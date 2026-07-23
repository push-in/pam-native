<?php

declare(strict_types=1);

namespace Pam\Native;

use LogicException;
use WeakMap;

abstract class Component implements Renderable
{
    /** @var WeakMap<object, string>|null */
    private static ?WeakMap $persisted = null;

    abstract public function render(): Renderable;

    final public function toElement(): Element
    {
        if ($this instanceof Restorable) {
            $persisted = self::$persisted ??= new WeakMap();
            if (!isset($persisted[$this])) {
                $state = State::get('component.'.$this->stateKey(), []);
                $this->restoreState(is_array($state) ? $state : []);
                $persisted[$this] = '';
            }
        }
        $rendered = $this->render();

        if ($rendered instanceof View) {
            $element = $rendered->withScope($this)->toElement();
        } else {
            if ($rendered === $this) {
                throw new LogicException('A component cannot render itself.');
            }

            $element = $rendered->toElement();
        }

        if ($this instanceof Restorable) {
            $state = $this->saveState();
            $hash = hash('xxh3', serialize($state));
            $persisted = self::$persisted ??= new WeakMap();
            if (($persisted[$this] ?? '') !== $hash) {
                State::set('component.'.$this->stateKey(), $state);
                $persisted[$this] = $hash;
            }
        }

        return $element;
    }
}

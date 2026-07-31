<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use InvalidArgumentException;
use Pam\Native\AsyncStatus;
use Pam\Native\AsyncValue;
use Pam\Native\Element;
use Pam\Native\Renderable;

final readonly class Suspense implements Renderable
{
    /**
     * @param Closure(mixed): Renderable $content
     * @param (Closure(AsyncValue): Renderable)|null $failure
     */
    private function __construct(
        private AsyncValue $value,
        private Closure $content,
        private Renderable $fallback,
        private ?Closure $failure,
    ) {
    }

    /**
     * @param Closure(mixed): Renderable $content
     * @param (Closure(AsyncValue): Renderable)|null $failure
     */
    public static function make(
        AsyncValue $value,
        Closure $content,
        Renderable $fallback,
        ?Closure $failure = null,
    ): self {
        return new self($value, $content, $fallback, $failure);
    }

    public function toElement(): Element
    {
        if ($this->value->status === AsyncStatus::Loading && !$this->value->hasData()) {
            return $this->fallback->toElement();
        }
        if (in_array($this->value->status, [AsyncStatus::Error, AsyncStatus::Offline], true)) {
            if ($this->value->hasData()) {
                return ($this->content)($this->value->data)->toElement();
            }
            if ($this->failure !== null) {
                return ($this->failure)($this->value)->toElement();
            }
            return $this->fallback->toElement();
        }
        if ($this->value->status === AsyncStatus::Empty && !$this->value->hasData()) {
            if ($this->failure !== null) {
                return ($this->failure)($this->value)->toElement();
            }
            return $this->fallback->toElement();
        }

        $rendered = ($this->content)($this->value->data);
        if (!$rendered instanceof Renderable) {
            throw new InvalidArgumentException('Suspense content must return a Renderable.');
        }
        return $rendered->toElement();
    }
}

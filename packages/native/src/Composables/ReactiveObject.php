<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use LogicException;

/**
 * Base of the framework composables: read-only reactive properties
 * (`$insets->top`, `$scheme->value`; `$insets.top` in templates). Every read
 * goes through a signal, so the reader re-renders when the value changes.
 */
abstract class ReactiveObject
{
    /** @return list<string> */
    abstract protected function properties(): array;

    abstract protected function read(string $name): mixed;

    final public function __get(string $name): mixed
    {
        if (!\in_array($name, $this->properties(), true)) {
            throw new LogicException(static::class." has no property {$name}. Available: ".implode(', ', $this->properties()).'.');
        }

        return $this->read($name);
    }

    final public function __isset(string $name): bool
    {
        return \in_array($name, $this->properties(), true);
    }

    final public function __set(string $name, mixed $value): void
    {
        throw new LogicException(static::class." is read-only; {$name} cannot be assigned.");
    }

    /** @return array<string, mixed> every property, read reactively */
    public function toArray(): array
    {
        $values = [];
        foreach ($this->properties() as $name) {
            $values[$name] = $this->read($name);
        }

        return $values;
    }
}

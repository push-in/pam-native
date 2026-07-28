<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;
use LogicException;
use Pam\Native\Internal\DependencyTracker;

final class ComponentState
{
    /** @param array<string, mixed> $values */
    public function __construct(
        private array $values,
        private readonly \Closure $changed,
    ) {
        foreach ($values as $key => $value) {
            self::assertKey($key);
            self::assertValue($value);
        }
    }

    public function __get(string $name): mixed
    {
        if (!array_key_exists($name, $this->values)) {
            throw new LogicException("Unknown local state {$name}.");
        }
        DependencyTracker::read($this, $name);

        return $this->values[$name];
    }

    public function __set(string $name, mixed $value): void
    {
        if (!array_key_exists($name, $this->values)) {
            throw new LogicException("Unknown local state {$name}.");
        }
        self::assertValue($value);
        $previous = $this->values[$name];
        if ($previous === $value) {
            return;
        }
        $this->values[$name] = $value;
        DependencyTracker::invalidate($this, $name);
        ($this->changed)($name, $value, $previous);
    }

    public function __isset(string $name): bool
    {
        return isset($this->values[$name]);
    }

    /** @return array<string, mixed> */
    public function all(): array
    {
        return $this->values;
    }

    /** @param array<string, mixed> $values */
    public function patch(array $values): void
    {
        foreach ($values as $name => $value) {
            $this->__set($name, $value);
        }
    }

    /** @param array<string, mixed> $values */
    public function replace(array $values): void
    {
        if (array_keys($values) !== array_keys($this->values)) {
            throw new InvalidArgumentException('Replacement local state has a different shape.');
        }
        foreach ($values as $name => $value) {
            $this->__set($name, $value);
        }
    }

    private static function assertKey(mixed $key): void
    {
        if (!is_string($key) || preg_match('/^[A-Za-z][A-Za-z0-9_]{0,127}$/D', $key) !== 1) {
            throw new InvalidArgumentException('Local state keys must be safe identifiers.');
        }
    }

    private static function assertValue(mixed $value): void
    {
        if (is_null($value) || is_scalar($value)) {
            return;
        }
        if (!is_array($value)) {
            throw new InvalidArgumentException('Local state accepts only JSON scalar and array values.');
        }
        foreach ($value as $nested) {
            self::assertValue($nested);
        }
        json_encode($value, JSON_THROW_ON_ERROR);
    }
}

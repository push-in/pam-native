<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use InvalidArgumentException;

final readonly class RouteContext
{
    /** @param array<string, string|int|float|bool|null> $params */
    public function __construct(
        public string $name,
        private array $params = [],
    ) {
    }

    /** @return array<string, string|int|float|bool|null> */
    public function all(): array
    {
        return $this->params;
    }

    public function has(string $key): bool
    {
        return array_key_exists($key, $this->params);
    }

    public function string(string $key, ?string $default = null): ?string
    {
        $value = $this->params[$key] ?? $default;
        if ($value !== null && !is_string($value)) {
            throw new InvalidArgumentException("Route parameter {$key} is not a string.");
        }

        return $value;
    }

    public function integer(string $key, ?int $default = null): ?int
    {
        $value = $this->params[$key] ?? $default;
        if ($value !== null && !is_int($value)) {
            throw new InvalidArgumentException("Route parameter {$key} is not an integer.");
        }

        return $value;
    }

    public function decimal(string $key, ?float $default = null): ?float
    {
        $value = $this->params[$key] ?? $default;
        if ($value !== null && !is_float($value) && !is_int($value)) {
            throw new InvalidArgumentException("Route parameter {$key} is not numeric.");
        }

        return $value === null ? null : (float) $value;
    }

    public function boolean(string $key, ?bool $default = null): ?bool
    {
        $value = $this->params[$key] ?? $default;
        if ($value !== null && !is_bool($value)) {
            throw new InvalidArgumentException("Route parameter {$key} is not boolean.");
        }

        return $value;
    }
}

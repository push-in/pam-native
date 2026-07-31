<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use InvalidArgumentException;
use ReflectionClass;

/** Sparse option layer: omitted keys inherit; explicit false and null override. */
final readonly class ScreenOptionsPatch
{
    /** @param array<string, mixed> $values */
    private function __construct(private array $values)
    {
        $allowed = [];
        $constructor = (new ReflectionClass(ScreenOptions::class))->getConstructor();
        foreach ($constructor?->getParameters() ?? [] as $parameter) {
            $allowed[$parameter->getName()] = true;
        }
        foreach ($values as $key => $_) {
            if (!is_string($key) || !isset($allowed[$key])) {
                throw new InvalidArgumentException("Unknown screen option {$key}.");
            }
        }
    }

    /** @param array<string, mixed> $values */
    public static function from(array $values): self
    {
        return new self($values);
    }

    public static function one(string $option, mixed $value): self
    {
        return new self([$option => $value]);
    }

    /** @return array<string, mixed> */
    public function values(): array
    {
        return $this->values;
    }

    public function apply(ScreenOptions $base): ScreenOptions
    {
        return new ScreenOptions(...array_replace(get_object_vars($base), $this->values));
    }
}

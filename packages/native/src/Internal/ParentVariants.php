<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

/**
 * Lazily accumulated declarative values of a template element's native
 * ancestors (`__parentVariants` for PHP component factories). Most elements
 * never reach a factory that reads them, so the filtered merge is computed
 * only when one asks.
 *
 * @internal
 */
final class ParentVariants
{
    private const int MAX_CONTEXT_DEPTH = 4;
    private const int MAX_CONTEXT_ITEMS = 1_024;

    /** @var array<string, mixed>|null */
    private ?array $resolved = null;

    /** @param array<string, mixed> $values */
    private function __construct(
        private readonly mixed $parent,
        private readonly array $values,
    ) {
    }

    /** @param array<string, mixed> $values */
    public static function extend(mixed $parent, array $values): self
    {
        return new self($parent, $values);
    }

    /** @return array<string, mixed> */
    public static function resolve(mixed $variants): array
    {
        if ($variants instanceof self) {
            return $variants->resolved ??= $variants->materialize();
        }

        return is_array($variants) ? $variants : [];
    }

    /** @return array<string, mixed> */
    private function materialize(): array
    {
        $own = [];
        foreach ($this->values as $name => $value) {
            if (!str_starts_with((string) $name, '__pam') && self::declarative($value)) {
                $own[$name] = $value;
            }
        }

        return [...self::resolve($this->parent), ...$own];
    }

    private static function declarative(mixed $value, int $depth = 0): bool
    {
        if (is_scalar($value)) {
            return true;
        }
        if ($value === null) {
            return $depth > 0;
        }
        if (
            !is_array($value)
            || $depth >= self::MAX_CONTEXT_DEPTH
            || count($value) > self::MAX_CONTEXT_ITEMS
        ) {
            return false;
        }

        foreach ($value as $key => $item) {
            if (
                !is_int($key)
                && preg_match('/^[A-Za-z][A-Za-z0-9_-]{0,127}$/D', $key) !== 1
            ) {
                return false;
            }
            if (!self::declarative($item, $depth + 1)) {
                return false;
            }
        }

        return true;
    }
}

<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use InvalidArgumentException;

final class TemplateRegistry
{
    /** @var array<string, Closure(array<string, mixed>, list<Element>, ?object): Renderable> */
    private static array $components = [];

    /** @var array<string, array<int, string|int|float|bool>> */
    private static array $classes = [];

    private function __construct()
    {
    }

    /**
     * @param Closure(array<string, mixed>, list<Element>, ?object): Renderable $factory
     */
    public static function component(string $tag, Closure $factory): void
    {
        self::assertName($tag);
        self::$components[$tag] = $factory;
    }

    public static function view(string $tag, string $view): void
    {
        self::assertName($tag);
        self::component(
            $tag,
            static fn (array $props, array $children, ?object $_scope): View => View::make(
                $view,
                ['props' => $props, 'slot' => $children],
            ),
        );
    }

    /** @return Closure(array<string, mixed>, list<Element>, ?object): Renderable|null */
    public static function factory(string $tag): ?Closure
    {
        return self::$components[$tag] ?? null;
    }

    /**
     * @param array<int, mixed> $properties
     */
    public static function style(string $class, array $properties): void
    {
        self::assertName($class);

        $validated = [];

        foreach ($properties as $key => $value) {
            PropKey::from($key);

            if (!is_string($value) && !is_int($value) && !is_float($value) && !is_bool($value)) {
                throw new InvalidArgumentException('Template class properties must be scalar.');
            }

            $validated[$key] = $value;
        }

        self::$classes[$class] = $validated;
    }

    /** @return array<int, string|int|float|bool>|null */
    public static function classProperties(string $class): ?array
    {
        return self::$classes[$class] ?? null;
    }

    private static function assertName(string $name): void
    {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/', $name) !== 1) {
            throw new InvalidArgumentException('Template names must be safe identifiers.');
        }
    }
}

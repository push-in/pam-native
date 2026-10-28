<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use BackedEnum;
use LogicException;
use Pam\Native\Navigation\RouteContext;
use ReflectionMethod;
use ReflectionNamedType;
use ReflectionParameter;

use function array_key_exists;
use function filter_var;
use function is_a;
use function is_bool;
use function is_float;
use function is_int;
use function is_numeric;
use function is_string;

/**
 * Binds route parameters to `setup(...)` by name, with the types the
 * signature declares: `setup(string $id)`, `setup(int $page = 1)`,
 * `setup(Tab $tab)` (a backed enum), `setup(RouteContext $route)`. Path
 * segments arrive as strings and are converted when the conversion is exact
 * ("42" to int 42, "true" to bool); anything else is a clear error naming the
 * component, the parameter and the received value.
 *
 * @internal
 */
final class SetupArguments
{
    private static ?object $mismatch = null;

    private function __construct()
    {
    }

    /**
     * @param array<string, mixed> $params
     * @return array<string, mixed>
     */
    public static function resolve(
        ReflectionMethod $method,
        array $params,
        ?RouteContext $route,
        string $component,
    ): array {
        $arguments = [];
        foreach ($method->getParameters() as $parameter) {
            if ($parameter->isVariadic()) {
                break;
            }
            $name = $parameter->getName();
            $type = $parameter->getType();
            if ($type instanceof ReflectionNamedType && $type->getName() === RouteContext::class) {
                if ($route === null && !$type->allowsNull()) {
                    throw new LogicException("{$component}::setup() asks for the RouteContext but it was not shown by a route.");
                }
                $arguments[$name] = $route;
                continue;
            }
            if (array_key_exists($name, $params)) {
                $arguments[$name] = self::coerce($component, $parameter, $params[$name]);
                continue;
            }
            if ($parameter->isDefaultValueAvailable()) {
                continue;
            }
            if ($type === null || $type->allowsNull()) {
                $arguments[$name] = null;
                continue;
            }
            throw new LogicException(
                "{$component}::setup() requires the route parameter \${$name}, which the route did not provide.",
            );
        }

        return $arguments;
    }

    private static function coerce(string $component, ReflectionParameter $parameter, mixed $value): mixed
    {
        $type = $parameter->getType();
        if (!$type instanceof ReflectionNamedType) {
            return $value;
        }
        if ($value === null && $type->allowsNull()) {
            return null;
        }
        $expected = $type->getName();
        $converted = match (true) {
            $expected === 'mixed' => $value,
            $expected === 'string' => is_string($value) ? $value : (is_int($value) || is_float($value) ? (string) $value : self::fail()),
            $expected === 'int' => is_int($value) ? $value : (is_string($value) && preg_match('/^-?[0-9]{1,18}$/D', $value) === 1 ? (int) $value : self::fail()),
            $expected === 'float' => is_float($value) || is_int($value) ? (float) $value : (is_string($value) && is_numeric($value) ? (float) $value : self::fail()),
            $expected === 'bool' => is_bool($value) ? $value : (is_string($value) || is_int($value) ? filter_var($value, FILTER_VALIDATE_BOOL, FILTER_NULL_ON_FAILURE) ?? self::fail() : self::fail()),
            is_a($expected, BackedEnum::class, true) => $value instanceof $expected ? $value : ((is_string($value) || is_int($value)) ? ($expected::tryFrom(self::enumKey($expected, $value)) ?? self::fail()) : self::fail()),
            default => is_object($value) && $value instanceof $expected ? $value : self::fail(),
        };
        if ($converted === self::fail()) {
            $actual = is_string($value) ? '"'.$value.'"' : get_debug_type($value);
            throw new LogicException(
                "{$component}::setup() parameter \${$parameter->getName()} expects {$expected}, {$actual} given by the route.",
            );
        }

        return $converted;
    }

    /** @param class-string<BackedEnum> $enum */
    private static function enumKey(string $enum, string|int $value): string|int
    {
        $sample = $enum::cases()[0] ?? null;
        if ($sample !== null && is_int($sample->value) && is_string($value) && preg_match('/^-?[0-9]{1,18}$/D', $value) === 1) {
            return (int) $value;
        }

        return $sample !== null && is_string($sample->value) ? (string) $value : $value;
    }

    /** Sentinel for a value that cannot become the declared type. */
    private static function fail(): object
    {
        return self::$mismatch ??= new \stdClass();
    }
}


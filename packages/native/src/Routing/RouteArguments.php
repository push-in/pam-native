<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use BackedEnum;
use InvalidArgumentException;
use ReflectionClass;
use ReflectionFunctionAbstract;
use ReflectionMethod;
use ReflectionNamedType;
use ReflectionParameter;
use WeakMap;

use function array_key_exists;
use function filter_var;
use function is_bool;
use function is_float;
use function is_int;
use function is_numeric;
use function is_string;

/**
 * Route params -> typed `setup(string $id, int $page = 1)` arguments.
 *
 * Contract with the component runtime: the router instantiates a screen
 * without arguments and calls `RouteArguments::attach($screen, $params)`;
 * when the component mounts, its setup is called with
 * `$screen->setup(...RouteArguments::for($screen))`. Screens whose
 * constructor declares parameters (pre-v2 style) receive them there instead.
 * Values coming from links are strings and are coerced to the declared
 * scalar or backed-enum type; anything else fails with a clear message.
 */
final class RouteArguments
{
    private static ?WeakMap $attached = null;

    private function __construct()
    {
    }

    /**
     * @param array<string, string|int|float|bool|null> $params
     * @param list<object> $services injectable by type (Navigator, RouteContext, ...)
     */
    public static function attach(object $screen, array $params, string $route = '', array $services = []): void
    {
        self::$attached ??= new WeakMap();
        self::$attached[$screen] = ['params' => $params, 'route' => $route, 'services' => $services];
    }

    /** @return array<string, string|int|float|bool|null> Params attached to a screen. */
    public static function params(object $screen): array
    {
        return self::$attached[$screen]['params'] ?? [];
    }

    /**
     * Named arguments for `$component->setup(...)` (or another method).
     *
     * @return array<string, mixed>
     */
    public static function for(object $component, string $method = 'setup'): array
    {
        if (!method_exists($component, $method)) return [];
        $entry = self::$attached[$component] ?? ['params' => [], 'route' => $component::class, 'services' => []];

        return self::resolve(new ReflectionMethod($component, $method), $entry['params'], $entry['route'] ?: $component::class, strict: false, services: $entry['services']);
    }

    /**
     * Instantiates a screen class for a route: constructor parameters are
     * filled from params; otherwise params are attached for setup().
     *
     * @param class-string $class
     * @param array<string, string|int|float|bool|null> $params
     * @param list<object> $services injected into class-typed parameters
     */
    public static function instantiate(string $class, array $params, string $route, array $services = []): object
    {
        $reflection = new ReflectionClass($class);
        if (!$reflection->isInstantiable()) {
            throw new InvalidArgumentException("Route {$route} screen {$class} is not instantiable.");
        }
        $constructor = $reflection->getConstructor();
        if ($constructor !== null && $constructor->getNumberOfParameters() > 0) {
            $screen = $reflection->newInstanceArgs(self::resolve($constructor, $params, $route, strict: false, services: $services));
        } else {
            $screen = $reflection->newInstance();
        }
        self::attach($screen, $params, $route, $services);

        return $screen;
    }

    /**
     * Declared route parameters of a screen class: its own setup(), else its
     * constructor.
     *
     * @param class-string $class
     * @return list<array{name: string, type: string, nullable: bool, required: bool, default: string|int|float|bool|null, enum: ?string}>
     */
    public static function signature(string $class): array
    {
        $function = self::parameterSource($class);
        if ($function === null) return [];
        $signature = [];
        foreach ($function->getParameters() as $parameter) {
            if (self::injectable($parameter)) continue;
            $type = $parameter->getType();
            $name = $type instanceof ReflectionNamedType ? $type->getName() : 'mixed';
            $enum = null;
            if ($type instanceof ReflectionNamedType && !$type->isBuiltin() && is_a($name, BackedEnum::class, true)) {
                $enum = $name;
                $name = (string) (new \ReflectionEnum($name))->getBackingType();
            }
            $default = $parameter->isDefaultValueAvailable() ? $parameter->getDefaultValue() : null;
            if ($default instanceof BackedEnum) $default = $default->value;
            $signature[] = [
                'name' => $parameter->getName(),
                'type' => $name,
                'nullable' => $type === null || $type->allowsNull(),
                'required' => !$parameter->isOptional(),
                'default' => is_scalar($default) || $default === null ? $default : null,
                'enum' => $enum,
            ];
        }

        return $signature;
    }

    /** @param class-string $class */
    public static function parameterSource(string $class): ?ReflectionFunctionAbstract
    {
        $reflection = new ReflectionClass($class);
        // A parameterless setup() (the framework's base one included) declares no route params.
        if ($reflection->hasMethod('setup') && $reflection->getMethod('setup')->getNumberOfParameters() > 0) {
            return $reflection->getMethod('setup');
        }
        $constructor = $reflection->getConstructor();

        return $constructor !== null && $constructor->getNumberOfParameters() > 0 ? $constructor : null;
    }

    /** True when a screen closure takes the RouteContext (it reads arbitrary params itself). */
    public static function readsContext(\Closure $closure): bool
    {
        foreach ((new \ReflectionFunction($closure))->getParameters() as $parameter) {
            $type = $parameter->getType();
            if ($type instanceof ReflectionNamedType && is_a($type->getName(), \Pam\Native\Navigation\RouteContext::class, true)) return true;
        }

        return false;
    }

    /** Class-typed (non-enum) parameters are services injected by type, not route params. */
    public static function injectable(ReflectionParameter $parameter): bool
    {
        $type = $parameter->getType();

        return $type instanceof ReflectionNamedType && !$type->isBuiltin()
            && !is_a($type->getName(), BackedEnum::class, true);
    }

    /**
     * @param array<string, string|int|float|bool|null> $params
     * @param list<object> $services
     * @return array<string, mixed>
     */
    public static function resolve(ReflectionFunctionAbstract $function, array $params, string $route, bool $strict, array $services = []): array
    {
        $arguments = [];
        $known = [];
        foreach ($function->getParameters() as $parameter) {
            $name = $parameter->getName();
            $known[$name] = true;
            if (self::injectable($parameter)) {
                $type = $parameter->getType()->getName();
                foreach ($services as $service) {
                    if ($service instanceof $type) {
                        $arguments[$name] = $service;
                        continue 2;
                    }
                }
                if ($parameter->isOptional()) continue;
                if ($parameter->allowsNull()) {
                    $arguments[$name] = null;
                    continue;
                }
                throw new InvalidArgumentException("Route {$route} cannot inject {$type} \${$name}.");
            }
            if (array_key_exists($name, $params)) {
                $arguments[$name] = self::coerce($route, $parameter, $params[$name]);
                continue;
            }
            if (!$parameter->isOptional()) {
                throw new InvalidArgumentException("Route {$route} requires parameter {$name}.");
            }
        }
        if ($strict) {
            foreach ($params as $name => $_) {
                if (!isset($known[$name])) {
                    throw new InvalidArgumentException("Route {$route} has no parameter {$name}.");
                }
            }
        }

        return $arguments;
    }

    /**
     * Validates navigation params against a screen class (unknown names,
     * missing required ones and wrong types fail) and returns them coerced.
     *
     * @param class-string $class
     * @param array<string, string|int|float|bool|null> $params
     * @param list<string> $extra names accepted besides the signature (path params)
     * @return array<string, string|int|float|bool|null>
     */
    public static function validate(string $class, array $params, string $route, array $extra = []): array
    {
        $function = self::parameterSource($class);
        if ($function === null) {
            foreach ($params as $name => $_) {
                if (!in_array($name, $extra, true)) {
                    throw new InvalidArgumentException("Route {$route} has no parameter {$name}.");
                }
            }

            return $params;
        }
        $known = array_flip($extra);
        $coerced = [];
        foreach ($function->getParameters() as $parameter) {
            if (self::injectable($parameter)) continue;
            $known[$parameter->getName()] = true;
            if (array_key_exists($parameter->getName(), $params)) {
                $value = self::coerce($route, $parameter, $params[$parameter->getName()]);
                $coerced[$parameter->getName()] = $value instanceof BackedEnum ? $value->value : $value;
            } elseif (!$parameter->isOptional()) {
                throw new InvalidArgumentException("Route {$route} requires parameter {$parameter->getName()}.");
            }
        }
        foreach ($params as $name => $value) {
            if (!isset($known[$name])) throw new InvalidArgumentException("Route {$route} has no parameter {$name}.");
            if (!array_key_exists($name, $coerced)) $coerced[$name] = $value;
        }

        return $coerced;
    }

    private static function coerce(string $route, ReflectionParameter $parameter, mixed $value): mixed
    {
        $type = $parameter->getType();
        if (!$type instanceof ReflectionNamedType) return $value;
        if ($value === null) {
            if ($type->allowsNull()) return null;
            throw new InvalidArgumentException("Route {$route} parameter {$parameter->getName()} cannot be null.");
        }
        $name = $type->getName();
        if (!$type->isBuiltin() && is_a($name, BackedEnum::class, true)) {
            if ($value instanceof $name) return $value;
            $backing = (string) (new \ReflectionEnum($name))->getBackingType();
            $raw = $backing === 'int' && is_string($value) && preg_match('/^-?\d+$/D', $value) === 1 ? (int) $value : $value;
            $case = is_int($raw) || is_string($raw) ? $name::tryFrom($raw) : null;
            if ($case === null) {
                throw new InvalidArgumentException("Route {$route} parameter {$parameter->getName()} is not a valid {$name}.");
            }

            return $case;
        }
        $coerced = match ($name) {
            'mixed' => $value,
            'string' => is_string($value) ? $value : (is_int($value) || is_float($value) ? (string) $value : null),
            'int' => is_int($value) ? $value : (is_string($value) && preg_match('/^-?\d{1,18}$/D', $value) === 1 ? (int) $value : null),
            'float' => is_float($value) || is_int($value) ? (float) $value : (is_string($value) && is_numeric($value) ? (float) $value : null),
            'bool' => is_bool($value) ? $value : (is_string($value) || is_int($value)
                ? filter_var($value, FILTER_VALIDATE_BOOLEAN, FILTER_NULL_ON_FAILURE) : null),
            default => $value,
        };
        if ($coerced === null) {
            throw new InvalidArgumentException(
                "Route {$route} parameter {$parameter->getName()} expects {$name}, ".get_debug_type($value).' given.',
            );
        }

        return $coerced;
    }
}

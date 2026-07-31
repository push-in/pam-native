<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use InvalidArgumentException;
use Pam\Native\Navigation\RouteContext;
use Pam\Native\Renderable;
use ReflectionClass;
use ReflectionNamedType;
use ReflectionParameter;

/** @internal */
final class ScreenFactory
{
    /**
     * @param class-string<Renderable> $screen
     */
    public static function make(string $screen, RouteContext $route): Renderable
    {
        if (!is_a($screen, Renderable::class, true)) {
            throw new InvalidArgumentException("Route {$route->name} screen {$screen} must implement Renderable.");
        }

        $reflection = new ReflectionClass($screen);
        if (!$reflection->isInstantiable()) {
            throw new InvalidArgumentException("Route {$route->name} screen {$screen} is not instantiable.");
        }

        $constructor = $reflection->getConstructor();
        $params = $route->all();
        if ($constructor === null) {
            if ($params !== []) {
                throw new InvalidArgumentException("Route {$route->name} does not accept parameters.");
            }

            /** @var Renderable */
            return $reflection->newInstance();
        }

        $arguments = [];
        $known = [];
        foreach ($constructor->getParameters() as $parameter) {
            $known[$parameter->getName()] = true;
            if (array_key_exists($parameter->getName(), $params)) {
                $value = $params[$parameter->getName()];
                self::assertType($route->name, $parameter, $value);
                $arguments[$parameter->getName()] = $value;
                continue;
            }
            if (!$parameter->isOptional()) {
                throw new InvalidArgumentException(
                    "Route {$route->name} requires parameter {$parameter->getName()}.",
                );
            }
        }

        $unknown = array_diff_key($params, $known);
        if ($unknown !== []) {
            throw new InvalidArgumentException(
                "Route {$route->name} received unknown parameter ".array_key_first($unknown).'.',
            );
        }

        /** @var Renderable */
        return $reflection->newInstanceArgs($arguments);
    }

    private static function assertType(
        string $route,
        ReflectionParameter $parameter,
        string|int|float|bool|null $value,
    ): void {
        $type = $parameter->getType();
        if (!$type instanceof ReflectionNamedType || !$type->isBuiltin()) {
            return;
        }
        if ($value === null && $type->allowsNull()) {
            return;
        }

        $matches = match ($type->getName()) {
            'string' => is_string($value),
            'int' => is_int($value),
            'float' => is_float($value) || is_int($value),
            'bool' => is_bool($value),
            'mixed' => true,
            default => false,
        };
        if (!$matches) {
            $actual = $value === null ? 'null' : get_debug_type($value);
            throw new InvalidArgumentException(
                "Route {$route} parameter {$parameter->getName()} expects {$type->getName()}, {$actual} given.",
            );
        }
    }
}

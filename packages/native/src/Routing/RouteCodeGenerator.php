<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use InvalidArgumentException;
use JsonException;

final class RouteCodeGenerator
{
    /** @return array<string, string> filename => PHP source */
    public static function fromJson(string $json): array
    {
        try {
            $manifest = json_decode($json, true, flags: JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new InvalidArgumentException('The route manifest is not valid JSON.', previous: $error);
        }
        if (!is_array($manifest)) {
            throw new InvalidArgumentException('The route manifest must be an object.');
        }
        return self::generate($manifest);
    }

    /**
     * @param array<string, mixed> $manifest
     * @return array<string, string>
     */
    public static function generate(array $manifest): array
    {
        $namespace = self::qualifiedName($manifest['namespace'] ?? null, 'namespace');
        $enum = self::identifier($manifest['enum'] ?? 'AppRoute', 'enum');
        $helper = self::identifier($manifest['helper'] ?? 'Routes', 'helper');
        $routes = $manifest['routes'] ?? null;
        if (!is_array($routes) || !array_is_list($routes) || $routes === []) {
            throw new InvalidArgumentException('The route manifest requires a non-empty routes list.');
        }

        $cases = [];
        $methods = [];
        $names = [];
        $caseSymbols = [];
        $methodSymbols = [];
        foreach ($routes as $index => $route) {
            if (!is_array($route)) {
                throw new InvalidArgumentException("Route at index {$index} must be an object.");
            }
            $name = $route['name'] ?? null;
            if (!is_string($name) || preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/D', $name) !== 1) {
                throw new InvalidArgumentException("Route at index {$index} has an invalid name.");
            }
            $case = self::identifier($route['case'] ?? self::studly($name), "case for {$name}");
            $method = self::identifier($route['method'] ?? self::camel($name), "method for {$name}");
            if (isset($names[$name]) || isset($caseSymbols[strtolower($case)]) || isset($methodSymbols[strtolower($method)])) {
                throw new InvalidArgumentException("Route {$name} produces a duplicate name, case or helper method.");
            }
            $names[$name] = true;
            $caseSymbols[strtolower($case)] = true;
            $methodSymbols[strtolower($method)] = true;
            [$signature, $arguments] = self::parameters($route['params'] ?? [], $name);
            $cases[] = "    case {$case} = ".var_export($name, true).';';
            $methods[] = "    public static function {$method}({$signature}): RouteTarget\n"
                ."    {\n"
                ."        return Route::to({$enum}::{$case}{$arguments});\n"
                ."    }";
        }

        $header = "<?php\n\ndeclare(strict_types=1);\n\nnamespace {$namespace};\n";
        $enumSource = $header."\nenum {$enum}: string\n{\n".implode("\n", $cases)."\n}\n";
        $helperSource = $header
            ."\nuse Pam\\Native\\Routing\\Route;\n"
            ."use Pam\\Native\\Routing\\RouteTarget;\n\n"
            ."final class {$helper}\n{\n"
            ."    private function __construct() {}\n\n"
            .implode("\n\n", $methods)."\n}\n";

        return ["{$enum}.php" => $enumSource, "{$helper}.php" => $helperSource];
    }

    /** @return array{string, string} */
    private static function parameters(mixed $params, string $route): array
    {
        if (!is_array($params) || !array_is_list($params)) {
            throw new InvalidArgumentException("Route {$route} params must be a list.");
        }
        $signature = [];
        $arguments = [];
        $optionalSeen = false;
        $names = [];
        foreach ($params as $index => $param) {
            if (!is_array($param)) {
                throw new InvalidArgumentException("Parameter {$index} of route {$route} must be an object.");
            }
            $name = self::identifier($param['name'] ?? null, "parameter {$index} of {$route}");
            if (isset($names[$name])) {
                throw new InvalidArgumentException("Route {$route} repeats parameter {$name}.");
            }
            $names[$name] = true;
            $type = $param['type'] ?? 'string';
            if (!is_string($type) || !in_array($type, ['string', 'int', 'float', 'bool'], true)) {
                throw new InvalidArgumentException("Parameter {$name} of route {$route} has an unsupported type.");
            }
            $nullable = ($param['nullable'] ?? false) === true;
            $required = ($param['required'] ?? true) === true;
            if (!$required) $optionalSeen = true;
            elseif ($optionalSeen) {
                throw new InvalidArgumentException("Required parameter {$name} cannot follow optional parameters on route {$route}.");
            }
            $declaration = ($nullable ? '?' : '').$type.' $'.$name;
            if (!$required) {
                $default = $param['default'] ?? null;
                if ($default !== null && get_debug_type($default) !== $type && !($type === 'float' && is_int($default))) {
                    throw new InvalidArgumentException("Default for {$route}.{$name} does not match {$type}.");
                }
                if ($default === null && !$nullable) {
                    throw new InvalidArgumentException("Optional parameter {$route}.{$name} needs a default or nullable=true.");
                }
                $declaration .= ' = '.var_export($default, true);
            }
            $signature[] = $declaration;
            $arguments[] = $name.': $'.$name;
        }
        return [implode(', ', $signature), $arguments === [] ? '' : ', '.implode(', ', $arguments)];
    }

    private static function identifier(mixed $value, string $field): string
    {
        if (!is_string($value) || preg_match('/^[A-Za-z_][A-Za-z0-9_]*$/D', $value) !== 1) {
            throw new InvalidArgumentException("Invalid {$field} identifier.");
        }
        return $value;
    }

    private static function qualifiedName(mixed $value, string $field): string
    {
        if (!is_string($value) || preg_match('/^[A-Za-z_][A-Za-z0-9_]*(?:\\\\[A-Za-z_][A-Za-z0-9_]*)*$/D', $value) !== 1) {
            throw new InvalidArgumentException("Invalid {$field} name.");
        }
        return $value;
    }

    private static function studly(string $value): string
    {
        return str_replace(' ', '', ucwords(str_replace(['.', '-', '_'], ' ', $value)));
    }

    private static function camel(string $value): string
    {
        return lcfirst(self::studly($value));
    }
}

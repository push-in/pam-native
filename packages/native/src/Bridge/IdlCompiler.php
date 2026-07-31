<?php

declare(strict_types=1);

namespace Pam\Native\Bridge;

use InvalidArgumentException;
use JsonException;

final class IdlCompiler
{
    private const MAX_SCHEMA_BYTES = 1_048_576;

    private function __construct()
    {
    }

    public static function compile(string $json): IdlArtifacts
    {
        if ($json === '' || strlen($json) > self::MAX_SCHEMA_BYTES) {
            throw new InvalidArgumentException('PAM IDL must be between 1 byte and 1 MiB.');
        }
        try {
            $decoded = json_decode($json, true, 64, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new InvalidArgumentException('PAM IDL is not valid JSON.', previous: $error);
        }
        if (!is_array($decoded)) {
            throw new InvalidArgumentException('PAM IDL root must be an object.');
        }

        $schema = self::validate($decoded);
        $canonical = json_encode($schema, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);

        return new IdlArtifacts(
            php: self::php($schema),
            kotlin: self::kotlin($schema),
            swift: self::swift($schema),
            rust: self::rust($schema),
            fingerprint: hash('sha256', $canonical),
        );
    }

    /**
     * @param array<array-key, mixed> $schema
     * @return array{version: int, namespace: string, modules: list<array{id: int, name: string, methods: list<array{id: int, name: string, parameters: list<array{id: int, name: string, type: int, required: bool}>}>}>}
     */
    private static function validate(array $schema): array
    {
        if (($schema['version'] ?? null) !== 1) {
            throw new InvalidArgumentException('PAM IDL version must be 1.');
        }
        $namespace = $schema['namespace'] ?? null;
        if (!is_string($namespace) || preg_match('/^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$/', $namespace) !== 1) {
            throw new InvalidArgumentException('PAM IDL namespace is invalid.');
        }
        $modules = $schema['modules'] ?? null;
        if (!is_array($modules) || count($modules) > 256) {
            throw new InvalidArgumentException('PAM IDL modules must be a bounded list.');
        }

        $validatedModules = [];
        foreach (array_values($modules) as $moduleIndex => $module) {
            if (!is_array($module) || ($module['id'] ?? null) !== $moduleIndex + 1) {
                throw new InvalidArgumentException('PAM IDL module ids must be sequential integers starting at 1.');
            }
            $moduleName = self::identifier($module['name'] ?? null, 'module');
            $methods = $module['methods'] ?? null;
            if (!is_array($methods) || count($methods) > 256) {
                throw new InvalidArgumentException("PAM IDL module {$moduleName} methods must be a bounded list.");
            }
            $validatedMethods = [];
            foreach (array_values($methods) as $methodIndex => $method) {
                if (!is_array($method) || ($method['id'] ?? null) !== $methodIndex + 1) {
                    throw new InvalidArgumentException('PAM IDL method ids must be sequential integers starting at 1.');
                }
                $methodName = self::identifier($method['name'] ?? null, 'method');
                $parameters = $method['parameters'] ?? [];
                if (!is_array($parameters) || count($parameters) > 128) {
                    throw new InvalidArgumentException("PAM IDL method {$methodName} parameters must be a bounded list.");
                }
                $validatedParameters = [];
                foreach (array_values($parameters) as $fieldIndex => $field) {
                    if (!is_array($field) || ($field['id'] ?? null) !== $fieldIndex + 1) {
                        throw new InvalidArgumentException('PAM IDL field ids must be sequential integers starting at 1.');
                    }
                    $type = is_int($field['type'] ?? null) ? IdlType::tryFrom($field['type']) : null;
                    if ($type === null) {
                        throw new InvalidArgumentException('PAM IDL fields require a known integer type.');
                    }
                    $validatedParameters[] = [
                        'id' => $fieldIndex + 1,
                        'name' => self::identifier($field['name'] ?? null, 'field'),
                        'type' => $type->value,
                        'required' => ($field['required'] ?? true) === true,
                    ];
                }
                $validatedMethods[] = [
                    'id' => $methodIndex + 1,
                    'name' => $methodName,
                    'parameters' => $validatedParameters,
                ];
            }
            $validatedModules[] = [
                'id' => $moduleIndex + 1,
                'name' => $moduleName,
                'methods' => $validatedMethods,
            ];
        }

        return ['version' => 1, 'namespace' => $namespace, 'modules' => $validatedModules];
    }

    private static function identifier(mixed $value, string $kind): string
    {
        if (!is_string($value) || preg_match('/^[A-Za-z][A-Za-z0-9_]{0,63}$/', $value) !== 1) {
            throw new InvalidArgumentException("PAM IDL {$kind} name is invalid.");
        }
        return $value;
    }

    /** @param array{namespace: string, modules: list<array{id: int, name: string, methods: list<array{id: int, name: string, parameters: list<array{id: int, name: string, type: int, required: bool}>}>}>} $schema */
    private static function php(array $schema): string
    {
        $namespace = str_replace('.', '\\', $schema['namespace']);
        $lines = ["<?php", '', 'declare(strict_types=1);', '', "namespace {$namespace};", ''];
        foreach ($schema['modules'] as $module) {
            $class = self::className($module['name']).'Bridge';
            $lines[] = "final class {$class}";
            $lines[] = '{';
            $lines[] = "    public const int ID = {$module['id']};";
            foreach ($module['methods'] as $method) {
                $constant = strtoupper(preg_replace('/[^A-Za-z0-9]+/', '_', $method['name']) ?? $method['name']);
                $lines[] = "    public const int {$constant} = {$method['id']};";
            }
            $lines[] = '}';
            $lines[] = '';
        }
        return implode("\n", $lines);
    }

    /** @param array{namespace: string, modules: list<array{id: int, name: string, methods: list<array{id: int, name: string, parameters: list<array{id: int, name: string, type: int, required: bool}>}>}>} $schema */
    private static function kotlin(array $schema): string
    {
        $lines = ["package {$schema['namespace']}", ''];
        foreach ($schema['modules'] as $module) {
            $class = self::className($module['name']).'Bridge';
            $lines[] = "object {$class} {";
            $lines[] = "    const val ID: Int = {$module['id']}";
            foreach ($module['methods'] as $method) {
                $constant = strtoupper(preg_replace('/[^A-Za-z0-9]+/', '_', $method['name']) ?? $method['name']);
                $lines[] = "    const val {$constant}: Int = {$method['id']}";
            }
            $lines[] = '}';
            $lines[] = '';
        }
        return implode("\n", $lines);
    }

    /** @param array{namespace: string, modules: list<array{id: int, name: string, methods: list<array{id: int, name: string, parameters: list<array{id: int, name: string, type: int, required: bool}>}>}>} $schema */
    private static function swift(array $schema): string
    {
        $lines = ['import Foundation', ''];
        foreach ($schema['modules'] as $module) {
            $class = self::className($module['name']).'Bridge';
            $lines[] = "enum {$class} {";
            $lines[] = "    static let id: Int = {$module['id']}";
            foreach ($module['methods'] as $method) {
                $lines[] = "    static let {$method['name']}: Int = {$method['id']}";
            }
            $lines[] = '}';
            $lines[] = '';
        }
        return implode("\n", $lines);
    }

    /** @param array{namespace: string, modules: list<array{id: int, name: string, methods: list<array{id: int, name: string, parameters: list<array{id: int, name: string, type: int, required: bool}>}>}>} $schema */
    private static function rust(array $schema): string
    {
        $lines = ['// Generated by PAM Native IDL. Do not edit.', ''];
        foreach ($schema['modules'] as $module) {
            $prefix = strtoupper(preg_replace('/[^A-Za-z0-9]+/', '_', $module['name']) ?? $module['name']);
            $lines[] = "pub const {$prefix}_MODULE_ID: u16 = {$module['id']};";
            foreach ($module['methods'] as $method) {
                $methodName = strtoupper(preg_replace('/[^A-Za-z0-9]+/', '_', $method['name']) ?? $method['name']);
                $lines[] = "pub const {$prefix}_{$methodName}: u16 = {$method['id']};";
            }
            $lines[] = '';
        }
        return implode("\n", $lines);
    }

    private static function className(string $name): string
    {
        return implode('', array_map('ucfirst', preg_split('/[^A-Za-z0-9]+/', $name) ?: [$name]));
    }
}

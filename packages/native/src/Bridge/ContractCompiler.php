<?php

declare(strict_types=1);

namespace Pam\Native\Bridge;

use Pam\Native\Bridge\Attributes\NativeMethod;
use Pam\Native\Bridge\Attributes\NativeModule;
use Pam\Native\Bridge\Attributes\NativePermission;
use ReflectionClass;
use ReflectionMethod;
use ReflectionNamedType;

/**
 * @phpstan-type ParameterShape array{id: int, name: string, type: string, nullable: bool}
 * @phpstan-type MethodShape array{id: int, name: string, kind: int, timeoutMs: int, parameters: list<ParameterShape>, returns: string, permissions: list<string>}
 * @phpstan-type ModuleShape array{id: int, name: string, version: int, methods: list<MethodShape>}
 * @phpstan-type ManifestShape array{schema: int, namespace: string, modules: list<ModuleShape>}
 */
final class ContractCompiler
{
    /** @param list<class-string> $interfaces */
    public static function compile(array $interfaces, string $namespace = 'Pam.Generated'): ContractArtifacts
    {
        if ($interfaces === [] || count($interfaces) > 256) {
            throw new \InvalidArgumentException('Contract compilation requires between 1 and 256 interfaces.');
        }
        if (preg_match('/^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$/D', $namespace) !== 1) {
            throw new \InvalidArgumentException('Contract namespace must be a portable dotted identifier.');
        }
        $modules = [];
        foreach ($interfaces as $interface) {
            $reflection = new ReflectionClass($interface);
            if (!$reflection->isInterface()) {
                throw new \InvalidArgumentException("Native contract {$interface} must be an interface.");
            }
            $attribute = $reflection->getAttributes(NativeModule::class)[0] ?? null;
            if ($attribute === null) {
                throw new \InvalidArgumentException("Native contract {$interface} requires #[NativeModule].");
            }
            /** @var NativeModule $module */
            $module = $attribute->newInstance();
            $moduleName = $module->name ?? $reflection->getShortName();
            if (preg_match('/^[A-Za-z_][A-Za-z0-9_]{0,63}$/D', $moduleName) !== 1) {
                throw new \InvalidArgumentException("Native module {$moduleName} is not a portable identifier.");
            }
            /** @var list<MethodShape> $methods */
            $methods = [];
            foreach ($reflection->getMethods(ReflectionMethod::IS_PUBLIC) as $method) {
                $methods[] = self::method($method);
            }
            usort($methods, static fn (array $left, array $right): int => $left['id'] <=> $right['id']);
            self::sequential(array_column($methods, 'id'), "methods in {$interface}");
            $modules[] = [
                'id' => $module->id,
                'name' => $moduleName,
                'version' => $module->version,
                'methods' => $methods,
            ];
        }
        usort($modules, static fn (array $left, array $right): int => $left['id'] <=> $right['id']);
        self::sequential(array_column($modules, 'id'), 'modules');
        $manifest = ['schema' => 2, 'namespace' => $namespace, 'modules' => $modules];
        $canonical = json_encode($manifest, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES);

        return new ContractArtifacts(
            $manifest,
            self::php($manifest),
            self::kotlin($manifest),
            self::swift($manifest),
            hash('sha256', $canonical),
        );
    }

    /** @return MethodShape */
    private static function method(ReflectionMethod $method): array
    {
        $attribute = $method->getAttributes(NativeMethod::class)[0] ?? null;
        if ($attribute === null) {
            throw new \InvalidArgumentException("Native method {$method->getName()} requires #[NativeMethod].");
        }
        /** @var NativeMethod $native */
        $native = $attribute->newInstance();
        $parameters = [];
        foreach ($method->getParameters() as $index => $parameter) {
            $parameters[] = [
                'id' => $index + 1,
                'name' => $parameter->getName(),
                'type' => self::type($parameter->getType()),
                'nullable' => $parameter->allowsNull(),
            ];
        }
        $permissions = array_map(
            static fn ($item): string => $item->newInstance()->capability,
            $method->getAttributes(NativePermission::class),
        );
        sort($permissions, SORT_STRING);

        return [
            'id' => $native->id,
            'name' => $method->getName(),
            'kind' => $native->kind->value,
            'timeoutMs' => $native->timeoutMs,
            'parameters' => $parameters,
            'returns' => self::type($method->getReturnType()),
            'permissions' => $permissions,
        ];
    }

    private static function type(?\ReflectionType $type): string
    {
        if (!$type instanceof ReflectionNamedType) {
            throw new \InvalidArgumentException('Native contracts require named parameter and return types.');
        }
        $name = $type->getName();
        if (in_array($name, ['string', 'int', 'float', 'bool', 'array', 'void'], true)) {
            return $name;
        }
        if (enum_exists($name) || class_exists($name) || interface_exists($name)) {
            return ltrim($name, '\\');
        }
        throw new \InvalidArgumentException("Unsupported native contract type {$name}.");
    }

    /** @param list<int> $ids */
    private static function sequential(array $ids, string $label): void
    {
        if ($ids !== range(1, count($ids))) {
            throw new \InvalidArgumentException("Native contract {$label} must use sequential ids starting at 1.");
        }
    }

    /** @param ManifestShape $manifest */
    private static function php(array $manifest): string
    {
        return "<?php\n\ndeclare(strict_types=1);\n\nnamespace ".str_replace('.', '\\', $manifest['namespace']).";\n\n".self::constants($manifest, 'php');
    }

    /** @param ManifestShape $manifest */
    private static function kotlin(array $manifest): string
    {
        return "package {$manifest['namespace']}\n\n".self::constants($manifest, 'kotlin');
    }

    /** @param ManifestShape $manifest */
    private static function swift(array $manifest): string
    {
        return "import Foundation\n\n".self::constants($manifest, 'swift');
    }

    /** @param ManifestShape $manifest */
    private static function constants(array $manifest, string $language): string
    {
        $output = '';
        foreach ($manifest['modules'] as $module) {
            $name = preg_replace('/[^A-Za-z0-9]/', '', $module['name']).'Contract';
            $output .= match ($language) {
                'php' => "final class {$name}\n{\n    public const int ID = {$module['id']};\n",
                'kotlin' => "object {$name} {\n    const val ID: Int = {$module['id']}\n",
                default => "enum {$name} {\n    static let id: Int = {$module['id']}\n",
            };
            foreach ($module['methods'] as $method) {
                $identifier = $language === 'swift' ? $method['name'] : strtoupper($method['name']);
                $output .= match ($language) {
                    'php' => "    public const int {$identifier} = {$method['id']};\n",
                    'kotlin' => "    const val {$identifier}: Int = {$method['id']}\n",
                    default => "    static let {$identifier}: Int = {$method['id']}\n",
                };
            }
            $output .= "}\n\n";
        }
        return $output;
    }
}

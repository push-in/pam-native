<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use LogicException;
use Pam\Native\Component;
use Pam\Native\Renderable;
use Pam\Native\TemplateRegistry;
use ReflectionClass;
use ReflectionProperty;
use RuntimeException;
use WeakMap;

final class PamPhpRegistry
{
    /** @var array<class-string<Component>, PamPhpComponent> */
    private static array $components = [];

    /** @var array<class-string<Component>, string> */
    private static array $classFiles = [];

    /** @var WeakMap<object, array<string, Component>>|null */
    private static ?WeakMap $instances = null;

    /** @var WeakMap<object, array<string, true>>|null */
    private static ?WeakMap $seen = null;

    private static ?object $rootScope = null;
    private static bool $autoloadRegistered = false;

    private function __construct()
    {
    }

    public static function discover(string $sourcePath, string $cachePath): void
    {
        $components = PamPhpCompiler::compileDirectory($sourcePath, $cachePath);

        foreach ($components as $component) {
            /** @var class-string<Component> $className */
            $className = $component->className;
            $registered = self::$components[$className] ?? null;
            if (
                $registered !== null
                && $registered->source !== $component->source
            ) {
                throw new RuntimeException(
                    "Duplicate PAM component class {$className}.",
                );
            }
            self::$components[$className] = $component;
            self::$classFiles[$className] = $component->classFile;
            TemplateRegistry::component(
                $component->tag,
                static fn (
                    array $props,
                    array $children,
                    ?object $scope,
                ): Renderable => self::component(
                    $className,
                    $props,
                    $children,
                    $scope,
                ),
            );
        }

        self::registerAutoload();
    }

    public static function beginRender(): void
    {
        self::$seen = new WeakMap();
    }

    public static function finishRender(): void
    {
        $instances = self::$instances;
        if ($instances === null) {
            self::$seen = null;

            return;
        }

        $seen = self::$seen;
        foreach ($instances as $owner => $bucket) {
            $active = $seen !== null ? ($seen[$owner] ?? []) : [];
            foreach ($bucket as $cacheKey => $component) {
                if (isset($active[$cacheKey])) {
                    continue;
                }
                ComponentLifecycle::forget($component);
                unset($bucket[$cacheKey]);
            }
            $instances[$owner] = $bucket;
        }
        self::$seen = null;
    }

    public static function view(Component $component): CompiledComponentView
    {
        $definition = self::$components[$component::class] ?? null;

        if ($definition === null) {
            throw new LogicException(
                'Component '.$component::class
                .' must implement render() or be loaded from a .pam.php file.',
            );
        }

        return new CompiledComponentView($component, $definition->template);
    }

    /** @param array<string, mixed> $props */
    public static function make(string $className, array $props = []): Component
    {
        if (!isset(self::$components[$className])) {
            throw new RuntimeException(
                "PAM component class {$className} was not discovered.",
            );
        }

        /** @var class-string<Component> $componentClass */
        $componentClass = $className;

        return self::instantiate($componentClass, $props);
    }

    /** @return array<string, mixed> */
    public static function publicProps(Component $component): array
    {
        $props = [];
        $reflection = new ReflectionClass($component);

        foreach ($reflection->getProperties(ReflectionProperty::IS_PUBLIC) as $property) {
            if (
                $property->isStatic()
                || $property->getDeclaringClass()->getName() === Component::class
                || !$property->isInitialized($component)
            ) {
                continue;
            }
            $props[$property->getName()] = $property->getValue($component);
        }

        return $props;
    }

    public static function reset(): void
    {
        self::releaseInstances();
        self::$components = [];
        self::$classFiles = [];
    }

    public static function releaseInstances(): void
    {
        $instances = self::$instances;
        if ($instances !== null) {
            foreach ($instances as $bucket) {
                foreach ($bucket as $component) {
                    ComponentLifecycle::forget($component);
                }
            }
        }
        self::$instances = null;
        self::$seen = null;
        self::$rootScope = null;
    }

    /**
     * @param class-string<Component> $className
     * @param array<string, mixed> $values
     * @param list<\Pam\Native\Element> $children
     */
    private static function component(
        string $className,
        array $values,
        array $children,
        ?object $scope,
    ): Component {
        $identity = self::stringValue(
            $values['__pamNodePath'] ?? $values['key'] ?? $className,
        );
        $slots = $values['__pamSlots'] ?? ['slot' => $children];
        $listeners = $values['__pamComponentEvents'] ?? [];
        unset(
            $values['__pamNodePath'],
            $values['__pamSlots'],
            $values['__pamComponentEvents'],
            $values['__parentVariants'],
            $values['__pamEventContexts'],
            $values['className'],
            $values['key'],
        );

        if (!is_array($slots) || !is_array($listeners)) {
            throw new RuntimeException('Compiled component context is invalid.');
        }

        $owner = $scope ?? (self::$rootScope ??= new \stdClass());
        $instances = self::$instances ??= new WeakMap();
        $bucket = $instances[$owner] ?? [];
        $cacheKey = $className.'@'.$identity;
        $seen = self::$seen ??= new WeakMap();
        $active = $seen[$owner] ?? [];
        $active[$cacheKey] = true;
        $seen[$owner] = $active;
        $instance = $bucket[$cacheKey] ?? null;

        if (!$instance instanceof $className) {
            $instance = self::instantiate($className, $values);
        } elseif (!self::updateProps($instance, $values)) {
            ComponentLifecycle::forget($instance);
            $instance = self::instantiate($className, $values);
        }

        /** @var array<string, list<Renderable>> $safeSlots */
        $safeSlots = $slots;
        /** @var array<string, Closure> $safeListeners */
        $safeListeners = $listeners;
        $instance->__pamConfigure($safeSlots, $safeListeners);
        $bucket[$cacheKey] = $instance;
        $instances[$owner] = $bucket;

        return $instance;
    }

    /**
     * @param class-string<Component> $className
     * @param array<string, mixed> $props
     */
    private static function instantiate(string $className, array $props): Component
    {
        self::autoload($className);

        $reflection = new ReflectionClass($className);
        if (!$reflection->isSubclassOf(Component::class)) {
            throw new RuntimeException(
                "PAM component {$className} must extend ".Component::class.'.',
            );
        }

        $constructor = $reflection->getConstructor();
        if ($constructor === null) {
            if ($props !== []) {
                throw new RuntimeException(
                    "PAM component {$className} does not accept props: "
                    .implode(', ', array_keys($props)).'.',
                );
            }

            return $reflection->newInstance();
        }

        $arguments = [];
        $accepted = [];
        foreach ($constructor->getParameters() as $parameter) {
            $name = $parameter->getName();
            $accepted[$name] = true;
            if (array_key_exists($name, $props)) {
                $arguments[] = $props[$name];
            } elseif ($parameter->isDefaultValueAvailable()) {
                $arguments[] = $parameter->getDefaultValue();
            } else {
                throw new RuntimeException(
                    "Required prop {$className}::\${$name} is missing.",
                );
            }
        }
        $unknown = array_diff_key($props, $accepted);
        if ($unknown !== []) {
            throw new RuntimeException(
                "Unknown props for {$className}: "
                .implode(', ', array_keys($unknown)).'.',
            );
        }

        return $reflection->newInstanceArgs($arguments);
    }

    /**
     * Returns false when immutable or private constructor props require a
     * fresh component instance.
     *
     * @param array<string, mixed> $props
     */
    private static function updateProps(Component $component, array $props): bool
    {
        $reflection = new ReflectionClass($component);
        $constructor = $reflection->getConstructor();

        if ($constructor === null) {
            return $props === [];
        }

        foreach ($constructor->getParameters() as $parameter) {
            $name = $parameter->getName();
            if (!array_key_exists($name, $props) || !$reflection->hasProperty($name)) {
                continue;
            }
            $property = $reflection->getProperty($name);
            if (!$property->isInitialized($component)) {
                return false;
            }
            $previous = $property->getValue($component);
            if ($previous === $props[$name]) {
                continue;
            }
            if (!$property->isPublic() || $property->isReadOnly()) {
                return false;
            }
            $property->setValue($component, $props[$name]);
            $component->__pamNotifyUpdated($name);
        }

        return true;
    }

    private static function registerAutoload(): void
    {
        if (self::$autoloadRegistered) {
            return;
        }
        spl_autoload_register(self::autoload(...), prepend: true);
        self::$autoloadRegistered = true;
    }

    private static function autoload(string $className): void
    {
        $file = self::$classFiles[$className] ?? null;

        if ($file !== null && !class_exists($className, false)) {
            require $file;
        }
    }

    private static function stringValue(mixed $value): string
    {
        if (!is_string($value) && !is_int($value)) {
            throw new RuntimeException('PAM component identity must be a string or integer.');
        }

        return (string) $value;
    }
}

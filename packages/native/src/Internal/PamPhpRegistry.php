<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use BackedEnum;
use LogicException;
use Pam\Native\Attributes\Prop;
use Pam\Native\Component;
use Pam\Native\Renderable;
use Pam\Native\TemplateRegistry;
use Pam\Native\UI\Contract\ComponentContractFactory;
use ReflectionClass;
use RuntimeException;
use WeakMap;

use function array_key_exists;
use function count;
use function is_array;
use function is_float;
use function is_int;
use function is_string;

final class PamPhpRegistry
{
    /** @var array<class-string<Component>, PamPhpComponent> */
    private static array $components = [];

    /** @var array<class-string<Component>, string> */
    private static array $classFiles = [];

    /**
     * @var array<class-string<Component>, array{
     *     factory: Closure(array): Component,
     *     parameters: list<array{
     *         name: string,
     *         default: bool,
     *         defaultValue: mixed,
     *         prop: Prop|null,
     *         mutable: bool
     *     }>
     * }>
     */
    private static array $metadata = [];

    /** @var WeakMap<object, ComponentInstances>|null */
    private static ?WeakMap $instances = null;

    /** Render pass whose used instances are tracked; null outside a pass. */
    private static ?int $seen = null;

    private static int $pass = 0;

    /** @var array{0: ComponentInstances, 1: string, 2: Component, 3: array<string, mixed>}|null */
    private static ?array $lastCall = null;

    /** @var array<class-string, array<string, true>|null> */
    private static array $propertyParameters = [];

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
            TemplateRegistry::compiledComponent(
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

        foreach ($components as $component) {
            if ($component->language !== \Pam\Native\LanguageVersion::Language2) {
                continue;
            }
            /** @var class-string<Component> $className */
            $className = $component->className;
            self::autoload($className);
            TemplateRegistry::contract(
                ComponentContractFactory::fromClass($className, $component->tag),
            );
        }

        foreach ($components as $component) {
            if ($component->language !== \Pam\Native\LanguageVersion::Language2) {
                continue;
            }
            /** @var class-string<Component> $className */
            $className = $component->className;
            TemplateContractValidator::validate($className, $component->template);
        }
    }

    public static function beginRender(): void
    {
        self::$seen = ++self::$pass;
    }

    public static function finishRender(): void
    {
        self::$lastCall = null;
        $instances = self::$instances;
        if ($instances === null) {
            self::$seen = null;

            return;
        }

        $seen = self::$seen;
        foreach ($instances as $owner => $bucket) {
            if (
                $seen !== null
                && $bucket->pass !== $seen
                && $owner instanceof Component
                && ComponentLifecycle::retainedWithoutRender($owner)
            ) {
                // The owner was reused: everything it rendered stays.
                continue;
            }
            $active = $seen !== null && $bucket->pass === $seen ? $bucket->active : [];
            if (count($active) === count($bucket->instances)) {
                // Every instance was used (active only holds instance keys).
                continue;
            }
            // Only the instances this pass did not use, in creation order.
            foreach (array_diff_key($bucket->instances, $active) as $cacheKey => $component) {
                ComponentLifecycle::forget($component);
                unset($bucket->instances[$cacheKey]);
                $path = $bucket->callPaths[$cacheKey] ?? null;
                if ($path !== null) {
                    unset($bucket->callPaths[$cacheKey], $bucket->calls[$path]);
                }
                $component->__pamRelease();
            }
        }
        self::$seen = null;
    }

    public static function view(Component $component): CompiledComponentView
    {
        $definition = self::$components[$component::class] ?? null;

        if ($definition === null) {
            throw new LogicException(
                'Component '.$component::class
                .' must implement render() or be loaded from a .pam component file.',
            );
        }

        return new CompiledComponentView($component, $definition->template);
    }

    public static function retainScope(Component $owner): void
    {
        $instances = self::$instances;
        $seen = self::$seen;
        if ($instances === null || $seen === null) {
            return;
        }
        $bucket = $instances[$owner] ?? null;
        if ($bucket === null || $bucket->instances === [] || $bucket->retained === $seen) {
            return;
        }
        $bucket->retained = $seen;
        if ($bucket->pass !== $seen) {
            $bucket->pass = $seen;
            $bucket->active = [];
        }
        foreach ($bucket->instances as $cacheKey => $component) {
            $bucket->active[$cacheKey] = true;
            ComponentLifecycle::retain($component);
            self::retainScope($component);
        }
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
        return get_object_vars($component);
    }

    public static function reset(): void
    {
        self::releaseInstances();
        self::$components = [];
        self::$classFiles = [];
        self::$metadata = [];
        self::$propertyParameters = [];
    }

    public static function releaseInstances(): void
    {
        $instances = self::$instances;
        if ($instances !== null) {
            foreach ($instances as $bucket) {
                foreach ($bucket->instances as $component) {
                    ComponentLifecycle::forget($component);
                    $component->__pamRelease();
                }
                $bucket->calls = [];
                $bucket->callPaths = [];
            }
        }
        self::$instances = null;
        self::$seen = null;
        self::$rootScope = null;
        self::$lastCall = null;
    }

    /**
     * Builds constructor factories and prop schemas before the first frame.
     *
     * @param list<class-string<Component>> $classNames
     */
    public static function preloadMetadata(array $classNames): int
    {
        $loaded = 0;
        foreach ($classNames as $className) {
            self::autoload($className);
            self::metadata($className);
            $loaded++;
        }

        return $loaded;
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
        $inheritedStyles = $values['__pamInheritedStyles'] ?? [];
        unset(
            $values['__pamNodePath'],
            $values['__pamSlots'],
            $values['__pamComponentEvents'],
            $values['__pamInheritedStyles'],
            $values['__parentVariants'],
            $values['__pamEventContexts'],
            $values['className'],
            $values['key'],
        );

        if (
            !is_array($slots)
            || !is_array($listeners)
            || !is_array($inheritedStyles)
        ) {
            throw new RuntimeException('Compiled component context is invalid.');
        }

        $owner = $scope ?? (self::$rootScope ??= new \stdClass());
        $instances = self::$instances ??= new WeakMap();
        $bucket = $instances[$owner] ??= new ComponentInstances();
        $cacheKey = $className.'@'.$identity;
        $seen = self::$seen ??= ++self::$pass;
        if ($bucket->pass !== $seen) {
            $bucket->pass = $seen;
            $bucket->active = [];
        }
        $bucket->active[$cacheKey] = true;
        $instance = $bucket->instances[$cacheKey] ?? null;

        try {
            if (!$instance instanceof $className) {
                $instance = self::instantiate($className, $values);
            } elseif (!self::updateProps($instance, $values)) {
                $replaced = $instance;
                ComponentLifecycle::forget($replaced);
                $instance = self::instantiate($className, $values);
                $replaced->__pamRelease();
            }

            /** @var array<string, list<Renderable>> $safeSlots */
            $safeSlots = $slots;
            /** @var array<string, Closure> $safeListeners */
            $safeListeners = $listeners;
            $instance->__pamConfigure(
                $safeSlots,
                $safeListeners,
                $scope instanceof Component ? $scope : null,
                $inheritedStyles,
            );
        } catch (\Throwable $error) {
            // Keep "active" a subset of the instances (see finishRender()).
            if (!isset($bucket->instances[$cacheKey])) {
                unset($bucket->active[$cacheKey]);
            }

            throw $error;
        }
        $bucket->instances[$cacheKey] = $instance;
        self::$lastCall = [$bucket, $cacheKey, $instance, $values];

        return $instance;
    }

    /**
     * Remembers the inputs of the compiled component call that just ran.
     *
     * @param array<string, mixed> $values
     * @param array<string, mixed> $inherited
     */
    public static function rememberCall(int $plan, string $path, array $values, array $inherited): ?ComponentCall
    {
        $last = self::$lastCall;
        self::$lastCall = null;
        if ($last === null) {
            return null;
        }
        [$bucket, $cacheKey, $instance, $props] = $last;
        $names = self::$propertyParameters[$instance::class] ??= self::propertyParameters($instance);
        if ($names === null) {
            // No constructor props: updateProps() replaces the instance
            // whenever props are given.
            if ($props !== []) {
                return null;
            }
            $names = [];
        }
        $held = array_intersect_key($props, $names);
        $call = new ComponentCall($plan, $values, $inherited, $cacheKey, $instance, $held);
        if (count($bucket->calls) >= 4096) {
            $bucket->calls = [];
            $bucket->callPaths = [];
        }
        $bucket->calls[$path] = $call;
        $bucket->callPaths[$cacheKey] = $path;

        return $call;
    }

    /**
     * Constructor props that are instance properties (what updateProps()
     * compares), or null without constructor props.
     *
     * @return array<string, true>|null
     */
    private static function propertyParameters(Component $instance): ?array
    {
        $parameters = self::metadata($instance::class)['parameters'];
        if ($parameters === []) {
            return null;
        }
        $names = [];
        foreach ($parameters as $parameter) {
            if (property_exists($instance, $parameter['name'])) {
                $names[$parameter['name']] = true;
            }
        }

        return $names;
    }

    public static function call(?object $scope, string $path): ?ComponentCall
    {
        $owner = $scope ?? self::$rootScope;
        if ($owner === null || self::$instances === null) {
            return null;
        }

        return (self::$instances[$owner] ?? null)?->calls[$path] ?? null;
    }

    /**
     * Reuses the instance of a call site whose inputs did not change: same
     * effect as component() with the same values (props already equal, so
     * nothing updates) but without diffing and validating them again.
     *
     * @param array<string, Closure> $listeners
     * @param array<string, mixed> $inheritedStyles
     */
    public static function reuseCall(
        ComponentCall $call,
        ?object $scope,
        array $listeners,
        array $inheritedStyles,
    ): ?Component {
        $owner = $scope ?? self::$rootScope;
        $bucket = $owner === null || self::$instances === null ? null : (self::$instances[$owner] ?? null);
        $instance = $bucket?->instances[$call->cacheKey] ?? null;
        if ($bucket === null || $instance !== $call->instance) {
            return null;
        }
        foreach ($call->props as $name => $value) {
            if ($instance->{$name} !== $value) {
                return null;
            }
        }
        // As updateProps() does after finding every prop unchanged.
        $instance->__pamFlushChanges();
        if (!$instance->__pamReconfigure(
            ['slot' => []],
            $listeners,
            $scope instanceof Component ? $scope : null,
            $inheritedStyles,
        )) {
            return null;
        }
        $seen = self::$seen ??= ++self::$pass;
        if ($bucket->pass !== $seen) {
            $bucket->pass = $seen;
            $bucket->active = [];
        }
        $bucket->active[$call->cacheKey] = true;

        return $instance;
    }

    /**
     * @param class-string<Component> $className
     * @param array<string, mixed> $props
     */
    private static function instantiate(string $className, array $props): Component
    {
        self::autoload($className);

        $metadata = self::metadata($className);
        $arguments = [];
        $accepted = [];
        foreach ($metadata['parameters'] as $parameter) {
            $name = $parameter['name'];
            $accepted[$name] = true;
            if (
                !array_key_exists($name, $props)
                && $parameter['prop']?->required === true
            ) {
                throw new RuntimeException(
                    "Required prop {$className}::\${$name} is missing.",
                );
            }
            if (array_key_exists($name, $props)) {
                self::validateProp($className, $name, $props[$name], $parameter['prop']);
                $arguments[] = $props[$name];
            } elseif ($parameter['default']) {
                $arguments[] = $parameter['defaultValue'];
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

        return ($metadata['factory'])($arguments);
    }

    /**
     * Returns false when immutable or private constructor props require a
     * fresh component instance.
     *
     * @param array<string, mixed> $props
     */
    private static function updateProps(Component $component, array $props): bool
    {
        $metadata = self::metadata($component::class);
        if ($metadata['parameters'] === []) {
            return $props === [];
        }

        foreach ($metadata['parameters'] as $parameter) {
            $name = $parameter['name'];
            if (!array_key_exists($name, $props) || !property_exists($component, $name)) {
                continue;
            }
            self::validateProp($component::class, $name, $props[$name], $parameter['prop']);
            $previous = $component->{$name};
            if ($previous === $props[$name]) {
                continue;
            }
            if (!$parameter['mutable']) {
                return false;
            }
            $component->__pamNotifyUpdating($name, $props[$name], $previous);
            $component->{$name} = $props[$name];
            $component->__pamNotifyUpdated($name);
        }
        $component->__pamFlushChanges();

        return true;
    }

    private static function validateProp(
        string $className,
        string $name,
        mixed $value,
        ?Prop $prop,
    ): void {
        if ($prop === null) {
            return;
        }
        if ($prop->required && $value === null) {
            throw new RuntimeException("Required prop {$className}::\${$name} cannot be null.");
        }
        if ($prop->min !== null && (!is_int($value) && !is_float($value) || $value < $prop->min)) {
            throw new RuntimeException("Prop {$className}::\${$name} is below its minimum.");
        }
        if ($prop->max !== null && (!is_int($value) && !is_float($value) || $value > $prop->max)) {
            throw new RuntimeException("Prop {$className}::\${$name} exceeds its maximum.");
        }
        if ($prop->enum !== null) {
            if (!enum_exists($prop->enum) || !is_a($prop->enum, BackedEnum::class, true)) {
                throw new RuntimeException("Prop {$className}::\${$name} declares an invalid enum.");
            }
            if (!$value instanceof $prop->enum) {
                throw new RuntimeException("Prop {$className}::\${$name} must be {$prop->enum}.");
            }
        }
    }

    /**
     * @param class-string<Component> $className
     * @return array{
     *     factory: Closure(array): Component,
     *     parameters: list<array{
     *         name: string,
     *         default: bool,
     *         defaultValue: mixed,
     *         prop: Prop|null,
     *         mutable: bool
     *     }>
     * }
     */
    private static function metadata(string $className): array
    {
        if (isset(self::$metadata[$className])) {
            return self::$metadata[$className];
        }
        $reflection = new ReflectionClass($className);
        if (!$reflection->isSubclassOf(Component::class)) {
            throw new RuntimeException(
                "PAM component {$className} must extend ".Component::class.'.',
            );
        }
        $parameters = [];
        foreach ($reflection->getConstructor()?->getParameters() ?? [] as $parameter) {
            $property = $reflection->hasProperty($parameter->getName())
                ? $reflection->getProperty($parameter->getName())
                : null;
            $attribute = $parameter->getAttributes(Prop::class)[0] ?? null;
            $parameters[] = [
                'name' => $parameter->getName(),
                'default' => $parameter->isDefaultValueAvailable(),
                'defaultValue' => $parameter->isDefaultValueAvailable()
                    ? $parameter->getDefaultValue()
                    : null,
                'prop' => $attribute?->newInstance(),
                'mutable' => $property !== null
                    && $property->isPublic()
                    && !$property->isReadOnly()
                    && ($attribute === null || !$attribute->newInstance()->immutable),
            ];
        }
        $factory = static fn (array $arguments): Component => new $className(...$arguments);

        return self::$metadata[$className] = [
            'factory' => $factory,
            'parameters' => $parameters,
        ];
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
            PamPhpCompiler::materializePrebuilt($file);
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

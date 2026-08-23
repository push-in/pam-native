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
        $bucket = $instances[$owner] ?? [];
        $active = $seen[$owner] ?? [];
        foreach ($bucket as $cacheKey => $component) {
            $active[$cacheKey] = true;
            ComponentLifecycle::retain($component);
            self::retainScope($component);
        }
        $seen[$owner] = $active;
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
        $instance->__pamConfigure(
            $safeSlots,
            $safeListeners,
            $scope instanceof Component ? $scope : null,
            $inheritedStyles,
        );
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

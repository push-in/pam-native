<?php

declare(strict_types=1);

namespace Pam\Native\UI\Contract;

use BackedEnum;
use Pam\Native\Attributes\Event;
use Pam\Native\Attributes\Prop;
use Pam\Native\Attributes\Slot;
use Pam\Native\Component;
use ReflectionClass;
use ReflectionNamedType;
use ReflectionParameter;
use ReflectionUnionType;
use RuntimeException;

final class ComponentContractFactory
{
    private function __construct()
    {
    }

    /** @param class-string<Component> $component */
    public static function fromClass(string $component, string $tag): TagContract
    {
        $reflection = new ReflectionClass($component);
        $props = [];
        foreach ($reflection->getConstructor()?->getParameters() ?? [] as $parameter) {
            $attribute = $parameter->getAttributes(Prop::class)[0] ?? null;
            if ($attribute === null) {
                continue;
            }
            $definition = $attribute->newInstance();
            $props[] = new PropContract(
                name: $parameter->getName(),
                kind: self::kind($parameter),
                required: $definition->required || !$parameter->isDefaultValueAvailable(),
                bindable: !$definition->immutable,
                enum: $definition->enum ?? self::enumType($parameter),
            );
        }

        $events = [];
        foreach ($reflection->getAttributes(Event::class) as $attribute) {
            $definition = $attribute->newInstance();
            if (!class_exists($definition->payload) && !interface_exists($definition->payload)) {
                throw new RuntimeException(
                    "Component event {$component}.{$definition->name} has unknown payload {$definition->payload}.",
                );
            }
            $events[] = new EventContract($definition->name, $definition->payload);
        }

        $slots = [];
        foreach ($reflection->getAttributes(Slot::class) as $attribute) {
            $definition = $attribute->newInstance();
            if ($definition->minimum < 0 || ($definition->maximum !== null && $definition->maximum < $definition->minimum)) {
                throw new RuntimeException("Component slot {$component}.{$definition->name} has invalid cardinality.");
            }
            $slots[] = new SlotContract(
                $definition->name,
                $definition->minimum,
                $definition->maximum,
            );
        }

        return new TagContract($tag, $props, $events, $slots);
    }

    private static function kind(ReflectionParameter $parameter): ValueKind
    {
        $type = $parameter->getType();
        if ($type instanceof ReflectionUnionType) {
            $nonNull = [];
            foreach ($type->getTypes() as $candidate) {
                if (!$candidate instanceof ReflectionNamedType) {
                    return ValueKind::Any;
                }
                if ($candidate->getName() !== 'null') {
                    $nonNull[] = $candidate;
                }
            }
            return count($nonNull) === 1 ? self::namedKind($nonNull[0]) : ValueKind::Any;
        }
        return $type instanceof ReflectionNamedType ? self::namedKind($type) : ValueKind::Any;
    }

    /** @return class-string<BackedEnum>|null */
    private static function enumType(ReflectionParameter $parameter): ?string
    {
        $type = $parameter->getType();
        if (!$type instanceof ReflectionNamedType) {
            return null;
        }
        $name = $type->getName();

        return is_a($name, BackedEnum::class, true) ? $name : null;
    }

    private static function namedKind(ReflectionNamedType $type): ValueKind
    {
        return match ($type->getName()) {
            'string' => ValueKind::String,
            'int' => ValueKind::Integer,
            'float' => ValueKind::Float,
            'bool' => ValueKind::Boolean,
            'array', 'iterable' => ValueKind::Array,
            'mixed' => ValueKind::Any,
            default => is_a($type->getName(), BackedEnum::class, true)
                ? ValueKind::Enum
                : ValueKind::Object,
        };
    }
}

<?php

declare(strict_types=1);

namespace Pam\Native\UI\Contract;

use BackedEnum;
use Pam\Native\Renderable;
use RuntimeException;

final class ContractValidator
{
    private function __construct()
    {
    }

    /**
     * @param array<string, mixed> $props
     * @param array<string, list<Renderable>> $slots
     * @param array<string, mixed> $events
     */
    public static function validate(
        TagContract $contract,
        array $props,
        array $slots,
        array $events,
    ): void {
        $known = [];
        foreach ($contract->props as $prop) {
            $known[$prop->name] = true;
            if (!array_key_exists($prop->name, $props)) {
                if ($prop->required) {
                    throw new RuntimeException(
                        "Required prop {$contract->name}.{$prop->name} is missing.",
                    );
                }
                continue;
            }
            if (!self::matches($props[$prop->name], $prop)) {
                throw new RuntimeException(
                    "Prop {$contract->name}.{$prop->name} violates its typed contract.",
                );
            }
        }
        $unknown = array_diff_key($props, $known);
        if ($unknown !== []) {
            throw new RuntimeException(
                "Unknown props for {$contract->name}: ".implode(', ', array_keys($unknown)).'.',
            );
        }

        $knownEvents = [];
        foreach ($contract->events as $event) {
            $knownEvents[$event->name] = true;
        }
        $unknownEvents = array_diff_key($events, $knownEvents);
        if ($unknownEvents !== []) {
            throw new RuntimeException(
                "Unknown events for {$contract->name}: ".implode(', ', array_keys($unknownEvents)).'.',
            );
        }

        foreach ($contract->slots as $slot) {
            $count = count($slots[$slot->name] ?? []);
            if ($count < $slot->minimum || ($slot->maximum !== null && $count > $slot->maximum)) {
                throw new RuntimeException(
                    "Slot {$contract->name}.{$slot->name} violates its cardinality contract.",
                );
            }
        }
    }

    private static function matches(mixed $value, PropContract $prop): bool
    {
        if ($value === null && !$prop->required) {
            return true;
        }
        if ($prop->enum !== null) {
            return $value instanceof BackedEnum && $value::class === $prop->enum;
        }

        return match ($prop->kind) {
            ValueKind::String => is_string($value),
            ValueKind::Integer => is_int($value),
            ValueKind::Float => is_float($value) || is_int($value),
            ValueKind::Boolean => is_bool($value),
            ValueKind::Array => is_array($value),
            ValueKind::Object => is_object($value),
            ValueKind::Enum => $value instanceof BackedEnum,
            ValueKind::Renderable => $value instanceof Renderable,
            ValueKind::Any => true,
        };
    }
}

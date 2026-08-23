<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Attributes\Action;
use Pam\Native\Attributes\Expose;
use Pam\Native\Component;
use Pam\Native\TemplateRegistry;
use ReflectionClass;
use RuntimeException;

final class TemplateContractValidator
{
    private function __construct()
    {
    }

    /** @param class-string<Component> $component */
    public static function validate(string $component, CompiledTemplateNode $tree): void
    {
        self::node(new ReflectionClass($component), $tree);
    }

    /** @param ReflectionClass<Component> $component */
    private static function node(ReflectionClass $component, CompiledTemplateNode $node): void
    {
        foreach ($node->attributes as $name => $value) {
            if (
                !str_starts_with($name, '@')
                && !str_starts_with($name, 'on:')
            ) {
                continue;
            }
            if (!is_string($value) || preg_match('/^[A-Za-z_][A-Za-z0-9_]*$/D', $value) !== 1) {
                continue;
            }
            if (!$component->hasMethod($value)) {
                throw self::error($node, "action {$value} does not exist on {$component->getName()}");
            }
            $method = $component->getMethod($value);
            if (
                !$method->isPublic()
                || ($method->getAttributes(Action::class) === []
                    && $method->getAttributes(Expose::class) === [])
            ) {
                throw self::error(
                    $node,
                    "action {$value} must be public and declare #[Action] (#[Expose] remains compatible)",
                );
            }
        }

        $contract = TemplateRegistry::tagContract($node->name);
        if ($contract !== null) {
            $knownProps = [];
            foreach ($contract->props as $prop) {
                $knownProps[$prop->name] = true;
                if ($prop->required && !isset($node->attributes[$prop->name]) && !isset($node->attributes[':'.$prop->name])) {
                    throw self::error($node, "required prop {$node->name}.{$prop->name} is missing");
                }
            }
            $knownEvents = [];
            foreach ($contract->events as $event) {
                $knownEvents[$event->name] = true;
            }
            foreach (array_keys($node->attributes) as $attribute) {
                if (str_starts_with($attribute, '@')) {
                    $event = substr($attribute, 1);
                    if (!isset($knownEvents[$event])) {
                        throw self::error($node, "unknown event {$node->name}.{$event}");
                    }
                    continue;
                }
                $prop = ltrim($attribute, ':');
                if (
                    isset($knownProps[$prop])
                    || in_array($prop, ['class', 'key', 'recipe'], true)
                    || str_starts_with($prop, 'p-')
                    || str_starts_with($prop, 'variant:')
                ) {
                    continue;
                }
                throw self::error($node, "unknown prop {$node->name}.{$prop}");
            }
        }

        foreach ($node->children as $child) {
            self::node($component, $child);
        }
    }

    private static function error(CompiledTemplateNode $node, string $message): RuntimeException
    {
        return new RuntimeException(
            "PAM2401 {$message} at {$node->source}:{$node->line}:{$node->column}.",
        );
    }
}

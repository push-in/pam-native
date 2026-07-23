<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

final class CompiledTemplateNode
{
    /** @var list<self> */
    public array $children = [];

    /** @param array<string, string|bool> $attributes */
    public function __construct(
        public readonly int $kind,
        public readonly string $name,
        public readonly array $attributes,
        public readonly string $source,
        public readonly int $line,
        public readonly int $column,
        public readonly string $value = '',
    ) {
    }

    /**
     * @return array{
     *     kind: int,
     *     name: string,
     *     attributes: array<string, string|bool>,
     *     children: list<array<string, mixed>>,
     *     source: string,
     *     line: int,
     *     column: int,
     *     value: string
     * }
     */
    public function toArray(): array
    {
        return [
            'kind' => $this->kind,
            'name' => $this->name,
            'attributes' => $this->attributes,
            'children' => array_map(
                static fn (self $child): array => $child->toArray(),
                $this->children,
            ),
            'source' => $this->source,
            'line' => $this->line,
            'column' => $this->column,
            'value' => $this->value,
        ];
    }

    public static function hydrate(mixed $raw): ?self
    {
        if (!is_array($raw)) {
            return null;
        }
        $kind = $raw['kind'] ?? null;
        $name = $raw['name'] ?? null;
        $attributes = $raw['attributes'] ?? null;
        $children = $raw['children'] ?? null;
        $source = $raw['source'] ?? null;
        $line = $raw['line'] ?? null;
        $column = $raw['column'] ?? null;
        $value = $raw['value'] ?? '';
        if (
            !is_int($kind)
            || !is_string($name)
            || !is_array($attributes)
            || !is_array($children)
            || !is_string($source)
            || !is_int($line)
            || !is_int($column)
            || !is_string($value)
        ) {
            return null;
        }
        $safeAttributes = [];

        foreach ($attributes as $key => $attribute) {
            if (!is_string($key) || (!is_string($attribute) && !is_bool($attribute))) {
                return null;
            }

            $safeAttributes[$key] = $attribute;
        }
        $node = new self($kind, $name, $safeAttributes, $source, $line, $column, $value);

        foreach ($children as $child) {
            $hydrated = self::hydrate($child);

            if ($hydrated === null) {
                return null;
            }

            $node->children[] = $hydrated;
        }

        return $node;
    }
}

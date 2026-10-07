<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use function is_array;
use function is_bool;
use function is_int;
use function is_string;

final class CompiledTemplateNode
{
    /** @var list<self> */
    public array $children = [];

    /**
     * @internal Render plan derived once from this node (directives
     * resolved, attribute classification); owned by TemplateRenderer.
     *
     * @var array<string, mixed>|null
     */
    public ?array $pamPlan = null;

    /**
     * @internal Element plans keyed by "n" (native tag) and "f" (registered
     * component tag); owned by TemplateRenderer.
     *
     * @var array<string, array<string, mixed>>
     */
    public array $pamTagPlans = [];

    /** @var array<string, array{0: int, 1: int, 2: ?string, 3: string, 4: mixed, 5: array<string, mixed>, 6: string}> last style entry per tag variant */
    public array $pamLastEntries = [];

    /** @var array<string, array<string, array{0: int, 1: int, 2: ?string, 3: string, 4: mixed, 5: array<string, mixed>, 6: string}>> recent style entries per tag variant, by class and inherited styles */
    public array $pamRecentEntries = [];

    /** Native element rendered through fastTag() last time (see TemplateRenderer::fastNode()). */
    public bool $pamFast = false;

    /** Whether this template mentions `props` anywhere (null: not scanned yet). */
    public ?bool $pamUsesProps = null;

    /** @internal True when any attribute, directive or text of the tree mentions "props". */
    public function mentionsProps(): bool
    {
        if (str_contains($this->name, 'props') || str_contains($this->value, 'props')) {
            return true;
        }
        foreach ($this->attributes as $name => $value) {
            if (str_contains((string) $name, 'props') || (is_string($value) && str_contains($value, 'props'))) {
                return true;
            }
        }
        foreach ($this->children as $child) {
            if ($child->mentionsProps()) {
                return true;
            }
        }

        return false;
    }

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

    /**
     * @param string|null $source replaces every node's source (relocated
     *        bundle caches store project-relative paths)
     */
    public static function hydrate(mixed $raw, ?string $source = null): ?self
    {
        if (!is_array($raw)) {
            return null;
        }
        $kind = $raw['kind'] ?? null;
        $name = $raw['name'] ?? null;
        $attributes = $raw['attributes'] ?? null;
        $children = $raw['children'] ?? null;
        $nodeSource = $raw['source'] ?? null;
        $line = $raw['line'] ?? null;
        $column = $raw['column'] ?? null;
        $value = $raw['value'] ?? '';
        if (
            !is_int($kind)
            || !is_string($name)
            || !is_array($attributes)
            || !is_array($children)
            || !is_string($nodeSource)
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
        $node = new self($kind, $name, $safeAttributes, $source ?? $nodeSource, $line, $column, $value);

        foreach ($children as $child) {
            $hydrated = self::hydrate($child, $source);

            if ($hydrated === null) {
                return null;
            }

            $node->children[] = $hydrated;
        }

        return $node;
    }
}

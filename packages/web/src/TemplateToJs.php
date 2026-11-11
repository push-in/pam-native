<?php

declare(strict_types=1);

namespace Pam\Native\Web;

use Pam\Native\Internal\CompiledTemplateNode;
use RuntimeException;

/**
 * Compiles a component template to `function (c, s)` that builds its DOM
 * once; every binding is an effect on the signals it reads, so a change
 * updates only the attributes and texts that depend on it.
 */
final class TemplateToJs
{
    /** Tags the runtime renders (resources/runtime.js BOX). */
    public const TAGS = [
        'Screen', 'View', 'Column', 'Row', 'Stack', 'SafeAreaView', 'ScrollView', 'Pressable', 'Button', 'Link',
        'Text', 'Image', 'Spacer', 'Divider', 'ActivityIndicator', 'Input', 'TextInput', 'Template', 'Fragment', 'Card',
    ];

    private int $counter = 0;
    /** @var list<string> */
    private array $code = [];
    /** @var list<string> */
    public array $warnings = [];

    /**
     * @param array<string, true> $properties
     * @param array<string, true> $methods
     * @param array<string, string> $components tag => JS class
     */
    public function __construct(
        private readonly array $properties,
        private readonly array $methods,
        private readonly array $components,
        private readonly string $scopeClass,
        private readonly string $file,
    ) {
    }

    public function compile(CompiledTemplateNode $root): string
    {
        $elements = array_values(array_filter($root->children, static fn (CompiledTemplateNode $n): bool => $n->kind === 1));
        $this->code = [];
        if (count($elements) === 1 && $this->directive($elements[0], 'p-for') === null && $this->directive($elements[0], 'p-if') === null) {
            $result = $this->element($elements[0], null, []);
        } else {
            $result = $this->fresh();
            $this->code[] = "var {$result} = H.n('View', '');";
            $this->children($root->children, $result, []);
        }

        return "function (c, s) {\n    var H = PAM.H;\n    ".implode("\n    ", $this->code)."\n    return {$result};\n}";
    }

    /**
     * @param list<CompiledTemplateNode> $children
     * @param array<string, true> $locals
     */
    private function children(array $children, string $parent, array $locals): void
    {
        $count = count($children);
        for ($index = 0; $index < $count; $index++) {
            $child = $children[$index];
            if ($child->kind !== 1) {
                $this->text($child, $parent, $locals);
                continue;
            }
            if ($this->directive($child, 'p-if') !== null) {
                $branches = [[$child, $this->directive($child, 'p-if')]];
                while ($index + 1 < $count) {
                    $next = $children[$index + 1];
                    if ($next->kind !== 1) {
                        break;
                    }
                    $elseIf = $this->directive($next, 'p-else-if');
                    if ($elseIf !== null) {
                        $branches[] = [$next, $elseIf];
                        $index++;
                        continue;
                    }
                    if (array_key_exists('p-else', $next->attributes) || array_key_exists('v-else', $next->attributes)) {
                        $branches[] = [$next, null];
                        $index++;
                    }
                    break;
                }
                $this->ifChain($branches, $parent, $locals);
                continue;
            }
            if ($this->directive($child, 'p-for') !== null) {
                $this->loop($child, $parent, $locals);
                continue;
            }
            $this->element($child, $parent, $locals);
        }
    }

    /**
     * @param list<array{0: CompiledTemplateNode, 1: ?string}> $branches
     * @param array<string, true> $locals
     */
    private function ifChain(array $branches, string $parent, array $locals): void
    {
        $out = [];
        foreach ($branches as [$node, $condition]) {
            $test = $condition === null ? 'null' : 'function () { return '.$this->expression($condition, $locals, $node).'; }';
            $out[] = "[{$test}, ".$this->block($node, $locals, ['p-if', 'p-else-if', 'p-else', 'v-if', 'v-else-if', 'v-else']).']';
        }
        $this->code[] = "H.i({$parent}, [".implode(', ', $out).']);';
    }

    /** @param array<string, true> $locals */
    private function loop(CompiledTemplateNode $node, string $parent, array $locals): void
    {
        $header = (string) $this->directive($node, 'p-for');
        if (preg_match('/^\s*\$?([A-Za-z_][A-Za-z0-9_]*)\s+in\s+(.+)\s*$/Ds', $header, $match) !== 1) {
            throw $this->error($node, 'p-for must use "$item in $items" syntax');
        }
        $item = $match[1];
        $indexAttribute = $node->attributes['p-index'] ?? null;
        $index = is_string($indexAttribute) ? ltrim($indexAttribute, '$') : $item.'Index';
        $source = $this->expression($match[2], $locals, $node);
        $inner = $locals + [$item => true, $index => true];
        $keyExpression = $node->attributes['p-key'] ?? $node->attributes[':key'] ?? null;
        $key = is_string($keyExpression)
            ? 'function (s) { return '.$this->expression($keyExpression, $inner, $node).'; }'
            : 'null';
        $build = $this->block($node, $inner, ['p-for', 'p-key', ':key', 'key', 'p-index'], 's');
        $this->code[] = "H.f({$parent}, function () { return {$source}; }, {$key}, {$build}, s, ".Js::string($item).', '.Js::string($index).');';
    }

    /**
     * A function building one element (with its own effects).
     *
     * @param array<string, true> $locals
     * @param list<string> $drop
     */
    private function block(CompiledTemplateNode $node, array $locals, array $drop, string $parameter = ''): string
    {
        $outer = $this->code;
        $this->code = [];
        $stripped = new CompiledTemplateNode(
            kind: $node->kind,
            name: $node->name,
            attributes: array_diff_key($node->attributes, array_flip($drop)),
            source: $node->source,
            line: $node->line,
            column: $node->column,
            value: $node->value,
        );
        $stripped->children = $node->children;
        if ($this->directive($stripped, 'p-if') !== null) {
            $wrapper = $this->fresh();
            $this->code[] = "var {$wrapper} = H.n('Fragment', '');";
            $this->children([$stripped], $wrapper, $locals);
            $result = $wrapper;
        } else {
            $result = $this->element($stripped, null, $locals);
        }
        $body = implode("\n        ", $this->code);
        $this->code = $outer;

        return "function ({$parameter}) {\n        {$body}\n        return {$result};\n    }";
    }

    /** @param array<string, true> $locals */
    private function element(CompiledTemplateNode $node, ?string $parent, array $locals): string
    {
        if (isset($this->components[$node->name])) {
            return $this->component($node, $parent, $locals);
        }
        $tag = $node->name;
        if (!in_array($tag, self::TAGS, true)) {
            $this->warnings[] = "{$this->file}:{$node->line}: <{$tag}> renders as a plain View in web builds.";
            $tag = 'View';
        }
        $var = $this->fresh();
        $static = $node->attributes['class'] ?? '';
        $classes = trim((is_string($static) ? $static : '').' '.$this->scopeClass);
        $this->code[] = "var {$var} = H.n(".Js::string($tag).', '.Js::string($classes).');';

        foreach ($node->attributes as $name => $value) {
            $name = (string) $name;
            if ($name === 'class' || in_array($name, ['p-key', ':key', 'key', 'ref', 'p-ref'], true)) {
                continue;
            }
            if ($name === ':class' || $name === 'p-bind:class') {
                $this->code[] = "H.c({$var}, function () { return ".$this->expression((string) $value, $locals, $node).'; });';
                continue;
            }
            if ($name === 'p-show' || $name === 'v-show') {
                $this->code[] = "H.b({$var}, 'display', function () { return PAM.b(".$this->expression((string) $value, $locals, $node).") ? null : 'none'; });";
                continue;
            }
            if ($name === 'p-model' || $name === 'v-model') {
                $target = $this->expression((string) $value, $locals, $node);
                $this->code[] = "H.b({$var}, 'value', function () { return {$target}; });";
                $this->code[] = "H.e({$var}, 'changeText', function (v) { {$target} = v; });";
                continue;
            }
            if (str_starts_with($name, '@') || str_starts_with($name, 'on:') || str_starts_with($name, 'p-on:')) {
                $event = substr($name, str_starts_with($name, '@') ? 1 : strpos($name, ':') + 1);
                $handler = ExpressionToJs::handler((string) $value, $locals, $this->properties, $this->methods, $this->where($node));
                $this->code[] = "H.e({$var}, ".Js::string($event).", {$handler});";
                continue;
            }
            if (str_starts_with($name, ':') || str_starts_with($name, 'p-bind:')) {
                $prop = substr($name, str_starts_with($name, ':') ? 1 : 7);
                $this->code[] = "H.b({$var}, ".Js::string($prop).', function () { return '.$this->expression((string) $value, $locals, $node).'; });';
                continue;
            }
            if (str_starts_with($name, 'p-') || str_starts_with($name, 'v-')) {
                throw $this->error($node, "{$name} is not supported in web builds yet");
            }
            $this->code[] = "H.a({$var}, ".Js::string($name).', '.Js::value($value === true ? true : $value).');';
        }

        $this->children($node->children, $var, $locals);
        if ($parent !== null) {
            $this->code[] = "{$parent}.appendChild({$var});";
        }

        return $var;
    }

    /** @param array<string, true> $locals */
    private function component(CompiledTemplateNode $node, ?string $parent, array $locals): string
    {
        $attributes = [];
        foreach ($node->attributes as $name => $value) {
            $name = (string) $name;
            if (str_starts_with($name, ':')) {
                $attributes[] = Js::string(substr($name, 1)).': PAM.bound(function () { return '.$this->expression((string) $value, $locals, $node).'; })';
            } elseif (!str_starts_with($name, '@') && $name !== 'class') {
                $attributes[] = Js::string($name).': '.Js::value($value);
            }
        }
        $holder = $parent;
        if ($holder === null) {
            $holder = $this->fresh();
            $this->code[] = "var {$holder} = H.n('Fragment', '');";
        }
        $this->code[] = "H.m({$holder}, {$this->components[$node->name]}, {".implode(', ', $attributes).'});';

        return $holder;
    }

    /** @param array<string, true> $locals */
    private function text(CompiledTemplateNode $node, string $parent, array $locals): void
    {
        $value = $node->value;
        $offset = 0;
        while (preg_match('/\{\{(.*?)\}\}/s', $value, $match, PREG_OFFSET_CAPTURE, $offset) === 1) {
            $start = $match[0][1];
            if ($start > $offset) {
                $this->code[] = "H.t({$parent}, ".Js::string(substr($value, $offset, $start - $offset)).');';
            }
            $this->code[] = "H.x({$parent}, function () { return ".$this->expression(trim($match[1][0]), $locals, $node).'; });';
            $offset = $start + strlen($match[0][0]);
        }
        if ($offset < strlen($value)) {
            $this->code[] = "H.t({$parent}, ".Js::string(substr($value, $offset)).');';
        }
    }

    /** @param array<string, true> $locals */
    private function expression(string $expression, array $locals, CompiledTemplateNode $node): string
    {
        return ExpressionToJs::compile($expression, $locals, $this->properties, $this->methods, $this->where($node));
    }

    private function directive(CompiledTemplateNode $node, string $name): ?string
    {
        $value = $node->attributes[$name] ?? $node->attributes['v-'.substr($name, 2)] ?? null;

        return is_string($value) ? $value : null;
    }

    private function fresh(): string
    {
        return 'e'.++$this->counter;
    }

    private function where(CompiledTemplateNode $node): string
    {
        return "{$this->file}:{$node->line}";
    }

    private function error(CompiledTemplateNode $node, string $message): RuntimeException
    {
        return new RuntimeException($this->where($node).': '.$message.'.');
    }
}

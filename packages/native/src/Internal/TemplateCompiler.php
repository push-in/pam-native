<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\LanguageVersion;
use RuntimeException;

final class TemplateCompiler
{
    private const MAX_TEMPLATE_BYTES = 2_097_152;
    private const MAX_NODES = 100_000;
    private const MAX_DEPTH = 256;

    /** @var array<string, array{mtime: int, tree: CompiledTemplateNode}> */
    private static array $memory = [];

    private function __construct()
    {
    }

    public static function load(string $source, ?string $cachePath): CompiledTemplateNode
    {
        $mtime = (int) filemtime($source);
        $cached = self::$memory[$source] ?? null;

        if ($cached !== null && $cached['mtime'] === $mtime) {
            return $cached['tree'];
        }

        $compiledPath = $cachePath === null
            ? null
            : rtrim($cachePath, DIRECTORY_SEPARATOR).'/'.hash('xxh3', $source).'.php';
        $tree = null;

        if (
            $compiledPath !== null
            && is_file($compiledPath)
            && (int) filemtime($compiledPath) >= $mtime
        ) {
            $tree = CompiledTemplateNode::hydrate(require $compiledPath);
        }

        if ($tree === null) {
            $contents = file_get_contents($source);

            if ($contents === false) {
                throw new RuntimeException("Cannot read template {$source}.");
            }

            $tree = self::compile($contents, $source);

            if ($compiledPath !== null) {
                self::store($compiledPath, $tree);
            }
        }

        self::$memory[$source] = ['mtime' => $mtime, 'tree' => $tree];

        return $tree;
    }

    public static function compile(
        string $source,
        string $name = '<memory>',
        LanguageVersion $language = LanguageVersion::Language1,
    ): CompiledTemplateNode
    {
        if (strlen($source) > self::MAX_TEMPLATE_BYTES) {
            throw new RuntimeException("Template {$name} exceeds two megabytes.");
        }

        $root = new CompiledTemplateNode(
            kind: 1,
            name: '#root',
            attributes: [],
            source: $name,
            line: 1,
            column: 1,
        );
        $stack = [$root];
        $offset = 0;
        $nodes = 0;

        foreach (self::tokens($source) as [$token, $position]) {
            self::appendText(
                $stack,
                substr($source, $offset, $position - $offset),
                $nodes,
            );
            $offset = $position + strlen($token);

            if (str_starts_with($token, '<!--')) {
                continue;
            }

            if (str_starts_with($token, '</')) {
                $closing = [];
                if (
                    preg_match('/^<\\/([A-Za-z][A-Za-z0-9_.-]*)\\s*>$/', $token, $closing) !== 1
                ) {
                    throw new RuntimeException("Invalid closing tag in {$name}.");
                }
                $current = self::lastNode($stack);

                if (count($stack) === 1 || $current->name !== $closing[1]) {
                    throw new RuntimeException("Unexpected closing tag {$closing[1]} in {$name}.");
                }

                array_pop($stack);
                continue;
            }

            $opening = [];
            if (
                preg_match(
                    '/^<([A-Za-z][A-Za-z0-9_.-]*)(.*?)\\s*(\\/?)>$/s',
                    $token,
                    $opening,
                ) !== 1
            ) {
                throw new RuntimeException("Invalid tag in {$name}.");
            }

            if (++$nodes > self::MAX_NODES || count($stack) > self::MAX_DEPTH) {
                throw new RuntimeException("Template {$name} exceeds structural limits.");
            }

            $location = self::location($source, $position);
            $node = new CompiledTemplateNode(
                kind: 1,
                name: $opening[1],
                attributes: self::attributes($opening[2], $name),
                source: $name,
                line: $location['line'],
                column: $location['column'],
            );
            $parent = self::lastNode($stack);

            $parent->children[] = $node;

            if ($opening[3] !== '/') {
                $stack[] = $node;
            }
        }

        self::appendText($stack, substr($source, $offset), $nodes);

        if (count($stack) !== 1) {
            $unclosed = self::lastNode($stack);
            throw new RuntimeException(
                "Template contains an unclosed tag {$unclosed->name} in {$name}.",
            );
        }

        self::validateContracts($root, $language);

        return $root;
    }

    private static function validateContracts(
        CompiledTemplateNode $node,
        LanguageVersion $language,
    ): void
    {
        $forAttribute = self::directiveAttribute($node, 'p-for', 'v-for');
        if ($forAttribute !== null) {
            $directive = $node->attributes[$forAttribute];
            if (
                !is_string($directive)
                || preg_match(
                    '/^\s*\$?([A-Za-z_][A-Za-z0-9_]*)\s+in\s+(.+)\s*$/D',
                    $directive,
                ) !== 1
            ) {
                throw new RuntimeException(
                    "{$forAttribute} must use \"\$item in \$items\" syntax at {$node->source}:{$node->line}:{$node->column}.",
                );
            }
        }

        if ($node->name === 'GestureDetector') {
            [$minimum, $maximum] = self::renderedChildBounds($node->children);
            if ($minimum !== 1 || $maximum !== 1) {
                throw new RuntimeException(
                    "GestureDetector must resolve to exactly one child at {$node->source}:{$node->line}:{$node->column}.",
                );
            }
        }

        if ($language === LanguageVersion::Language2) {
            self::validateLanguage2($node);
        }

        foreach ($node->children as $child) {
            self::validateContracts($child, $language);
        }
    }

    private static function validateLanguage2(CompiledTemplateNode $node): void
    {
        if (
            in_array($node->name, ['VirtualizedList', 'VirtualGrid'], true)
            && self::containsLoop($node)
            && !self::loopHasStableKey($node)
        ) {
            throw new RuntimeException(
                "PAM2201 virtualized loops require p-key at {$node->source}:{$node->line}:{$node->column}.",
            );
        }

        if ($node->name === 'Image') {
            $decorative = ($node->attributes['decorative'] ?? false) === true
                || ($node->attributes['decorative'] ?? '') === 'true';
            if (!$decorative && !isset($node->attributes['accessibilityLabel'])) {
                throw new RuntimeException(
                    "PAM2301 Image requires accessibilityLabel or decorative=\"true\" at {$node->source}:{$node->line}:{$node->column}.",
                );
            }
        }

        if ($node->name === 'Match') {
            $defaults = 0;
            foreach ($node->children as $child) {
                if ($child->kind !== 1 || !in_array($child->name, ['Case', 'Default'], true)) {
                    throw new RuntimeException('PAM2202 Match accepts only Case and Default children.');
                }
                $defaults += $child->name === 'Default' ? 1 : 0;
            }
            if ($defaults > 1) {
                throw new RuntimeException('PAM2203 Match accepts at most one Default child.');
            }
        }

        if ($node->name === 'Await') {
            $allowed = ['Pending', 'Content', 'Empty', 'Error', 'Offline', 'Stale', 'Default'];
            $seen = [];
            foreach ($node->children as $child) {
                if ($child->kind !== 1 || !in_array($child->name, $allowed, true)) {
                    throw new RuntimeException('PAM2204 Await contains an unsupported state branch.');
                }
                if (isset($seen[$child->name])) {
                    throw new RuntimeException("PAM2205 Await state {$child->name} is declared twice.");
                }
                $seen[$child->name] = true;
            }
        }
    }

    private static function containsLoop(CompiledTemplateNode $node): bool
    {
        foreach ($node->children as $child) {
            if (self::hasDirective($child, 'p-for', 'v-for') || self::containsLoop($child)) {
                return true;
            }
        }

        return false;
    }

    private static function loopHasStableKey(CompiledTemplateNode $node): bool
    {
        foreach ($node->children as $child) {
            if (
                self::hasDirective($child, 'p-for', 'v-for')
                && isset($child->attributes['p-key'])
            ) {
                return true;
            }
            if (self::loopHasStableKey($child)) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param list<CompiledTemplateNode> $children
     * @return array{int, int}
     */
    private static function renderedChildBounds(array $children): array
    {
        $minimum = 0;
        $maximum = 0;
        $count = count($children);

        for ($index = 0; $index < $count; $index++) {
            $child = $children[$index];
            if (self::hasDirective($child, 'p-for', 'v-for')) {
                return [0, PHP_INT_MAX];
            }
            if (self::hasDirective($child, 'p-if', 'v-if')) {
                $hasElse = false;
                while ($index + 1 < $count) {
                    $next = $children[$index + 1];
                    if (self::hasDirective($next, 'p-else-if', 'v-else-if')) {
                        $index++;
                        continue;
                    }
                    if (self::hasDirective($next, 'p-else', 'v-else')) {
                        $index++;
                        $hasElse = true;
                    }
                    break;
                }
                $minimum += $hasElse ? 1 : 0;
                $maximum++;
                continue;
            }
            if (
                self::hasDirective($child, 'p-else-if', 'v-else-if')
                || self::hasDirective($child, 'p-else', 'v-else')
            ) {
                return [0, PHP_INT_MAX];
            }
            $minimum++;
            $maximum++;
        }

        return [$minimum, $maximum];
    }

    private static function hasDirective(
        CompiledTemplateNode $node,
        string $canonical,
        string $legacy,
    ): bool {
        return self::directiveAttribute($node, $canonical, $legacy) !== null;
    }

    private static function directiveAttribute(
        CompiledTemplateNode $node,
        string $canonical,
        string $legacy,
    ): ?string {
        if (array_key_exists($canonical, $node->attributes)) {
            return $canonical;
        }

        return array_key_exists($legacy, $node->attributes) ? $legacy : null;
    }

    /**
     * @param list<CompiledTemplateNode> $stack
     */
    private static function appendText(array $stack, string $text, int &$nodes): void
    {
        if (trim($text) === '') {
            return;
        }

        if (++$nodes > self::MAX_NODES) {
            throw new RuntimeException('Template exceeds its node limit.');
        }
        $parent = self::lastNode($stack);

        $parent->children[] = new CompiledTemplateNode(
            kind: 2,
            name: '#text',
            attributes: [],
            source: $parent->source,
            line: $parent->line,
            column: $parent->column,
            value: trim($text),
        );
    }

    /** @return array<string, string|bool> */
    private static function attributes(string $source, string $name): array
    {
        $matches = [];
        preg_match_all(
            "/([#:@A-Za-z_][#:@A-Za-z0-9_.-]*)(?:\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'))?/",
            $source,
            $matches,
            PREG_SET_ORDER | PREG_OFFSET_CAPTURE | PREG_UNMATCHED_AS_NULL,
        );
        $attributes = [];
        $offset = 0;

        foreach ($matches as $match) {
            $whole = self::capture($match, 0);
            $attribute = self::capture($match, 1);

            if ($whole === null || $attribute === null) {
                throw new RuntimeException("Invalid attributes in {$name}.");
            }
            [$raw, $position] = $whole;

            if (trim(substr($source, $offset, $position - $offset)) !== '') {
                throw new RuntimeException("Invalid attributes in {$name}.");
            }
            $key = $attribute[0];

            if (array_key_exists($key, $attributes)) {
                throw new RuntimeException("Duplicate template attribute {$key} in {$name}.");
            }

            $double = self::capture($match, 2);
            $single = self::capture($match, 3);
            $attributes[$key] = match (true) {
                $double !== null && $double[1] >= 0 => $double[0],
                $single !== null && $single[1] >= 0 => $single[0],
                default => true,
            };
            $offset = $position + strlen($raw);
        }

        if (trim(substr($source, $offset)) !== '') {
            throw new RuntimeException("Invalid attributes in {$name}.");
        }

        return $attributes;
    }

    /**
     * @param array<array-key, mixed> $match
     * @return array{string, int}|null
     */
    private static function capture(array $match, int $index): ?array
    {
        $capture = $match[$index] ?? null;

        if (
            !is_array($capture)
            || !array_key_exists(0, $capture)
            || !array_key_exists(1, $capture)
            || !is_string($capture[0])
            || !is_int($capture[1])
        ) {
            return null;
        }

        return [$capture[0], $capture[1]];
    }

    /** @return list<array{string, int}> */
    private static function tokens(string $source): array
    {
        $tokens = [];
        $length = strlen($source);

        for ($start = 0; $start < $length; $start++) {
            if ($source[$start] !== '<') {
                continue;
            }

            if (substr($source, $start, 4) === '<!--') {
                $end = strpos($source, '-->', $start + 4);
                if ($end === false) {
                    continue;
                }
                $end += 3;
                $tokens[] = [substr($source, $start, $end - $start), $start];
                $start = $end - 1;

                continue;
            }

            $nameOffset = $start + 1;
            if (($source[$nameOffset] ?? '') === '/') {
                $nameOffset++;
            }
            if (
                $nameOffset >= $length
                || !self::isAsciiLetter($source[$nameOffset])
            ) {
                continue;
            }

            $quote = null;
            $captured = false;
            for ($end = $nameOffset + 1; $end < $length; $end++) {
                $character = $source[$end];
                if ($quote !== null) {
                    if ($character === $quote) {
                        $quote = null;
                    }

                    continue;
                }
                if ($character === '"' || $character === "'") {
                    $quote = $character;

                    continue;
                }
                if ($character !== '>') {
                    continue;
                }

                $tokens[] = [
                    substr($source, $start, $end - $start + 1),
                    $start,
                ];
                $start = $end;
                $captured = true;
                break;
            }
            if (!$captured && $quote !== null) {
                $fallbackEnd = strpos($source, '>', $nameOffset + 1);
                if ($fallbackEnd !== false) {
                    $tokens[] = [
                        substr($source, $start, $fallbackEnd - $start + 1),
                        $start,
                    ];
                    $start = $fallbackEnd;
                }
            }
        }

        return $tokens;
    }

    private static function isAsciiLetter(string $character): bool
    {
        return ($character >= 'A' && $character <= 'Z')
            || ($character >= 'a' && $character <= 'z');
    }

    /** @param list<CompiledTemplateNode> $stack */
    private static function lastNode(array $stack): CompiledTemplateNode
    {
        $node = end($stack);

        if (!$node instanceof CompiledTemplateNode) {
            throw new RuntimeException('Template parser stack is empty.');
        }

        return $node;
    }

    private static function store(string $path, CompiledTemplateNode $tree): void
    {
        $directory = dirname($path);

        if (!is_dir($directory) && !mkdir($directory, 0o755, true) && !is_dir($directory)) {
            throw new RuntimeException("Cannot create template cache {$directory}.");
        }

        $temporary = tempnam($directory, 'pam-view-');

        if ($temporary === false) {
            throw new RuntimeException('Cannot create a temporary template cache file.');
        }

        $bytes = file_put_contents(
            $temporary,
            "<?php\n\nreturn ".var_export($tree->toArray(), true).";\n",
            LOCK_EX,
        );

        if ($bytes === false || !rename($temporary, $path)) {
            @unlink($temporary);
            throw new RuntimeException("Cannot write template cache {$path}.");
        }
    }

    /** @return array{line: int, column: int} */
    private static function location(string $source, int $offset): array
    {
        $before = substr($source, 0, max(0, $offset));
        $line = substr_count($before, "\n") + 1;
        $lastBreak = strrpos($before, "\n");
        $column = $lastBreak === false ? strlen($before) + 1 : strlen($before) - $lastBreak;

        return ['line' => $line, 'column' => $column];
    }
}

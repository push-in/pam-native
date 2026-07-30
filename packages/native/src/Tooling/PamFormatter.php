<?php

declare(strict_types=1);

namespace Pam\Native\Tooling;

use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\TemplateCompiler;
use RuntimeException;

/**
 * Deterministic formatter for single-file .pam.php components.
 *
 * PHP remains untouched so projects may keep using Pint/PHP-CS-Fixer. The PAM
 * template and scoped CSS blocks are normalized here because generic HTML
 * formatters do not understand PAM expressions or directives.
 */
final class PamFormatter
{
    private const MAX_INLINE_COLUMNS = 100;
    private const COMMENT_TAG = 'Pam.__FormatterComment';

    private function __construct()
    {
    }

    public static function format(string $source, string $name = '<memory>'): string
    {
        if (str_contains($source, '<'.self::COMMENT_TAG)) {
            throw new RuntimeException(
                "PAM component {$name} uses the formatter's reserved internal tag.",
            );
        }

        [$php, $template, $style, $hasStyle] = self::split($source, $name);
        $protectedTemplate = preg_replace_callback(
            '/<!--[\s\S]*?-->/',
            static fn (array $match): string =>
                '<'.self::COMMENT_TAG.' value="'
                    .base64_encode($match[0]).'" />',
            $template,
        );
        if (!is_string($protectedTemplate)) {
            throw new RuntimeException(
                "Cannot preserve template comments in {$name}.",
            );
        }
        $tree = TemplateCompiler::compile($protectedTemplate, $name);
        $lines = ['<template>'];
        foreach ($tree->children as $child) {
            array_push($lines, ...self::node($child, 1));
        }
        $lines[] = '</template>';

        $formatted = rtrim($php)."\n?>\n\n".implode("\n", $lines);
        if ($hasStyle) {
            $formatted .= "\n\n<style scoped>\n"
                .self::style($style)
                ."\n</style>";
        }

        return $formatted."\n";
    }

    public static function formatFile(string $path, bool $check = false): bool
    {
        $source = file_get_contents($path);
        if (!is_string($source)) {
            throw new RuntimeException("Cannot read PAM component {$path}.");
        }
        $formatted = self::format($source, $path);
        if ($formatted === $source) {
            return false;
        }
        if ($check) {
            return true;
        }
        if (file_put_contents($path, $formatted, LOCK_EX) === false) {
            throw new RuntimeException("Cannot write PAM component {$path}.");
        }

        return true;
    }

    /** @return array{string, string, string, bool} */
    private static function split(string $source, string $name): array
    {
        $tokens = token_get_all($source);
        $offset = 0;
        $closeOffset = null;
        $closeLength = 0;
        foreach ($tokens as $token) {
            $text = is_array($token) ? $token[1] : $token;
            if (is_array($token) && $token[0] === T_CLOSE_TAG) {
                $closeOffset = $offset;
                $closeLength = strlen($text);
                break;
            }
            $offset += strlen($text);
        }
        if ($closeOffset === null) {
            throw new RuntimeException(
                "PAM component {$name} must close PHP before <template>.",
            );
        }
        $php = substr($source, 0, $closeOffset);
        $markup = substr($source, $closeOffset + $closeLength);
        $match = [];
        if (preg_match(
            '/\A\s*<template(?:\s[^>]*)?>([\s\S]*?)<\/template>'
                .'\s*(?:<style(?:\s+scoped)?\s*>([\s\S]*?)<\/style>)?\s*\z/D',
            $markup,
            $match,
        ) !== 1) {
            throw new RuntimeException(
                "PAM component {$name} must contain one template and optional scoped style.",
            );
        }

        return [
            $php,
            (string) $match[1],
            (string) ($match[2] ?? ''),
            array_key_exists(2, $match),
        ];
    }

    /** @return list<string> */
    private static function node(CompiledTemplateNode $node, int $depth): array
    {
        $indent = str_repeat('    ', $depth);
        if ($node->kind === 2) {
            return [$indent.trim($node->value)];
        }
        if ($node->name === self::COMMENT_TAG) {
            $encoded = $node->attributes['value'] ?? null;
            $comment = is_string($encoded)
                ? base64_decode($encoded, true)
                : false;
            if (!is_string($comment) || !str_starts_with($comment, '<!--')) {
                throw new RuntimeException(
                    'PAM formatter could not restore a template comment.',
                );
            }

            return [$indent.trim($comment)];
        }

        $attributes = self::attributes($node->attributes);
        $opening = '<'.$node->name;
        foreach ($attributes as $name => $value) {
            $opening .= $value === true
                ? ' '.$name
                : ' '.$name.'='.self::quoted($value);
        }
        if ($node->children === []) {
            return strlen($indent.$opening.' />') <= self::MAX_INLINE_COLUMNS
                ? [$indent.$opening.' />']
                : self::multilineOpening($node->name, $attributes, $depth, true);
        }

        if (
            count($node->children) === 1
            && $node->children[0]->kind === 2
            && strlen(
                $indent.$opening.'>'
                    .trim($node->children[0]->value)
                    .'</'.$node->name.'>',
            ) <= self::MAX_INLINE_COLUMNS
        ) {
            return [
                $indent.$opening.'>'
                    .trim($node->children[0]->value)
                    .'</'.$node->name.'>',
            ];
        }

        $lines = strlen($indent.$opening.'>') <= self::MAX_INLINE_COLUMNS
            ? [$indent.$opening.'>']
            : self::multilineOpening($node->name, $attributes, $depth, false);
        foreach ($node->children as $child) {
            array_push($lines, ...self::node($child, $depth + 1));
        }
        $lines[] = $indent.'</'.$node->name.'>';

        return $lines;
    }

    /**
     * @param array<string, string|bool> $attributes
     * @return list<string>
     */
    private static function multilineOpening(
        string $tag,
        array $attributes,
        int $depth,
        bool $selfClosing,
    ): array {
        $indent = str_repeat('    ', $depth);
        $attributeIndent = str_repeat('    ', $depth + 1);
        $lines = [$indent.'<'.$tag];
        foreach ($attributes as $name => $value) {
            $lines[] = $attributeIndent.($value === true
                ? $name
                : $name.'='.self::quoted($value));
        }
        $lines[] = $indent.($selfClosing ? '/>' : '>');

        return $lines;
    }

    /**
     * @param array<string, string|bool> $attributes
     * @return array<string, string|bool>
     */
    private static function attributes(array $attributes): array
    {
        $output = [];
        foreach ($attributes as $name => $value) {
            $name = match ($name) {
                'v-if' => 'p-if',
                'v-else-if' => 'p-else-if',
                'v-else' => 'p-else',
                'v-for' => 'p-for',
                default => $name,
            };
            if (array_key_exists($name, $output) && $output[$name] !== $value) {
                throw new RuntimeException(
                    "Cannot format conflicting PAM directive {$name}.",
                );
            }
            $output[$name] = $value;
        }

        return $output;
    }

    private static function quoted(string $value): string
    {
        if (!str_contains($value, '"')) {
            return '"'.$value.'"';
        }
        if (!str_contains($value, "'")) {
            return "'".$value."'";
        }

        throw new RuntimeException(
            'PAM formatter cannot safely quote an attribute containing both quote styles.',
        );
    }

    private static function style(string $source): string
    {
        $lines = [];
        $clean = preg_replace('/\/\*[\s\S]*?\*\//', '', trim($source));
        if (!is_string($clean)) {
            throw new RuntimeException('Cannot format scoped PAM styles.');
        }
        preg_match_all(
            '/([^{}]+)\{([^{}]*)\}/',
            $clean,
            $blocks,
            PREG_SET_ORDER | PREG_OFFSET_CAPTURE,
        );
        $matchedBytes = 0;
        foreach ($blocks as $block) {
            $offset = $block[0][1];
            if (trim(substr($clean, $matchedBytes, $offset - $matchedBytes)) !== '') {
                throw new RuntimeException(
                    'PAM formatter cannot format nested or invalid scoped CSS.',
                );
            }
            $matchedBytes = $offset + strlen($block[0][0]);
            if ($lines !== []) {
                $lines[] = '';
            }
            $lines[] = '    '.trim($block[1][0]).' {';
            foreach (explode(';', $block[2][0]) as $declaration) {
                $declaration = trim($declaration);
                if ($declaration !== '') {
                    $lines[] = '        '.$declaration.';';
                }
            }
            $lines[] = '    }';
        }
        if (trim(substr($clean, $matchedBytes)) !== '') {
            throw new RuntimeException(
                'PAM formatter cannot format invalid scoped CSS.',
            );
        }

        return implode("\n", $lines);
    }
}

<?php

declare(strict_types=1);

namespace Pam\Native\Tooling;

use Pam\Native\Internal\CompiledTemplateNode;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\LanguageVersion;
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

        [$php, $template, $style, $hasStyle, $language] = self::split($source, $name);
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
        $tree = TemplateCompiler::compile($protectedTemplate, $name, $language);
        $lines = [$language === LanguageVersion::Language2
            ? '<template language="2">'
            : '<template>'];
        foreach ($tree->children as $child) {
            array_push($lines, ...self::node($child, 1));
        }
        $lines[] = '</template>';

        $formatted = rtrim($php)."\n?>\n\n".implode("\n", $lines);
        if ($hasStyle && trim($style) !== '') {
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

    /** @return array{string, string, string, bool, LanguageVersion} */
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
            '/\A\s*<template(?:\s+([^>]*))?>([\s\S]*?)<\/template>'
                .'\s*(?:<style(?:\s+scoped)?\s*>([\s\S]*?)<\/style>)?\s*\z/D',
            $markup,
            $match,
        ) !== 1) {
            throw new RuntimeException(
                "PAM component {$name} must contain one template and optional scoped style.",
            );
        }

        $attributes = trim((string) ($match[1] ?? ''));
        $language = LanguageVersion::Language1;
        if (preg_match('/(?:language|version)\s*=\s*(["\'])2\1/D', $attributes) === 1) {
            $language = LanguageVersion::Language2;
        } elseif ($attributes !== '') {
            throw new RuntimeException(
                "PAM component {$name} has unsupported template attributes; use language=\"2\".",
            );
        }

        return [
            $php,
            (string) $match[2],
            (string) ($match[3] ?? ''),
            array_key_exists(3, $match),
            $language,
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
        $imports = [];
        $clean = preg_replace_callback(
            '/@import\s+(?:url\(\s*)?(["\'])([^"\']+)\1\s*\)?\s*;/i',
            static function (array $match) use (&$imports): string {
                $imports[] = '@import "'.trim((string) $match[2]).'";';

                return '';
            },
            $clean,
        );
        if (!is_string($clean)) {
            throw new RuntimeException('Cannot format scoped PAM style imports.');
        }
        foreach ($imports as $import) {
            $lines[] = '    '.$import;
        }
        $depth = 1;
        $buffer = '';
        $quote = null;
        $length = strlen($clean);
        for ($index = 0; $index < $length; $index++) {
            $character = $clean[$index];
            if ($quote !== null) {
                $buffer .= $character;
                if ($character === $quote && ($index === 0 || $clean[$index - 1] !== '\\')) {
                    $quote = null;
                }
                continue;
            }
            if ($character === '"' || $character === "'") {
                $quote = $character;
                $buffer .= $character;
                continue;
            }
            if ($character === '{') {
                $header = trim($buffer);
                if ($header === '') {
                    throw new RuntimeException('PAM formatter found an empty CSS selector.');
                }
                if ($lines !== [] && end($lines) !== '') {
                    $lines[] = '';
                }
                $lines[] = str_repeat('    ', $depth).$header.' {';
                $depth++;
                $buffer = '';
                continue;
            }
            if ($character === ';') {
                $declaration = trim($buffer);
                if ($declaration !== '') {
                    $lines[] = str_repeat('    ', $depth).$declaration.';';
                }
                $buffer = '';
                continue;
            }
            if ($character === '}') {
                $declaration = trim($buffer);
                if ($declaration !== '') {
                    throw new RuntimeException('PAM formatter requires semicolons in CSS declarations.');
                }
                if ($depth <= 1) {
                    throw new RuntimeException('PAM formatter found an unexpected CSS closing brace.');
                }
                $depth--;
                $lines[] = str_repeat('    ', $depth).'}';
                $buffer = '';
                continue;
            }
            $buffer .= $character;
        }
        if ($quote !== null || $depth !== 1 || trim($buffer) !== '') {
            throw new RuntimeException('PAM formatter cannot format invalid scoped CSS.');
        }

        return implode("\n", $lines);
    }
}

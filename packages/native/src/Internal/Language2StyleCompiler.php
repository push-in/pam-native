<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use RuntimeException;

/** Extracts Language 2 constructs into a deterministic, runtime-free style IR. */
final class Language2StyleCompiler
{
    private function __construct()
    {
    }

    /**
     * @return array{
     *   source: string,
     *   tokens: array<string, string>,
     *   states: array<string, array<string, array<string, string|int|bool>>>,
     *   recipes: array<string, array{base: array<string, string|int|bool>, variants: array<string, array<string, array<string, string|int|bool>>>}>,
     *   queries: list<array<string, mixed>>,
     *   keyframes: array<string, list<array{offset: float, styles: array<string, string|int|bool>}>>
     * }
     */
    public static function extract(string $source, string $name): array
    {
        $tokens = [];
        $states = [];
        $recipes = [];
        $queries = [];
        $keyframes = [];
        $legacy = [];

        foreach (self::blocks($source, $name) as [$header, $body]) {
            if ($header === '@tokens') {
                foreach (self::rawDeclarations($body, $name) as $token => $value) {
                    $safe = ltrim($token, '-');
                    if (preg_match('/^[a-z][a-z0-9.-]*$/D', $safe) !== 1) {
                        throw new RuntimeException("Invalid design token {$token} in {$name}.");
                    }
                    $tokens['--'.str_replace('.', '-', $safe)] = trim($value);
                }
                continue;
            }

            if (preg_match('/^(.+):(pressed|focused|disabled|selected|checked|hovered)$/D', $header, $match) === 1) {
                $selector = trim($match[1]);
                self::assertSelector($selector, $name);
                $states[$selector][$match[2]] = ScopedStyleCompiler::compileDeclarations(
                    $body,
                    $tokens,
                    $name,
                );
                continue;
            }

            if (preg_match('/^@recipe\s+([A-Za-z][A-Za-z0-9_.-]*)$/D', $header, $match) === 1) {
                $recipes[$match[1]] = self::recipe($body, $tokens, $name);
                continue;
            }

            if (preg_match('/^@(media|container)\s+(.+)$/D', $header, $match) === 1) {
                self::assertQuery($match[1], trim($match[2]), $name);
                $queries[] = [
                    'kind' => $match[1] === 'media' ? 1 : 2,
                    'condition' => trim($match[2]),
                    'styles' => ScopedStyleCompiler::compile($body, $name.' '.$header),
                ];
                continue;
            }

            if (preg_match('/^@keyframes\s+([A-Za-z][A-Za-z0-9_-]*)$/D', $header, $match) === 1) {
                $keyframes[$match[1]] = self::keyframes($body, $tokens, $name);
                continue;
            }

            $legacy[] = $header.' {'.$body.'}';
        }

        if ($tokens !== []) {
            $declarations = [];
            foreach ($tokens as $token => $value) {
                $declarations[] = $token.': '.$value.';';
            }
            array_unshift($legacy, ':root {'.implode(' ', $declarations).'}');
        }

        return [
            'source' => implode("\n", $legacy),
            'tokens' => $tokens,
            'states' => $states,
            'recipes' => $recipes,
            'queries' => $queries,
            'keyframes' => $keyframes,
        ];
    }

    /** @return list<array{string, string}> */
    private static function blocks(string $source, string $name): array
    {
        $clean = preg_replace('/\/\*[\s\S]*?\*\//', '', $source);
        if (!is_string($clean)) {
            throw new RuntimeException("Cannot parse styles in {$name}.");
        }
        $blocks = [];
        $length = strlen($clean);
        $cursor = 0;
        while ($cursor < $length) {
            while ($cursor < $length && ctype_space($clean[$cursor])) {
                $cursor++;
            }
            if ($cursor >= $length) {
                break;
            }
            $open = strpos($clean, '{', $cursor);
            if ($open === false) {
                throw new RuntimeException("Invalid CSS block in {$name}.");
            }
            $header = trim(substr($clean, $cursor, $open - $cursor));
            if ($header === '') {
                throw new RuntimeException("Empty CSS selector in {$name}.");
            }
            $depth = 1;
            $quote = null;
            $index = $open + 1;
            for (; $index < $length && $depth > 0; $index++) {
                $character = $clean[$index];
                if ($quote !== null) {
                    if ($character === $quote && $clean[$index - 1] !== '\\') {
                        $quote = null;
                    }
                    continue;
                }
                if ($character === '"' || $character === "'") {
                    $quote = $character;
                } elseif ($character === '{') {
                    $depth++;
                } elseif ($character === '}') {
                    $depth--;
                }
            }
            if ($depth !== 0) {
                throw new RuntimeException("Unclosed CSS block {$header} in {$name}.");
            }
            $blocks[] = [$header, substr($clean, $open + 1, $index - $open - 2)];
            $cursor = $index;
        }

        return $blocks;
    }

    /** @return array<string, string> */
    private static function rawDeclarations(string $body, string $name): array
    {
        $output = [];
        foreach (explode(';', $body) as $declaration) {
            if (trim($declaration) === '') {
                continue;
            }
            $parts = explode(':', $declaration, 2);
            if (count($parts) !== 2 || trim($parts[0]) === '' || trim($parts[1]) === '') {
                throw new RuntimeException("Invalid declaration in {$name}.");
            }
            $output[trim($parts[0])] = trim($parts[1]);
        }
        return $output;
    }

    /**
     * @param array<string, string> $tokens
     * @return array{
     *   base: array<string, string|int|bool>,
     *   variants: array<string, array<string, array<string, string|int|bool>>>
     * }
     */
    private static function recipe(string $body, array $tokens, string $name): array
    {
        $recipe = ['base' => [], 'variants' => []];
        foreach (self::blocks($body, $name.' recipe') as [$header, $declarations]) {
            if ($header === 'base') {
                $recipe['base'] = ScopedStyleCompiler::compileDeclarations($declarations, $tokens, $name);
                continue;
            }
            if (preg_match('/^variant\s+([A-Za-z][A-Za-z0-9_-]*)=([A-Za-z0-9_.-]+)$/D', $header, $match) !== 1) {
                throw new RuntimeException("Invalid recipe branch {$header} in {$name}.");
            }
            $recipe['variants'][$match[1]][$match[2]] =
                ScopedStyleCompiler::compileDeclarations($declarations, $tokens, $name);
        }
        return $recipe;
    }

    /**
     * @param array<string, string> $tokens
     * @return list<array{offset: float, styles: array<string, string|int|bool>}>
     */
    private static function keyframes(string $body, array $tokens, string $name): array
    {
        /** @var list<array{offset: float, styles: array<string, string|int|bool>}> $frames */
        $frames = [];
        foreach (self::blocks($body, $name.' keyframes') as [$header, $declarations]) {
            $offset = match ($header) {
                'from' => 0.0,
                'to' => 1.0,
                default => str_ends_with($header, '%') ? (float) substr($header, 0, -1) / 100 : -1,
            };
            if ($offset < 0 || $offset > 1) {
                throw new RuntimeException("Invalid keyframe offset {$header} in {$name}.");
            }
            $styles = ScopedStyleCompiler::compileDeclarations($declarations, $tokens, $name);
            $unsupported = array_diff(array_keys($styles), [
                'opacity', 'translationX', 'translationY', 'scaleX', 'scaleY', 'rotation',
            ]);
            if ($unsupported !== []) {
                throw new RuntimeException(
                    "Keyframes in {$name} may animate only compositor properties.",
                );
            }
            $frames[] = ['offset' => $offset, 'styles' => $styles];
        }
        usort(
            $frames,
            static fn (array $left, array $right): int =>
                $left['offset'] <=> $right['offset'],
        );
        return $frames;
    }

    private static function assertSelector(string $selector, string $name): void
    {
        if (
            preg_match('/^(?:\.[A-Za-z_][A-Za-z0-9_-]*|[A-Za-z][A-Za-z0-9_.-]*)$/D', $selector) !== 1
        ) {
            throw new RuntimeException("Unsupported state selector {$selector} in {$name}.");
        }
    }

    private static function assertQuery(string $kind, string $condition, string $name): void
    {
        $media = '/^\((?:min|max)-(?:width|height):\s*[0-9]+(?:\.[0-9]+)?(?:dp|px)\)$/D';
        $container = '/^(?:[A-Za-z][A-Za-z0-9_-]*\s+)?\((?:min|max)-(?:width|height):\s*[0-9]+(?:\.[0-9]+)?(?:dp|px)\)$/D';
        if (preg_match($kind === 'media' ? $media : $container, $condition) !== 1) {
            throw new RuntimeException("Unsupported @{$kind} condition {$condition} in {$name}.");
        }
    }
}

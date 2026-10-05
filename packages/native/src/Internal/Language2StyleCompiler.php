<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Style\StyleQueryKind;
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

        $blocks = self::blocks($source, $name);
        // :root custom properties are visible inside @media/@container,
        // state blocks and @supports bodies.
        $rootVariables = [];
        foreach ($blocks as [$header, $body]) {
            if ($header === ':root') {
                foreach (self::rawDeclarations($body, $name) as $property => $value) {
                    if (str_starts_with($property, '--')) {
                        $rootVariables[$property] = trim($value);
                    }
                }
            }
        }
        $expanded = [];
        foreach ($blocks as [$header, $body]) {
            if (!str_starts_with($header, '@') && str_contains($header, ',')
                && preg_match('/::?(?:pressed|focus|focused|focus-visible|disabled|selected|checked|hover|hovered|active|loading|error|placeholder|selection)\b/', $header) === 1) {
                foreach (self::splitSelectors($header) as $part) {
                    $expanded[] = [$part, $body];
                }
                continue;
            }
            $expanded[] = [$header, $body];
        }

        foreach ($expanded as [$header, $body]) {
            $variables = [...$rootVariables, ...$tokens];
            if (preg_match('/^(.+)::(placeholder|selection)$/D', $header, $match) === 1) {
                $declarations = self::rawDeclarations($body, $name);
                foreach (array_keys($declarations) as $property) {
                    if ($property !== 'color') {
                        throw new RuntimeException(
                            "::{$match[2]} in {$name} supports only color; {$property} is unsupported.",
                        );
                    }
                }
                $legacy[] = trim($match[1]).' { -pam-'.$match[2].'-color: '.($declarations['color'] ?? 'transparent').'; }';
                continue;
            }
            if (preg_match('/^@supports\s+(.+)$/isD', $header, $match) === 1) {
                if (self::supports(trim($match[1]), $variables, $name)) {
                    $nested = self::extract(self::rootSource($rootVariables).$body, $name.' @supports');
                    $tokens = [...$tokens, ...$nested['tokens']];
                    $states = array_replace_recursive($states, $nested['states']);
                    $recipes = array_replace_recursive($recipes, $nested['recipes']);
                    $queries = [...$queries, ...$nested['queries']];
                    $keyframes = [...$keyframes, ...$nested['keyframes']];
                    $legacy[] = preg_replace('/^:root \{[^}]*\}\s*/', '', $nested['source']) ?? $nested['source'];
                }
                continue;
            }
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

            if (preg_match('/^(.+):(pressed|focus|focused|focus-visible|disabled|selected|checked|hover|hovered|active|loading|error)$/D', $header, $match) === 1) {
                $selector = trim($match[1]);
                self::assertSelector($selector, $name);
                $state = match ($match[2]) {
                    'focused' => 'focus', 'hovered' => 'hover', default => $match[2],
                };
                try {
                    $states[$selector][$state] = ScopedStyleCompiler::compileDeclarations(
                        $body,
                        $variables,
                        $name,
                    );
                } catch (CssDiagnostic $diagnostic) {
                    $diagnostic->selector ??= $header;
                    throw $diagnostic;
                }
                continue;
            }

            if (preg_match('/^@recipe\s+([A-Za-z][A-Za-z0-9_.-]*)$/D', $header, $match) === 1) {
                $recipes[$match[1]] = self::recipe($body, $variables, $name);
                continue;
            }

            if (preg_match('/^@(media|container)\s+(.+)$/D', $header, $match) === 1) {
                $queryAst = StyleQueryCompiler::compile(trim($match[2]), $name);
                $queries[] = [
                    'kind' => $match[1] === 'media'
                        ? StyleQueryKind::Media->value
                        : StyleQueryKind::Container->value,
                    'condition' => trim($match[2]),
                    'ast' => $queryAst,
                    'styles' => ScopedStyleCompiler::compile(
                        self::rootSource($variables).$body,
                        $name.' '.$header,
                    ),
                ];
                continue;
            }

            if (preg_match('/^@keyframes\s+([A-Za-z][A-Za-z0-9_-]*)$/D', $header, $match) === 1) {
                $keyframes[$match[1]] = self::keyframes($body, $variables, $name);
                continue;
            }

            if (preg_match('/^@layer\s+([A-Za-z][A-Za-z0-9_.-]*)$/D', $header) === 1) {
                $layer = self::extract($body, $name.' '.$header);
                $tokens = [...$tokens, ...$layer['tokens']];
                $states = array_replace_recursive($states, $layer['states']);
                $recipes = array_replace_recursive($recipes, $layer['recipes']);
                $queries = [...$queries, ...$layer['queries']];
                $keyframes = [...$keyframes, ...$layer['keyframes']];
                $legacy[] = $layer['source'];
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

    /** @param array<string, string> $variables */
    private static function rootSource(array $variables): string
    {
        if ($variables === []) {
            return '';
        }
        $declarations = [];
        foreach ($variables as $property => $value) {
            $declarations[] = $property.': '.$value.';';
        }

        return ':root {'.implode(' ', $declarations)."}\n";
    }

    /** @return list<string> */
    private static function splitSelectors(string $header): array
    {
        $parts = [];
        $depth = 0;
        $start = 0;
        $length = strlen($header);
        for ($index = 0; $index < $length; $index++) {
            if ($header[$index] === '(') {
                $depth++;
            } elseif ($header[$index] === ')') {
                $depth--;
            } elseif ($header[$index] === ',' && $depth === 0) {
                $parts[] = trim(substr($header, $start, $index - $start));
                $start = $index + 1;
            }
        }
        $parts[] = trim(substr($header, $start));

        return array_values(array_filter($parts, static fn (string $part): bool => $part !== ''));
    }

    /**
     * Evaluates `@supports` at compile time against the native compiler:
     * a declaration is supported when PAM can compile it.
     *
     * @param array<string, string> $variables
     */
    private static function supports(string $condition, array $variables, string $name): bool
    {
        $condition = trim($condition);
        foreach (['or', 'and'] as $operator) {
            $parts = self::splitKeyword($condition, $operator);
            if (count($parts) > 1) {
                $results = array_map(
                    static fn (string $part): bool => self::supports($part, $variables, $name),
                    $parts,
                );
                return $operator === 'or'
                    ? in_array(true, $results, true)
                    : !in_array(false, $results, true);
            }
        }
        if (preg_match('/^not\s+(.+)$/isD', $condition, $match) === 1) {
            return !self::supports($match[1], $variables, $name);
        }
        if (preg_match('/^selector\((.+)\)$/isD', $condition, $match) === 1) {
            try {
                StyleSelectorCompiler::compile(trim($match[1]), $name);
                return true;
            } catch (RuntimeException|\InvalidArgumentException) {
                return false;
            }
        }
        if (str_starts_with($condition, '(') && str_ends_with($condition, ')')) {
            $inner = trim(substr($condition, 1, -1));
            if (str_starts_with($inner, '(') || preg_match('/^(?:not|selector)\b/i', $inner) === 1) {
                return self::supports($inner, $variables, $name);
            }
            if (preg_match('/^(-{0,2}[A-Za-z][A-Za-z0-9-]*)\s*:\s*(.+)$/sD', $inner, $match) === 1) {
                if (str_starts_with($match[1], '--')) {
                    return true;
                }
                try {
                    ScopedStyleCompiler::compileDeclarations($match[1].': '.$match[2], $variables, $name);
                    return true;
                } catch (RuntimeException|\InvalidArgumentException) {
                    return false;
                }
            }
        }

        throw new RuntimeException("Invalid @supports condition {$condition} in {$name}.");
    }

    /** @return list<string> */
    private static function splitKeyword(string $condition, string $keyword): array
    {
        $parts = [];
        $depth = 0;
        $start = 0;
        $length = strlen($condition);
        $needle = ' '.$keyword.' ';
        for ($index = 0; $index < $length; $index++) {
            if ($condition[$index] === '(') {
                $depth++;
            } elseif ($condition[$index] === ')') {
                $depth--;
            } elseif ($depth === 0 && strcasecmp(substr($condition, $index, strlen($needle)), $needle) === 0) {
                $parts[] = trim(substr($condition, $start, $index - $start));
                $index += strlen($needle) - 1;
                $start = $index + 1;
            }
        }
        $parts[] = trim(substr($condition, $start));

        return array_values(array_filter($parts, static fn (string $part): bool => $part !== ''));
    }

    private static function assertSelector(string $selector, string $name): void
    {
        StyleSelectorCompiler::compile($selector, $name);
    }

}

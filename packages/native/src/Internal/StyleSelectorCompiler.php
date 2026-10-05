<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use RuntimeException;

/** Compiles the native-safe selector grammar to a deterministic matcher IR. */
final class StyleSelectorCompiler
{
    private function __construct()
    {
    }

    /**
     * @return array{source:string, compounds:list<array{combinator:string,tag:?string,id:?string,classes:list<string>,attributes:list<array{name:string,operator:string,value:?string}>,pseudos:list<string>}>,specificity:list<int>}
     */
    public static function compile(string $selector, string $name): array
    {
        $selector = trim($selector);
        if ($selector === '' || str_contains($selector, '::')) {
            throw new RuntimeException(
                "Unsupported native selector {$selector} in {$name}; pseudo-elements other than ::placeholder and ::selection have no native node.",
            );
        }
        if (preg_match('/(?:^|[^\\\\])[+~]/', preg_replace('/\([^()]*\)|\[[^\]]*\]/', '', $selector) ?? $selector) === 1) {
            throw new RuntimeException(
                "Sibling combinators (+, ~) in {$selector} are unsupported natively in {$name}; add a class to the sibling instead.",
            );
        }
        $parts = self::tokens($selector);
        if (!is_array($parts) || $parts === []) {
            throw new RuntimeException("Invalid native selector {$selector} in {$name}.");
        }
        $compounds = [];
        $combinator = 'self';
        foreach ($parts as $part) {
            if ($part === '>') {
                $combinator = 'child';
                continue;
            }
            $compound = self::compound(trim($part), $selector, $name);
            $compound['combinator'] = $compounds === [] ? 'self' : $combinator;
            $compounds[] = $compound;
            $combinator = 'descendant';
        }
        $ids = 0;
        $classes = 0;
        $tags = 0;
        foreach ($compounds as $compound) {
            $ids += $compound['id'] === null ? 0 : 1;
            $classes += count($compound['classes']) + count($compound['attributes']) + count($compound['pseudos'])
                + count($compound['nots'] ?? []);
            $tags += $compound['tag'] === null || $compound['tag'] === '*' ? 0 : 1;
        }

        return ['source' => $selector, 'compounds' => $compounds, 'specificity' => [$ids, $classes, $tags]];
    }

    /**
     * Splits a selector into compounds and `>` combinators, keeping
     * parenthesised and bracketed fragments intact.
     *
     * @return list<string>
     */
    private static function tokens(string $selector): array
    {
        $parts = [];
        $current = '';
        $depth = 0;
        $length = strlen($selector);
        for ($index = 0; $index < $length; $index++) {
            $character = $selector[$index];
            if ($character === '(' || $character === '[') {
                $depth++;
            } elseif ($character === ')' || $character === ']') {
                $depth--;
            }
            if ($depth === 0 && ($character === '>' || ctype_space($character))) {
                if ($current !== '') {
                    $parts[] = $current;
                    $current = '';
                }
                if ($character === '>') {
                    $parts[] = '>';
                }
                continue;
            }
            $current .= $character;
        }
        if ($current !== '') {
            $parts[] = $current;
        }

        return $parts;
    }

    /**
     * Expands `:is()` / `:where()` into a plain selector list so the matcher
     * stays a flat compound chain.
     *
     * @return list<string>
     */
    public static function expand(string $selectorList): array
    {
        $output = [];
        foreach (self::splitList($selectorList) as $selector) {
            if (preg_match('/:(is|where|matches)\(/', $selector, $match, PREG_OFFSET_CAPTURE) !== 1) {
                $output[] = $selector;
                continue;
            }
            $start = $match[0][1];
            $open = $start + strlen($match[0][0]) - 1;
            $depth = 0;
            $close = -1;
            for ($index = $open; $index < strlen($selector); $index++) {
                if ($selector[$index] === '(') {
                    $depth++;
                } elseif ($selector[$index] === ')') {
                    $depth--;
                    if ($depth === 0) {
                        $close = $index;
                        break;
                    }
                }
            }
            if ($close < 0) {
                throw new RuntimeException("Unclosed :{$match[1][0]}() in selector {$selector}.");
            }
            $inner = substr($selector, $open + 1, $close - $open - 1);
            foreach (self::splitList($inner) as $alternative) {
                array_push($output, ...self::expand(
                    substr($selector, 0, $start).$alternative.substr($selector, $close + 1),
                ));
            }
        }

        return $output;
    }

    /** @return list<string> */
    private static function splitList(string $value): array
    {
        $parts = [];
        $depth = 0;
        $start = 0;
        $length = strlen($value);
        for ($index = 0; $index < $length; $index++) {
            if ($value[$index] === '(' || $value[$index] === '[') {
                $depth++;
            } elseif ($value[$index] === ')' || $value[$index] === ']') {
                $depth--;
            } elseif ($value[$index] === ',' && $depth === 0) {
                $parts[] = trim(substr($value, $start, $index - $start));
                $start = $index + 1;
            }
        }
        $parts[] = trim(substr($value, $start));

        return array_values(array_filter($parts, static fn (string $part): bool => $part !== ''));
    }

    /** @return array{tag:?string,id:?string,classes:list<string>,attributes:list<array{name:string,operator:string,value:?string}>,pseudos:list<string>,nots?:list<array<string, mixed>>} */
    private static function compound(string $source, string $selector, string $name): array
    {
        $tag = null;
        $id = null;
        $classes = [];
        $attributes = [];
        $pseudos = [];
        $nots = [];
        $offset = 0;
        if (preg_match('/^(\*|[A-Za-z][A-Za-z0-9_-]*)/', $source, $match) === 1) {
            $tag = $match[1];
            $offset = strlen($match[0]);
        }
        while ($offset < strlen($source)) {
            $tail = substr($source, $offset);
            if (preg_match('/^#([A-Za-z_][A-Za-z0-9_-]*)/', $tail, $match) === 1) {
                if ($id !== null) {
                    throw new RuntimeException("Selector {$selector} has multiple ids in {$name}.");
                }
                $id = $match[1];
            } elseif (preg_match('/^\.([A-Za-z_][A-Za-z0-9_-]*)/', $tail, $match) === 1) {
                $classes[] = $match[1];
            } elseif (preg_match('/^\[([A-Za-z_:][A-Za-z0-9_:.-]*)(?:\s*(=|~=|\|=|\^=|\$=|\*=)\s*(?:"([^"]*)"|\'([^\']*)\'|([^\]\s]+)))?\s*\]/', $tail, $match) === 1) {
                $attributes[] = [
                    'name' => $match[1],
                    'operator' => $match[2] ?? '',
                    'value' => ($match[3] ?? '') !== '' ? $match[3] : ((($match[4] ?? '') !== '') ? $match[4] : (($match[5] ?? '') !== '' ? $match[5] : null)),
                ];
            } elseif (preg_match('/^:not\(((?:[^()]|\([^()]*\))+)\)/', $tail, $match) === 1) {
                foreach (self::splitList($match[1]) as $negated) {
                    if (preg_match('/[\s>]/', $negated) === 1) {
                        throw new RuntimeException("Native :not() accepts simple selectors only in {$selector} ({$name}).");
                    }
                    $nots[] = self::compound($negated, $selector, $name);
                }
            } elseif (preg_match('/^:(first-child|last-child|only-child|nth-child|nth-last-child|first-of-type|last-of-type|nth-of-type|only-of-type)\b/', $tail, $match) === 1) {
                throw new RuntimeException(
                    "Structural pseudo-class :{$match[1]} in {$selector} is unsupported natively in {$name}; bind a class from the loop (e.g. :class=\"\$loop->first ? 'first' : ''\").",
                );
            } elseif (preg_match('/^:(pressed|hover|focus|focus-visible|disabled|checked|selected|active|loading|error|empty)(?![A-Za-z-])/', $tail, $match) === 1) {
                $pseudos[] = $match[1];
            } else {
                throw new RuntimeException("Unsupported native selector fragment {$tail} in {$selector} ({$name}).");
            }
            $offset += strlen($match[0]);
        }
        if ($tag === null && $id === null && $classes === [] && $attributes === [] && $pseudos === [] && $nots === []) {
            throw new RuntimeException("Invalid native selector {$selector} in {$name}.");
        }

        return $nots === []
            ? compact('tag', 'id', 'classes', 'attributes', 'pseudos')
            : compact('tag', 'id', 'classes', 'attributes', 'pseudos', 'nots');
    }
}

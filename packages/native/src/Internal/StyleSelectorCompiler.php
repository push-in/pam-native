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
            throw new RuntimeException("Unsupported native selector {$selector} in {$name}.");
        }
        $parts = preg_split('/\s*(>)\s*|\s+/', $selector, -1, PREG_SPLIT_DELIM_CAPTURE | PREG_SPLIT_NO_EMPTY);
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
            $classes += count($compound['classes']) + count($compound['attributes']) + count($compound['pseudos']);
            $tags += $compound['tag'] === null || $compound['tag'] === '*' ? 0 : 1;
        }

        return ['source' => $selector, 'compounds' => $compounds, 'specificity' => [$ids, $classes, $tags]];
    }

    /** @return array{tag:?string,id:?string,classes:list<string>,attributes:list<array{name:string,operator:string,value:?string}>,pseudos:list<string>} */
    private static function compound(string $source, string $selector, string $name): array
    {
        $tag = null;
        $id = null;
        $classes = [];
        $attributes = [];
        $pseudos = [];
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
            } elseif (preg_match('/^:(pressed|hover|focus|focus-visible|disabled|checked|selected|active|loading|error|empty|first-child|last-child)/', $tail, $match) === 1) {
                $pseudos[] = $match[1];
            } else {
                throw new RuntimeException("Unsupported native selector fragment {$tail} in {$selector} ({$name}).");
            }
            $offset += strlen($match[0]);
        }
        if ($tag === null && $id === null && $classes === [] && $attributes === [] && $pseudos === []) {
            throw new RuntimeException("Invalid native selector {$selector} in {$name}.");
        }

        return compact('tag', 'id', 'classes', 'attributes', 'pseudos');
    }
}

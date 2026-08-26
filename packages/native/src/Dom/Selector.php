<?php

declare(strict_types=1);

namespace Pam\Native\Dom;

use InvalidArgumentException;
use Pam\Native\Element as NativeElement;

final readonly class Selector
{
    /** @param non-empty-list<array{compound: string, combinator: ?string}> $parts */
    private function __construct(private array $parts)
    {
    }

    public static function compile(string $source): self
    {
        $source = trim($source);
        if ($source === '' || strlen($source) > 512) {
            throw new InvalidArgumentException('DOM selectors must contain between 1 and 512 bytes.');
        }
        if (str_contains($source, ',') || preg_match('/[+~:*()]/', $source) === 1) {
            throw new InvalidArgumentException('DOM selectors support type, id, class, data attributes, child, and descendant combinators.');
        }

        preg_match_all('/(?:\[[^\]]+\]|[^\s>])+|>/', $source, $matches);
        $tokens = $matches[0] ?? [];
        $parts = [];
        $nextCombinator = null;
        foreach ($tokens as $token) {
            if ($token === '>') {
                if ($parts === [] || $nextCombinator !== null) {
                    throw new InvalidArgumentException("Invalid DOM selector {$source}.");
                }
                $nextCombinator = '>';
                continue;
            }
            $parts[] = ['compound' => $token, 'combinator' => $parts === [] ? null : ($nextCombinator ?? ' ')];
            $nextCombinator = null;
        }
        if ($parts === [] || $nextCombinator !== null || count($parts) > 16) {
            throw new InvalidArgumentException("Invalid or excessively complex DOM selector {$source}.");
        }

        return new self($parts);
    }

    /** @param callable(string): ?NativeElement $parent */
    public function matches(NativeElement $element, callable $parent): bool
    {
        return $this->matchesAt(count($this->parts) - 1, $element, $parent);
    }

    /** @param callable(string): ?NativeElement $parent */
    private function matchesAt(int $index, NativeElement $element, callable $parent): bool
    {
        if (!$this->matchesCompound($this->parts[$index]['compound'], $element)) {
            return false;
        }
        if ($index === 0) {
            return true;
        }

        $identity = $element->domIdentity();
        $ancestor = $identity === null ? null : $parent($identity);
        if ($this->parts[$index]['combinator'] === '>') {
            return $ancestor !== null && $this->matchesAt($index - 1, $ancestor, $parent);
        }
        while ($ancestor !== null) {
            if ($this->matchesAt($index - 1, $ancestor, $parent)) {
                return true;
            }
            $identity = $ancestor->domIdentity();
            $ancestor = $identity === null ? null : $parent($identity);
        }

        return false;
    }

    private function matchesCompound(string $compound, NativeElement $element): bool
    {
        preg_match('/^[A-Za-z][A-Za-z0-9-]*/', $compound, $tagMatch);
        $tag = $tagMatch[0] ?? null;
        if ($tag !== null && strcasecmp($tag, $element->kind()->name) !== 0) {
            return false;
        }
        if (preg_match('/#([A-Za-z][A-Za-z0-9_.:-]{0,127})/', $compound, $id) === 1 && $element->domId() !== $id[1]) {
            return false;
        }
        preg_match_all('/\.([A-Za-z_][A-Za-z0-9_-]{0,127})/', $compound, $classes);
        foreach ($classes[1] ?? [] as $class) {
            if (!in_array($class, $element->domClasses(), true)) {
                return false;
            }
        }
        preg_match_all('/\[data-([a-z][a-z0-9-]{0,63})(?:=(?:"([^"]*)"|\'([^\']*)\'|([^\]]+)))?\]/', $compound, $data, PREG_SET_ORDER);
        foreach ($data as $attribute) {
            $name = $attribute[1];
            $dataset = $element->domDataset();
            if (!array_key_exists($name, $dataset)) {
                return false;
            }
            $hasExpectedValue = str_contains($attribute[0], '=');
            $expected = $attribute[2] !== '' ? $attribute[2] : ($attribute[3] !== '' ? $attribute[3] : trim($attribute[4] ?? ''));
            if ($hasExpectedValue && $dataset[$name] !== $expected) {
                return false;
            }
        }

        $consumed = preg_replace([
            '/^[A-Za-z][A-Za-z0-9-]*/',
            '/#[A-Za-z][A-Za-z0-9_.:-]{0,127}/',
            '/\.[A-Za-z_][A-Za-z0-9_-]{0,127}/',
            '/\[data-[^\]]+\]/',
        ], '', $compound);
        if ($consumed !== '') {
            throw new InvalidArgumentException("Unsupported DOM selector compound {$compound}.");
        }

        return true;
    }
}

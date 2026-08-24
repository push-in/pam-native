<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Align;

/** Tailwind-like utilities compiled to the same native style attributes as CSS. */
final class StyleUtilityCompiler
{
    private function __construct()
    {
    }

    /** @return array{attribute:string,value:int|float}|null */
    public static function compile(string $class): ?array
    {
        $fixed = [
            'flex-1' => ['flexGrow', 1.0],
            'w-full' => ['widthPercent', 100.0],
            'h-full' => ['heightPercent', 100.0],
            'bg-white' => ['backgroundColor', 0xFFFFFFFF],
            'bg-black' => ['backgroundColor', 0xFF000000],
            'text-white' => ['textColor', 0xFFFFFFFF],
            'text-black' => ['textColor', 0xFF000000],
            'items-start' => ['alignItems', Align::Start->value],
            'items-center' => ['alignItems', Align::Center->value],
            'items-end' => ['alignItems', Align::End->value],
            'items-stretch' => ['alignItems', Align::Stretch->value],
            'items-baseline' => ['alignItems', Align::Baseline->value],
        ];
        if (isset($fixed[$class])) {
            return ['attribute' => $fixed[$class][0], 'value' => $fixed[$class][1]];
        }
        if (preg_match('/^(p|px|py|m|mx|my|gap|rounded|elevation)-(\d+(?:\.\d+)?)$/D', $class, $match) === 1) {
            return [
                'attribute' => match ($match[1]) {
                    'p' => 'padding', 'px' => 'paddingHorizontal', 'py' => 'paddingVertical',
                    'm' => 'margin', 'mx' => 'marginHorizontal', 'my' => 'marginVertical',
                    'gap' => 'gap', 'rounded' => 'borderRadius', 'elevation' => 'elevation',
                },
                'value' => (float) $match[2] * 4.0,
            ];
        }
        if (preg_match('/^opacity-(\d{1,3})$/D', $class, $match) === 1) {
            return ['attribute' => 'opacity', 'value' => min(100, (int) $match[1]) / 100];
        }
        if (preg_match('/^grid-(\d{1,2})$/D', $class, $match) === 1) {
            return ['attribute' => 'columns', 'value' => max(1, min(64, (int) $match[1]))];
        }
        if (preg_match('/^col(?:(-sm|-md|-lg|-xl))?-(\d{1,2})$/D', $class, $match) === 1) {
            return [
                'attribute' => match ($match[1] ?? '') {
                    '-sm' => 'spanSm', '-md' => 'spanMd', '-lg' => 'spanLg', '-xl' => 'spanXl', default => 'span',
                },
                'value' => max(1, min(64, (int) $match[2])),
            ];
        }
        if (preg_match('/^(offset|order)(?:(-sm|-md|-lg|-xl))?-(\d+)$/D', $class, $match) === 1) {
            $suffix = match ($match[2] ?? '') { '-sm' => 'Sm', '-md' => 'Md', '-lg' => 'Lg', '-xl' => 'Xl', default => '' };
            return ['attribute' => $match[1].$suffix, 'value' => (int) $match[3]];
        }
        if (preg_match('/^gutter-(x|y)-(\d+(?:\.\d+)?)$/D', $class, $match) === 1) {
            return ['attribute' => $match[1] === 'x' ? 'gridColumnGap' : 'gridRowGap', 'value' => (float) $match[2] * 4.0];
        }
        return null;
    }

    /** @return array{version:int,source:string,grammar:list<string>} */
    public static function manifest(): array
    {
        return [
            'version' => 1,
            'source' => 'tailwind-compatible',
            'grammar' => ['fixed', 'spacing', 'opacity', 'grid', 'span', 'offset', 'order', 'gutter'],
        ];
    }
}

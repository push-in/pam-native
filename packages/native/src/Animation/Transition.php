<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

use InvalidArgumentException;

use function in_array;
use function strlen;

/**
 * CSS transitions compiled for the native runtime, with per-property
 * durations, delays and timing functions plus a `spring()` timing function
 * (WebKit order: `spring(mass stiffness damping [velocity])`).
 *
 * ```css
 * .bubble { transition: transform 300ms spring(1 260 18), opacity 120ms ease-out 40ms; }
 * ```
 *
 * The compiled `transitionSpec` stores the four CSS longhand lists so later
 * longhand declarations override a shorthand exactly as in CSS.
 *
 * @internal Used by the scoped style compiler and Element::transition().
 */
final class Transition
{
    private const array LONGHANDS = [
        'transition-property' => 'property',
        'transition-duration' => 'duration',
        'transition-timing-function' => 'timing',
        'transition-delay' => 'delay',
    ];

    private const array PROPERTIES = [
        'all', 'none', 'opacity', 'transform', 'translate', 'scale', 'rotate', 'border-radius',
    ];

    private function __construct()
    {
    }

    /**
     * Applies one transition declaration to [spec] and returns the new spec.
     */
    public static function apply(string $spec, string $property, string $value): string
    {
        $lists = self::lists($spec);
        $lower = strtolower(trim($value));
        if ($property === 'transition') {
            $lists = self::shorthand($lower);
        } elseif (isset(self::LONGHANDS[$property])) {
            $kind = self::LONGHANDS[$property];
            $items = array_map('trim', self::split($lower));
            $lists[$kind] = array_map(
                static fn (string $item): string => match ($kind) {
                    'property' => self::property($item),
                    'duration', 'delay' => (string) self::time($item),
                    default => self::timing($item),
                },
                $items,
            );
        } else {
            throw new InvalidArgumentException("Unsupported transition property {$property}.");
        }

        return self::encode($lists);
    }

    /** Longest duration + delay, used for legacy single-duration hosts. */
    public static function maxDuration(string $spec): int
    {
        $lists = self::lists($spec);
        $durations = array_map('intval', $lists['duration'] ?? ['0']);

        return $durations === [] ? 0 : max($durations);
    }

    /** True when at least one listed property animates. */
    public static function animates(string $spec): bool
    {
        $lists = self::lists($spec);
        $properties = $lists['property'] ?? ['all'];
        if ($properties === ['none']) {
            return false;
        }
        foreach ($lists['timing'] ?? [] as $timing) {
            if (str_starts_with($timing, 'spring:')) {
                return true;
            }
        }

        return self::maxDuration($spec) > 0;
    }

    /** Legacy `AnimationEasing` integer for the first timing function. */
    public static function legacyEasing(string $spec): int
    {
        $first = self::lists($spec)['timing'][0] ?? 'ease';

        return match (true) {
            $first === 'linear' => 1,
            $first === 'ease-in' => 2,
            $first === 'ease-out' => 3,
            str_starts_with($first, 'spring:') => 5,
            default => 4,
        };
    }

    /** CSS timing function → native token. */
    public static function timing(string $function): string
    {
        $function = strtolower(trim($function));
        if ($function === 'step-start' || $function === 'step-end' || str_starts_with($function, 'steps(')) {
            return 'linear';
        }
        if ($function === 'spring' || str_starts_with($function, 'spring(')) {
            $arguments = [];
            if ($function !== 'spring' && preg_match('/^spring\(([^)]*)\)$/D', $function, $match) === 1) {
                $arguments = array_values(array_filter(
                    preg_split('/[\s,]+/', trim($match[1])) ?: [],
                    static fn (string $part): bool => $part !== '',
                ));
            }
            $numbers = array_map(static function (string $part): float {
                if (!is_numeric($part)) {
                    throw new InvalidArgumentException("Invalid spring() argument {$part}.");
                }
                return (float) $part;
            }, $arguments);
            $spring = new Spring(
                stiffness: $numbers[1] ?? 100.0,
                damping: $numbers[2] ?? 10.0,
                mass: $numbers[0] ?? 1.0,
            );

            return $spring->token();
        }

        return Easing::token($function === 'ease' ? Easing::Ease : $function);
    }

    /** @return array<string, list<string>> */
    private static function shorthand(string $value): array
    {
        if ($value === 'none') {
            return ['property' => ['none'], 'duration' => ['0'], 'timing' => ['ease'], 'delay' => ['0']];
        }
        $lists = ['property' => [], 'duration' => [], 'timing' => [], 'delay' => []];
        foreach (self::split($value) as $layer) {
            $property = 'all';
            $times = [];
            $timing = 'ease';
            foreach (self::parts($layer) as $part) {
                if (preg_match('/^(?:\d+|\d*\.\d+)(?:ms|s)$/D', $part) === 1) {
                    $times[] = self::time($part);
                } elseif (
                    in_array($part, ['linear', 'ease', 'ease-in', 'ease-out', 'ease-in-out', 'step-start', 'step-end', 'spring'], true)
                    || str_starts_with($part, 'cubic-bezier(')
                    || str_starts_with($part, 'steps(')
                    || str_starts_with($part, 'spring(')
                ) {
                    $timing = self::timing($part);
                } elseif (in_array($part, ['normal', 'allow-discrete'], true)) {
                    continue;
                } else {
                    $property = self::property($part);
                }
            }
            $lists['property'][] = $property;
            $lists['duration'][] = (string) ($times[0] ?? 0);
            $lists['timing'][] = $timing;
            $lists['delay'][] = (string) ($times[1] ?? 0);
        }

        return $lists;
    }

    private static function property(string $property): string
    {
        $property = trim($property);
        if (!in_array($property, self::PROPERTIES, true)) {
            throw new InvalidArgumentException(
                "Native transitions support ".implode(', ', self::PROPERTIES)."; received {$property}.",
            );
        }

        return $property;
    }

    private static function time(string $part): int
    {
        if (preg_match('/^((?:\d+|\d*\.\d+))(ms|s)$/D', trim($part), $match) !== 1) {
            throw new InvalidArgumentException("Invalid transition time {$part}.");
        }
        $milliseconds = (int) round((float) $match[1] * ($match[2] === 's' ? 1000 : 1));

        return Motion::milliseconds(min(60_000, $milliseconds), 'Transition time');
    }

    /** @return array<string, list<string>> */
    private static function lists(string $spec): array
    {
        $lists = [];
        foreach (explode("\n", $spec) as $line) {
            $line = trim($line);
            if (!str_starts_with($line, '@') || !str_contains($line, ' ')) {
                continue;
            }
            [$name, $values] = explode(' ', substr($line, 1), 2);
            $lists[$name] = array_map('trim', explode(',', $values));
        }

        return $lists;
    }

    /** @param array<string, list<string>> $lists */
    private static function encode(array $lists): string
    {
        $lines = [];
        foreach (['property', 'duration', 'timing', 'delay'] as $kind) {
            if (isset($lists[$kind]) && $lists[$kind] !== []) {
                $lines[] = '@'.$kind.' '.implode(',', $lists[$kind]);
            }
        }

        return implode("\n", $lines);
    }

    /** @return list<string> */
    private static function split(string $value): array
    {
        $parts = [];
        $depth = 0;
        $start = 0;
        $length = strlen($value);
        for ($index = 0; $index < $length; $index++) {
            $character = $value[$index];
            if ($character === '(') {
                $depth++;
            } elseif ($character === ')') {
                $depth--;
            } elseif ($character === ',' && $depth === 0) {
                $parts[] = trim(substr($value, $start, $index - $start));
                $start = $index + 1;
            }
        }
        $parts[] = trim(substr($value, $start));

        return array_values(array_filter($parts, static fn (string $part): bool => $part !== ''));
    }

    /** @return list<string> */
    private static function parts(string $layer): array
    {
        $parts = [];
        $depth = 0;
        $current = '';
        $length = strlen($layer);
        for ($index = 0; $index < $length; $index++) {
            $character = $layer[$index];
            if ($character === '(') {
                $depth++;
            } elseif ($character === ')') {
                $depth--;
            }
            if (ctype_space($character) && $depth === 0) {
                if ($current !== '') {
                    $parts[] = $current;
                    $current = '';
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
}

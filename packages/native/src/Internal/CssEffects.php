<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use RuntimeException;

/**
 * Compiles CSS visual effects (gradients, multiple/inset box shadows and
 * filter functions) into compact wire strings decoded once by the native
 * painters. Lengths are device-independent pixels; colors are ARGB.
 *
 * Gradient wire (JSON list, topmost layer first):
 *   {"t":1|2 (linear|radial), "r":0|1 (repeating), "s":[[argb, value, unit], ...],
 *    linear:  "m":1 + "a":<deg> | "m":2 + "x":±1,"y":±1 (magic corner) | "m":3 + "p":[x0,y0,x1,y1] (box fractions)
 *    radial:  "e":0|1 (circle|ellipse), "z":1..5 (closest-side, closest-corner,
 *             farthest-side, farthest-corner, explicit) + "w":[v,u],"h":[v,u],
 *             "c":[[v,u],[v,u]] (center x/y)}
 *   unit: 0 auto, 1 fraction of the gradient line/box, 2 dp.
 * Box shadow wire: [[x, y, blur, spread, argb, inset], ...] in paint order.
 * Color matrix wire: 20 floats (Android ColorMatrix layout, offsets 0..255).
 */
final class CssEffects
{
    public const int GRADIENT_LINEAR = 1;
    public const int GRADIENT_RADIAL = 2;
    public const int DIRECTION_ANGLE = 1;
    public const int DIRECTION_CORNER = 2;
    public const int DIRECTION_POINTS = 3;
    public const int UNIT_AUTO = 0;
    public const int UNIT_FRACTION = 1;
    public const int UNIT_DP = 2;
    public const int SIZE_CLOSEST_SIDE = 1;
    public const int SIZE_CLOSEST_CORNER = 2;
    public const int SIZE_FARTHEST_SIDE = 3;
    public const int SIZE_FARTHEST_CORNER = 4;
    public const int SIZE_EXPLICIT = 5;

    private const array GRADIENTS = [
        'linear-gradient' => [self::GRADIENT_LINEAR, 0],
        'repeating-linear-gradient' => [self::GRADIENT_LINEAR, 1],
        'radial-gradient' => [self::GRADIENT_RADIAL, 0],
        'repeating-radial-gradient' => [self::GRADIENT_RADIAL, 1],
        '-webkit-linear-gradient' => [self::GRADIENT_LINEAR, 0],
    ];

    private function __construct()
    {
    }

    /**
     * `background` / `background-image` value → gradient layers plus the
     * final-layer color (shorthand only).
     *
     * @return array{layers: list<array<string, mixed>>, color: ?int}
     */
    public static function background(string $value, bool $shorthand, string $name): array
    {
        $layers = [];
        $color = null;
        $items = self::split($value, ',');
        foreach ($items as $index => $item) {
            $tokens = self::split($item, ' ');
            $gradient = null;
            foreach ($tokens as $token) {
                $lower = strtolower($token);
                if (preg_match('/^([a-z-]+)\((.*)\)$/sD', $lower, $match) === 1 && isset(self::GRADIENTS[$match[1]])) {
                    if ($gradient !== null) {
                        throw new RuntimeException("A background layer in {$name} can hold one gradient.");
                    }
                    $gradient = self::gradient($token, $name);
                    continue;
                }
                if (str_starts_with($lower, 'url(') || str_starts_with($lower, 'image-set(')) {
                    throw new RuntimeException(
                        "Native background images in {$name} are unsupported; use <ImageBackground> (gradients are supported).",
                    );
                }
                if (preg_match('/^(conic-gradient|repeating-conic-gradient|cross-fade|element|paint)\(/', $lower) === 1) {
                    throw new RuntimeException(
                        "{$lower} is unsupported natively in {$name}; use linear-gradient() or radial-gradient().",
                    );
                }
                if (in_array($lower, ['none', 'no-repeat', 'border-box', 'scroll'], true)) {
                    continue;
                }
                if ($shorthand && $index === count($items) - 1 && $color === null) {
                    try {
                        $color = CssColor::parse($token, "background in {$name}");
                        continue;
                    } catch (RuntimeException) {
                    }
                }
                throw new RuntimeException(
                    "Unsupported background value {$token} in {$name}; native backgrounds take a color and linear/radial gradients covering the border box.",
                );
            }
            if ($gradient !== null) {
                $layers[] = $gradient;
            }
        }

        return ['layers' => $layers, 'color' => $color];
    }

    /** @return array<string, mixed> */
    public static function gradient(string $value, string $name): array
    {
        if (preg_match('/^([A-Za-z-]+)\((.*)\)$/sD', trim($value), $match) !== 1
            || !isset(self::GRADIENTS[strtolower($match[1])])) {
            throw new RuntimeException("Expected a linear or radial gradient in {$name}, got {$value}.");
        }
        $function = strtolower($match[1]);
        [$type, $repeating] = self::GRADIENTS[$function];
        $arguments = self::split($match[2], ',');
        if ($arguments === []) {
            throw new RuntimeException("{$function}() needs color stops in {$name}.");
        }
        $layer = ['t' => $type, 'r' => $repeating];
        if ($type === self::GRADIENT_LINEAR) {
            $direction = self::linearDirection($arguments[0], $function === '-webkit-linear-gradient', $name);
            if ($direction !== null) {
                array_shift($arguments);
                $layer += $direction;
            } else {
                $layer += ['m' => self::DIRECTION_ANGLE, 'a' => 180];
            }
        } else {
            $shape = self::radialShape($arguments[0], $name);
            if ($shape !== null) {
                array_shift($arguments);
            }
            $layer += $shape ?? [
                'e' => 1,
                'z' => self::SIZE_FARTHEST_CORNER,
                'c' => [[0.5, self::UNIT_FRACTION], [0.5, self::UNIT_FRACTION]],
            ];
        }
        $layer['s'] = self::stops($arguments, $function, $name);

        return $layer;
    }

    /**
     * expo-linear-gradient props (colors, start, end, locations) → one layer.
     *
     * @param list<int> $colors
     * @param array{0: float, 1: float} $start
     * @param array{0: float, 1: float} $end
     * @param list<float>|null $locations
     * @return array<string, mixed>
     */
    public static function pointsGradient(array $colors, array $start, array $end, ?array $locations, string $name): array
    {
        if (count($colors) < 2) {
            throw new RuntimeException("LinearGradient in {$name} needs at least two colors.");
        }
        if ($locations !== null && count($locations) !== count($colors)) {
            throw new RuntimeException("LinearGradient locations in {$name} must match the colors count.");
        }
        $stops = [];
        foreach ($colors as $index => $color) {
            $stops[] = $locations === null
                ? [$color, 0, self::UNIT_AUTO]
                : [$color, self::round($locations[$index]), self::UNIT_FRACTION];
        }

        return [
            't' => self::GRADIENT_LINEAR,
            'r' => 0,
            'm' => self::DIRECTION_POINTS,
            'p' => [self::round($start[0]), self::round($start[1]), self::round($end[0]), self::round($end[1])],
            's' => $stops,
        ];
    }

    /** @param list<array<string, mixed>> $layers */
    public static function encode(array $layers): string
    {
        return $layers === [] ? '' : json_encode($layers, JSON_THROW_ON_ERROR | JSON_PRESERVE_ZERO_FRACTION);
    }

    /**
     * @return list<array{0: float, 1: float, 2: float, 3: float, 4: int, 5: int}>
     */
    public static function boxShadows(string $value, string $name): array
    {
        $shadows = [];
        foreach (self::split($value, ',') as $item) {
            $tokens = self::split($item, ' ');
            $inset = 0;
            $color = null;
            $lengths = [];
            foreach ($tokens as $token) {
                if (strtolower($token) === 'inset') {
                    if ($inset === 1) {
                        throw new RuntimeException("Duplicate inset in box-shadow in {$name}.");
                    }
                    $inset = 1;
                    continue;
                }
                if (self::isLength($token)) {
                    $lengths[] = self::length($token, $name);
                    continue;
                }
                if ($color !== null) {
                    throw new RuntimeException("Invalid box-shadow color in {$name}.");
                }
                if (strtolower($token) === 'currentcolor') {
                    throw new RuntimeException("currentColor in box-shadow needs a literal color in {$name}.");
                }
                $color = CssColor::parse($token, "Box shadow color in {$name}");
            }
            if (count($lengths) < 2 || count($lengths) > 4) {
                throw new RuntimeException(
                    "Native box-shadow in {$name} expects x-offset, y-offset, optional blur/spread, optional inset and color.",
                );
            }
            if (($lengths[2] ?? 0.0) < 0.0) {
                throw new RuntimeException("box-shadow blur radius cannot be negative in {$name}.");
            }
            $shadows[] = [
                $lengths[0],
                $lengths[1],
                $lengths[2] ?? 0.0,
                $lengths[3] ?? 0.0,
                $color ?? CssColor::parse('rgba(0, 0, 0, 0.33)', "Box shadow in {$name}"),
                $inset,
            ];
        }

        return $shadows;
    }

    /**
     * @return array{blur: float, matrix: ?list<float>}
     */
    public static function filter(string $value, string $property, string $name): array
    {
        $remaining = trim($value);
        $blur = 0.0;
        $matrix = null;
        if (strtolower($remaining) === 'none') {
            return ['blur' => 0.0, 'matrix' => null];
        }
        while ($remaining !== '') {
            if (preg_match('/^([A-Za-z-]+)\(((?:[^()]|\([^()]*\))*)\)\s*/D', $remaining, $match) !== 1) {
                throw new RuntimeException("Invalid {$property} {$value} in {$name}.");
            }
            $function = strtolower($match[1]);
            $argument = trim($match[2]);
            $remaining = ltrim(substr($remaining, strlen($match[0])));
            if ($function === 'blur') {
                $blur += $argument === '' ? 0.0 : self::length($argument, $name);
                continue;
            }
            $step = self::colorMatrix($function, $argument, $property, $name);
            $matrix = $matrix === null ? $step : self::multiply($step, $matrix);
        }
        if ($blur < 0.0) {
            throw new RuntimeException("{$property} blur() cannot be negative in {$name}.");
        }

        return ['blur' => $blur, 'matrix' => $matrix];
    }

    /** @param list<float>|null $matrix */
    public static function encodeMatrix(?array $matrix): string
    {
        if ($matrix === null) {
            return '';
        }

        return implode(',', array_map(static fn (float $value): string => self::number($value), $matrix));
    }

    public static function number(float $value): string
    {
        $rounded = round($value, 6);
        if ($rounded == 0.0) {
            return '0';
        }
        $text = rtrim(rtrim(sprintf('%.6F', $rounded), '0'), '.');

        return $text === '-0' ? '0' : $text;
    }

    /** @return list<float> */
    private static function colorMatrix(string $function, string $argument, string $property, string $name): array
    {
        $amount = static function (float $default, bool $clamp) use ($argument, $function, $name): float {
            if ($argument === '') {
                return $default;
            }
            $value = str_ends_with($argument, '%')
                ? (float) substr($argument, 0, -1) / 100
                : (preg_match('/^-?(?:\d+|\d*\.\d+)$/D', $argument) === 1
                    ? (float) $argument
                    : throw new RuntimeException("{$function}() expects a number or percentage in {$name}."));
            if ($value < 0.0) {
                throw new RuntimeException("{$function}() cannot be negative in {$name}.");
            }

            return $clamp ? min(1.0, $value) : $value;
        };
        $scale = static fn (float $r, float $g, float $b, float $a = 1.0): array => [
            $r, 0, 0, 0, 0,
            0, $g, 0, 0, 0,
            0, 0, $b, 0, 0,
            0, 0, 0, $a, 0,
        ];
        $rgb = static fn (array $m): array => [
            $m[0], $m[1], $m[2], 0, 0,
            $m[3], $m[4], $m[5], 0, 0,
            $m[6], $m[7], $m[8], 0, 0,
            0, 0, 0, 1, 0,
        ];
        $matrix = match ($function) {
            'brightness' => $scale(...array_fill(0, 3, $amount(1.0, false))),
            'contrast' => (static function (float $c): array {
                $offset = (0.5 - 0.5 * $c) * 255;
                return [
                    $c, 0, 0, 0, $offset,
                    0, $c, 0, 0, $offset,
                    0, 0, $c, 0, $offset,
                    0, 0, 0, 1, 0,
                ];
            })($amount(1.0, false)),
            'opacity' => $scale(1.0, 1.0, 1.0, $amount(1.0, true)),
            'invert' => (static function (float $a): array {
                $d = 1 - 2 * $a;
                $o = $a * 255;
                return [
                    $d, 0, 0, 0, $o,
                    0, $d, 0, 0, $o,
                    0, 0, $d, 0, $o,
                    0, 0, 0, 1, 0,
                ];
            })($amount(1.0, true)),
            'grayscale' => (static function (float $a) use ($rgb): array {
                $s = 1 - $a;
                return $rgb([
                    0.2126 + 0.7874 * $s, 0.7152 - 0.7152 * $s, 0.0722 - 0.0722 * $s,
                    0.2126 - 0.2126 * $s, 0.7152 + 0.2848 * $s, 0.0722 - 0.0722 * $s,
                    0.2126 - 0.2126 * $s, 0.7152 - 0.7152 * $s, 0.0722 + 0.9278 * $s,
                ]);
            })($amount(1.0, true)),
            'sepia' => (static function (float $a) use ($rgb): array {
                $s = 1 - $a;
                return $rgb([
                    0.393 + 0.607 * $s, 0.769 - 0.769 * $s, 0.189 - 0.189 * $s,
                    0.349 - 0.349 * $s, 0.686 + 0.314 * $s, 0.168 - 0.168 * $s,
                    0.272 - 0.272 * $s, 0.534 - 0.534 * $s, 0.131 + 0.869 * $s,
                ]);
            })($amount(1.0, true)),
            'saturate' => (static function (float $s) use ($rgb): array {
                return $rgb([
                    0.213 + 0.787 * $s, 0.715 - 0.715 * $s, 0.072 - 0.072 * $s,
                    0.213 - 0.213 * $s, 0.715 + 0.285 * $s, 0.072 - 0.072 * $s,
                    0.213 - 0.213 * $s, 0.715 - 0.715 * $s, 0.072 + 0.928 * $s,
                ]);
            })($amount(1.0, false)),
            'hue-rotate' => (static function (float $degrees) use ($rgb): array {
                $radians = deg2rad($degrees);
                $cos = cos($radians);
                $sin = sin($radians);
                return $rgb([
                    0.213 + $cos * 0.787 - $sin * 0.213,
                    0.715 - $cos * 0.715 - $sin * 0.715,
                    0.072 - $cos * 0.072 + $sin * 0.928,
                    0.213 - $cos * 0.213 + $sin * 0.143,
                    0.715 + $cos * 0.285 + $sin * 0.140,
                    0.072 - $cos * 0.072 - $sin * 0.283,
                    0.213 - $cos * 0.213 - $sin * 0.787,
                    0.715 - $cos * 0.715 + $sin * 0.715,
                    0.072 + $cos * 0.928 + $sin * 0.072,
                ]);
            })($argument === '' ? 0.0 : (float) self::angle($argument, $name)),
            'drop-shadow' => throw new RuntimeException(
                "{$property}: drop-shadow() is unsupported natively in {$name}; use box-shadow on the element.",
            ),
            'url' => throw new RuntimeException("{$property}: url() SVG filters are unsupported natively in {$name}."),
            default => throw new RuntimeException("Unknown {$property} function {$function}() in {$name}."),
        };

        return array_map(static fn (int|float $value): float => (float) $value, $matrix);
    }

    /**
     * @param list<float> $a applied second
     * @param list<float> $b applied first
     * @return list<float>
     */
    private static function multiply(array $a, array $b): array
    {
        $result = [];
        for ($row = 0; $row < 4; $row++) {
            for ($column = 0; $column < 5; $column++) {
                $sum = $column === 4 ? $a[$row * 5 + 4] : 0.0;
                for ($k = 0; $k < 4; $k++) {
                    $sum += $a[$row * 5 + $k] * $b[$k * 5 + $column];
                }
                $result[] = $sum;
            }
        }

        return $result;
    }

    /** @return array<string, mixed>|null */
    private static function linearDirection(string $argument, bool $webkit, string $name): ?array
    {
        $lower = strtolower(trim($argument));
        if (preg_match('/^to\s+(.+)$/D', $lower, $match) === 1) {
            $words = preg_split('/\s+/', trim($match[1])) ?: [];
            $x = 0;
            $y = 0;
            foreach ($words as $word) {
                match ($word) {
                    'left' => $x === 0 ? $x = -1 : throw new RuntimeException("Invalid gradient direction {$argument} in {$name}."),
                    'right' => $x === 0 ? $x = 1 : throw new RuntimeException("Invalid gradient direction {$argument} in {$name}."),
                    'top' => $y === 0 ? $y = -1 : throw new RuntimeException("Invalid gradient direction {$argument} in {$name}."),
                    'bottom' => $y === 0 ? $y = 1 : throw new RuntimeException("Invalid gradient direction {$argument} in {$name}."),
                    default => throw new RuntimeException("Invalid gradient direction {$argument} in {$name}."),
                };
            }
            if (count($words) > 2 || ($x === 0 && $y === 0)) {
                throw new RuntimeException("Invalid gradient direction {$argument} in {$name}.");
            }
            if ($x !== 0 && $y !== 0) {
                return ['m' => self::DIRECTION_CORNER, 'x' => $x, 'y' => $y];
            }

            return ['m' => self::DIRECTION_ANGLE, 'a' => match (true) {
                $y === -1 => 0,
                $x === 1 => 90,
                $y === 1 => 180,
                default => 270,
            }];
        }
        if (preg_match('/^-?(?:\d+|\d*\.\d+)(deg|rad|grad|turn)$/D', $lower) === 1 || $lower === '0') {
            $angle = (float) self::angle($lower, $name);
            // Legacy -webkit- angles use the math convention (0deg = to right).
            if ($webkit) {
                $angle = 90 - $angle;
            }

            return ['m' => self::DIRECTION_ANGLE, 'a' => self::round(fmod(fmod($angle, 360) + 360, 360))];
        }
        if ($webkit && in_array($lower, ['top', 'bottom', 'left', 'right'], true)) {
            return ['m' => self::DIRECTION_ANGLE, 'a' => match ($lower) {
                'bottom' => 0, 'left' => 90, 'top' => 180, default => 270,
            }];
        }

        return null;
    }

    /** @return array<string, mixed>|null */
    private static function radialShape(string $argument, string $name): ?array
    {
        $lower = strtolower(trim($argument));
        $positionText = null;
        if (preg_match('/^(.*?)\s*\bat\s+(.+)$/sD', $lower, $match) === 1) {
            $lower = trim($match[1]);
            $positionText = trim($match[2]);
        } elseif (!self::looksLikeRadialConfig($lower)) {
            return null;
        }
        $ellipse = null;
        $size = null;
        $explicit = [];
        foreach ($lower === '' ? [] : self::split($lower, ' ') as $token) {
            if ($token === 'circle' || $token === 'ellipse') {
                $ellipse = $token === 'ellipse' ? 1 : 0;
                continue;
            }
            $keyword = match ($token) {
                'closest-side' => self::SIZE_CLOSEST_SIDE,
                'closest-corner' => self::SIZE_CLOSEST_CORNER,
                'farthest-side' => self::SIZE_FARTHEST_SIDE,
                'farthest-corner' => self::SIZE_FARTHEST_CORNER,
                default => null,
            };
            if ($keyword !== null) {
                $size = $keyword;
                continue;
            }
            if (self::isLength($token) || preg_match('/^-?(?:\d+|\d*\.\d+)%$/D', $token) === 1) {
                $explicit[] = self::lengthPercentage($token, $name);
                continue;
            }
            throw new RuntimeException("Invalid radial-gradient shape {$argument} in {$name}.");
        }
        if ($explicit !== [] && $size !== null) {
            throw new RuntimeException("radial-gradient() in {$name} takes a size keyword or explicit lengths, not both.");
        }
        $layer = [];
        if ($explicit !== []) {
            if (count($explicit) === 1) {
                if ($ellipse === 1 || $explicit[0][1] === self::UNIT_FRACTION) {
                    throw new RuntimeException("A single radial-gradient size in {$name} must be a length for a circle.");
                }
                $ellipse = 0;
                $layer = ['z' => self::SIZE_EXPLICIT, 'w' => $explicit[0], 'h' => $explicit[0]];
            } elseif (count($explicit) === 2) {
                if ($ellipse === 0) {
                    throw new RuntimeException("A circle radial-gradient in {$name} takes one radius.");
                }
                $ellipse = 1;
                $layer = ['z' => self::SIZE_EXPLICIT, 'w' => $explicit[0], 'h' => $explicit[1]];
            } else {
                throw new RuntimeException("Invalid radial-gradient size in {$name}.");
            }
        } else {
            $layer = ['z' => $size ?? self::SIZE_FARTHEST_CORNER];
        }
        $layer = ['e' => $ellipse ?? 1] + $layer;
        $layer['c'] = $positionText === null
            ? [[0.5, self::UNIT_FRACTION], [0.5, self::UNIT_FRACTION]]
            : self::position($positionText, $name);

        return $layer;
    }

    private static function looksLikeRadialConfig(string $lower): bool
    {
        if (preg_match('/^(circle|ellipse|closest-side|closest-corner|farthest-side|farthest-corner)\b/', $lower) === 1) {
            return true;
        }
        $tokens = self::split($lower, ' ');
        foreach ($tokens as $token) {
            if (!self::isLength($token) && preg_match('/^-?(?:\d+|\d*\.\d+)%$/D', $token) !== 1) {
                return false;
            }
        }

        return $tokens !== [] && count($tokens) <= 2 && !(count($tokens) === 1 && preg_match('/%$/', $tokens[0]) === 1);
    }

    /** @return array{0: array{0: float, 1: int}, 1: array{0: float, 1: int}} */
    private static function position(string $value, string $name): array
    {
        $tokens = self::split($value, ' ');
        $keywords = ['left' => 0.0, 'center' => 0.5, 'right' => 1.0, 'top' => 0.0, 'bottom' => 1.0];
        $component = static function (string $token) use ($keywords, $name): array {
            if (isset($keywords[$token])) {
                return [$keywords[$token], self::UNIT_FRACTION];
            }

            return self::lengthPercentage($token, $name);
        };
        if (count($tokens) === 1) {
            $token = $tokens[0];
            if ($token === 'top' || $token === 'bottom') {
                return [[0.5, self::UNIT_FRACTION], $component($token)];
            }

            return [$component($token), [0.5, self::UNIT_FRACTION]];
        }
        if (count($tokens) !== 2) {
            throw new RuntimeException("Native gradient positions in {$name} take one or two values.");
        }
        [$first, $second] = $tokens;
        if (in_array($first, ['top', 'bottom'], true) || in_array($second, ['left', 'right'], true)) {
            [$first, $second] = [$second, $first];
        }
        if (in_array($first, ['top', 'bottom'], true) || in_array($second, ['left', 'right'], true)) {
            throw new RuntimeException("Invalid gradient position {$value} in {$name}.");
        }

        return [$component($first), $component($second)];
    }

    /**
     * @param list<string> $arguments
     * @return list<array{0: int, 1: float, 2: int}>
     */
    private static function stops(array $arguments, string $function, string $name): array
    {
        $stops = [];
        foreach ($arguments as $argument) {
            $tokens = self::split($argument, ' ');
            $color = null;
            $positions = [];
            foreach ($tokens as $token) {
                if (self::isLength($token) || preg_match('/^-?(?:\d+|\d*\.\d+)%$/D', $token) === 1) {
                    $positions[] = self::lengthPercentage($token, $name);
                    continue;
                }
                if ($color !== null) {
                    throw new RuntimeException("Invalid color stop {$argument} in {$function}() in {$name}.");
                }
                if (strtolower($token) === 'currentcolor') {
                    throw new RuntimeException("currentColor in gradients needs a literal color in {$name}.");
                }
                $color = CssColor::parse($token, "{$function}() color stop in {$name}");
            }
            if ($color === null) {
                throw new RuntimeException(
                    "Gradient color hints ({$argument}) are unsupported natively in {$name}; add an explicit color stop.",
                );
            }
            if (count($positions) > 2) {
                throw new RuntimeException("A color stop takes at most two positions in {$name}.");
            }
            if ($positions === []) {
                $stops[] = [$color, 0, self::UNIT_AUTO];
            }
            foreach ($positions as [$position, $unit]) {
                $stops[] = [$color, $position, $unit];
            }
        }
        if (count($stops) < 2) {
            if (count($stops) === 1) {
                // A single stop paints a solid color (CSS Images 4).
                $stops[] = [$stops[0][0], 0, self::UNIT_AUTO];
            } else {
                throw new RuntimeException("{$function}() needs at least two color stops in {$name}.");
            }
        }

        return $stops;
    }

    /** @return array{0: float, 1: int} */
    private static function lengthPercentage(string $token, string $name): array
    {
        if (preg_match('/^(-?(?:\d+|\d*\.\d+))%$/D', $token, $match) === 1) {
            return [self::round((float) $match[1] / 100), self::UNIT_FRACTION];
        }

        return [self::length($token, $name), self::UNIT_DP];
    }

    private static function isLength(string $token): bool
    {
        return preg_match('/^-?(?:\d+|\d*\.\d+)(?:px|dp|pt|rem|em)?$/iD', trim($token)) === 1;
    }

    private static function length(string $token, string $name): float
    {
        $trimmed = strtolower(trim($token));
        if (preg_match('/^(-?(?:\d+|\d*\.\d+))(px|dp|pt|rem|em)?$/D', $trimmed, $match) !== 1) {
            throw new RuntimeException(
                "Expected a literal length in {$name}, got {$token}; gradients, shadows and filters take px/rem lengths.",
            );
        }
        $value = (float) $match[1];
        if (($match[2] ?? '') === 'rem' || ($match[2] ?? '') === 'em') {
            $value *= 16;
        }

        return self::round($value);
    }

    private static function angle(string $value, string $name): float
    {
        $trimmed = strtolower(trim($value));
        foreach (['turn' => 360.0, 'grad' => 0.9, 'rad' => 180 / M_PI, 'deg' => 1.0] as $unit => $factor) {
            if (str_ends_with($trimmed, $unit)) {
                $number = substr($trimmed, 0, -strlen($unit));
                if (preg_match('/^-?(?:\d+|\d*\.\d+)$/D', $number) !== 1) {
                    break;
                }

                return (float) $number * $factor;
            }
        }
        if ($trimmed === '0') {
            return 0.0;
        }

        throw new RuntimeException("Expected an angle in {$name}, got {$value}.");
    }

    private static function round(float|int $value): float
    {
        $rounded = round((float) $value, 6);

        return $rounded == 0.0 ? 0.0 : $rounded;
    }

    /** @return list<string> */
    private static function split(string $value, string $separator): array
    {
        $parts = [];
        $depth = 0;
        $current = '';
        $length = strlen($value);
        for ($index = 0; $index < $length; $index++) {
            $character = $value[$index];
            if ($character === '(') {
                $depth++;
            } elseif ($character === ')') {
                $depth--;
            }
            $isSeparator = $depth === 0 && ($separator === ' '
                ? ctype_space($character)
                : $character === $separator);
            if ($isSeparator) {
                if (trim($current) !== '') {
                    $parts[] = trim($current);
                }
                $current = '';
                continue;
            }
            $current .= $character;
        }
        if ($depth !== 0) {
            throw new RuntimeException("Unbalanced parentheses in {$value}.");
        }
        if (trim($current) !== '') {
            $parts[] = trim($current);
        }

        return $parts;
    }
}

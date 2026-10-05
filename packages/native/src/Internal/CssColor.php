<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use InvalidArgumentException;

/**
 * Parses CSS Color values into the ARGB integer used by the native protocol.
 */
final class CssColor
{
    /** @var array<string, string> */
    private const NAMED = [
        'aliceblue' => 'F0F8FF',
        'antiquewhite' => 'FAEBD7',
        'aqua' => '00FFFF',
        'aquamarine' => '7FFFD4',
        'azure' => 'F0FFFF',
        'beige' => 'F5F5DC',
        'bisque' => 'FFE4C4',
        'black' => '000000',
        'blanchedalmond' => 'FFEBCD',
        'blue' => '0000FF',
        'blueviolet' => '8A2BE2',
        'brown' => 'A52A2A',
        'burlywood' => 'DEB887',
        'cadetblue' => '5F9EA0',
        'chartreuse' => '7FFF00',
        'chocolate' => 'D2691E',
        'coral' => 'FF7F50',
        'cornflowerblue' => '6495ED',
        'cornsilk' => 'FFF8DC',
        'crimson' => 'DC143C',
        'cyan' => '00FFFF',
        'darkblue' => '00008B',
        'darkcyan' => '008B8B',
        'darkgoldenrod' => 'B8860B',
        'darkgray' => 'A9A9A9',
        'darkgreen' => '006400',
        'darkgrey' => 'A9A9A9',
        'darkkhaki' => 'BDB76B',
        'darkmagenta' => '8B008B',
        'darkolivegreen' => '556B2F',
        'darkorange' => 'FF8C00',
        'darkorchid' => '9932CC',
        'darkred' => '8B0000',
        'darksalmon' => 'E9967A',
        'darkseagreen' => '8FBC8F',
        'darkslateblue' => '483D8B',
        'darkslategray' => '2F4F4F',
        'darkslategrey' => '2F4F4F',
        'darkturquoise' => '00CED1',
        'darkviolet' => '9400D3',
        'deeppink' => 'FF1493',
        'deepskyblue' => '00BFFF',
        'dimgray' => '696969',
        'dimgrey' => '696969',
        'dodgerblue' => '1E90FF',
        'firebrick' => 'B22222',
        'floralwhite' => 'FFFAF0',
        'forestgreen' => '228B22',
        'fuchsia' => 'FF00FF',
        'gainsboro' => 'DCDCDC',
        'ghostwhite' => 'F8F8FF',
        'gold' => 'FFD700',
        'goldenrod' => 'DAA520',
        'gray' => '808080',
        'green' => '008000',
        'greenyellow' => 'ADFF2F',
        'grey' => '808080',
        'honeydew' => 'F0FFF0',
        'hotpink' => 'FF69B4',
        'indianred' => 'CD5C5C',
        'indigo' => '4B0082',
        'ivory' => 'FFFFF0',
        'khaki' => 'F0E68C',
        'lavender' => 'E6E6FA',
        'lavenderblush' => 'FFF0F5',
        'lawngreen' => '7CFC00',
        'lemonchiffon' => 'FFFACD',
        'lightblue' => 'ADD8E6',
        'lightcoral' => 'F08080',
        'lightcyan' => 'E0FFFF',
        'lightgoldenrodyellow' => 'FAFAD2',
        'lightgray' => 'D3D3D3',
        'lightgreen' => '90EE90',
        'lightgrey' => 'D3D3D3',
        'lightpink' => 'FFB6C1',
        'lightsalmon' => 'FFA07A',
        'lightseagreen' => '20B2AA',
        'lightskyblue' => '87CEFA',
        'lightslategray' => '778899',
        'lightslategrey' => '778899',
        'lightsteelblue' => 'B0C4DE',
        'lightyellow' => 'FFFFE0',
        'lime' => '00FF00',
        'limegreen' => '32CD32',
        'linen' => 'FAF0E6',
        'magenta' => 'FF00FF',
        'maroon' => '800000',
        'mediumaquamarine' => '66CDAA',
        'mediumblue' => '0000CD',
        'mediumorchid' => 'BA55D3',
        'mediumpurple' => '9370DB',
        'mediumseagreen' => '3CB371',
        'mediumslateblue' => '7B68EE',
        'mediumspringgreen' => '00FA9A',
        'mediumturquoise' => '48D1CC',
        'mediumvioletred' => 'C71585',
        'midnightblue' => '191970',
        'mintcream' => 'F5FFFA',
        'mistyrose' => 'FFE4E1',
        'moccasin' => 'FFE4B5',
        'navajowhite' => 'FFDEAD',
        'navy' => '000080',
        'oldlace' => 'FDF5E6',
        'olive' => '808000',
        'olivedrab' => '6B8E23',
        'orange' => 'FFA500',
        'orangered' => 'FF4500',
        'orchid' => 'DA70D6',
        'palegoldenrod' => 'EEE8AA',
        'palegreen' => '98FB98',
        'paleturquoise' => 'AFEEEE',
        'palevioletred' => 'DB7093',
        'papayawhip' => 'FFEFD5',
        'peachpuff' => 'FFDAB9',
        'peru' => 'CD853F',
        'pink' => 'FFC0CB',
        'plum' => 'DDA0DD',
        'powderblue' => 'B0E0E6',
        'purple' => '800080',
        'rebeccapurple' => '663399',
        'red' => 'FF0000',
        'rosybrown' => 'BC8F8F',
        'royalblue' => '4169E1',
        'saddlebrown' => '8B4513',
        'salmon' => 'FA8072',
        'sandybrown' => 'F4A460',
        'seagreen' => '2E8B57',
        'seashell' => 'FFF5EE',
        'sienna' => 'A0522D',
        'silver' => 'C0C0C0',
        'skyblue' => '87CEEB',
        'slateblue' => '6A5ACD',
        'slategray' => '708090',
        'slategrey' => '708090',
        'snow' => 'FFFAFA',
        'springgreen' => '00FF7F',
        'steelblue' => '4682B4',
        'tan' => 'D2B48C',
        'teal' => '008080',
        'thistle' => 'D8BFD8',
        'tomato' => 'FF6347',
        'turquoise' => '40E0D0',
        'violet' => 'EE82EE',
        'wheat' => 'F5DEB3',
        'white' => 'FFFFFF',
        'whitesmoke' => 'F5F5F5',
        'yellow' => 'FFFF00',
        'yellowgreen' => '9ACD32',
    ];

    private function __construct()
    {
    }

    public static function parse(string $value, string $context = 'CSS color'): int
    {
        $raw = strtolower(trim($value));
        if ($raw === 'transparent') {
            return 0;
        }
        if (isset(self::NAMED[$raw])) {
            return self::argb(255, ...self::rgbFromHex(self::NAMED[$raw]));
        }
        if (str_starts_with($raw, '#')) {
            return self::hex($raw, $context);
        }
        if (preg_match('/^(rgba?|hsla?)\((.*)\)$/Di', $raw, $match) === 1) {
            return str_starts_with($match[1], 'rgb')
                ? self::rgbFunction($match[2], $context)
                : self::hslFunction($match[2], $context);
        }
        if (preg_match('/^(hwb|lab|lch|oklab|oklch|color|color-mix)\((.*)\)$/Dis', $raw, $match) === 1) {
            [$red, $green, $blue, $alpha] = self::modern($match[1], $match[2], $context);

            return self::argb(
                self::byte(round($alpha * 255, 6)),
                self::byte(round(self::encode($red) * 255, 6)),
                self::byte(round(self::encode($green) * 255, 6)),
                self::byte(round(self::encode($blue) * 255, 6)),
            );
        }

        throw new InvalidArgumentException(
            "{$context} must be a CSS named, hex, rgb(), hsl(), hwb(), lab(), lch(), oklab(), oklch(), color(), color-mix() or transparent color.",
        );
    }

    /**
     * CSS Color 4/5 functions resolved at compile time to linear sRGB.
     *
     * @return array{float, float, float, float} linear red, green, blue, alpha
     */
    private static function modern(string $function, string $body, string $context): array
    {
        $function = strtolower($function);
        if ($function === 'color-mix') {
            return self::mix($body, $context);
        }
        [$channels, $alphaSource] = self::functionalParts($body, $context);
        $alpha = self::alpha($alphaSource, $context) / 255;
        $channels = array_map(static fn (string $channel): string => $channel === 'none' ? '0' : $channel, $channels);
        if ($function === 'color') {
            $space = array_shift($channels);
            if (count($channels) !== 3) {
                throw new InvalidArgumentException("{$context} color() requires a color space and three channels.");
            }
            $values = array_map(
                static fn (string $channel): float => str_ends_with($channel, '%')
                    ? self::number(substr($channel, 0, -1), $context) / 100
                    : self::number($channel, $context),
                $channels,
            );
            return match ($space) {
                'srgb' => [self::decode($values[0]), self::decode($values[1]), self::decode($values[2]), $alpha],
                'srgb-linear' => [$values[0], $values[1], $values[2], $alpha],
                'display-p3' => [...self::p3ToSrgbLinear(self::decode($values[0]), self::decode($values[1]), self::decode($values[2])), $alpha],
                default => throw new InvalidArgumentException("{$context} color() supports srgb, srgb-linear and display-p3."),
            };
        }
        if (count($channels) !== 3) {
            throw new InvalidArgumentException("{$context} {$function}() requires three channels.");
        }
        $percent = static fn (string $value, float $scale): float => str_ends_with($value, '%')
            ? self::number(substr($value, 0, -1), $context) / 100 * $scale
            : self::number($value, $context);

        return match ($function) {
            'hwb' => (function () use ($channels, $alpha, $context): array {
                $hue = self::hue($channels[0], $context);
                $white = self::unit(self::number(rtrim($channels[1], '%'), $context) / 100);
                $black = self::unit(self::number(rtrim($channels[2], '%'), $context) / 100);
                if ($white + $black >= 1) {
                    $gray = $white / ($white + $black);
                    return [self::decode($gray), self::decode($gray), self::decode($gray), $alpha];
                }
                $base = self::hslToRgb($hue, 1.0, 0.5);
                return [
                    self::decode($base[0] * (1 - $white - $black) + $white),
                    self::decode($base[1] * (1 - $white - $black) + $white),
                    self::decode($base[2] * (1 - $white - $black) + $white),
                    $alpha,
                ];
            })(),
            'lab' => [...self::labToSrgbLinear($percent($channels[0], 100), $percent($channels[1], 125), $percent($channels[2], 125)), $alpha],
            'lch' => (function () use ($channels, $alpha, $percent, $context): array {
                $hue = deg2rad(self::hue($channels[2], $context));
                $chroma = $percent($channels[1], 150);
                return [...self::labToSrgbLinear($percent($channels[0], 100), $chroma * cos($hue), $chroma * sin($hue)), $alpha];
            })(),
            'oklab' => [...self::oklabToSrgbLinear($percent($channels[0], 1), $percent($channels[1], 0.4), $percent($channels[2], 0.4)), $alpha],
            'oklch' => (function () use ($channels, $alpha, $percent, $context): array {
                $hue = deg2rad(self::hue($channels[2], $context));
                $chroma = $percent($channels[1], 0.4);
                return [...self::oklabToSrgbLinear($percent($channels[0], 1), $chroma * cos($hue), $chroma * sin($hue)), $alpha];
            })(),
        };
    }

    /** @return array{float, float, float, float} */
    private static function mix(string $body, string $context): array
    {
        $parts = [];
        $depth = 0;
        $start = 0;
        for ($index = 0; $index < strlen($body); $index++) {
            if ($body[$index] === '(') {
                $depth++;
            } elseif ($body[$index] === ')') {
                $depth--;
            } elseif ($body[$index] === ',' && $depth === 0) {
                $parts[] = trim(substr($body, $start, $index - $start));
                $start = $index + 1;
            }
        }
        $parts[] = trim(substr($body, $start));
        if (count($parts) !== 3 || preg_match('/^in\s+([a-z0-9-]+)(?:\s+(?:shorter|longer|increasing|decreasing)\s+hue)?$/D', $parts[0], $space) !== 1) {
            throw new InvalidArgumentException("{$context} color-mix() requires 'in <space>, <color> [%], <color> [%]'.");
        }
        $colors = [];
        $weights = [];
        foreach ([$parts[1], $parts[2]] as $part) {
            if (preg_match('/^(.*?)\s+((?:\d+|\d*\.\d+)%)$/Ds', $part, $match) === 1) {
                $colors[] = trim($match[1]);
                $weights[] = (float) substr($match[2], 0, -1) / 100;
            } elseif (preg_match('/^((?:\d+|\d*\.\d+)%)\s+(.+)$/Ds', $part, $match) === 1) {
                $colors[] = trim($match[2]);
                $weights[] = (float) substr($match[1], 0, -1) / 100;
            } else {
                $colors[] = $part;
                $weights[] = null;
            }
        }
        [$first, $second] = $weights;
        if ($first === null && $second === null) {
            [$first, $second] = [0.5, 0.5];
        } elseif ($first === null) {
            $first = 1 - $second;
        } elseif ($second === null) {
            $second = 1 - $first;
        }
        $total = $first + $second;
        if ($total <= 0) {
            throw new InvalidArgumentException("{$context} color-mix() percentages must not sum to zero.");
        }
        $alphaMultiplier = min(1.0, $total);
        [$first, $second] = [$first / $total, $second / $total];
        $linear = array_map(static function (string $color) use ($context): array {
            $argb = self::parse($color, $context);
            return [
                self::decode((($argb >> 16) & 0xFF) / 255),
                self::decode((($argb >> 8) & 0xFF) / 255),
                self::decode(($argb & 0xFF) / 255),
                (($argb >> 24) & 0xFF) / 255,
            ];
        }, $colors);
        $alpha = $linear[0][3] * $first + $linear[1][3] * $second;
        $convert = match ($space[1]) {
            'srgb' => [static fn (array $c): array => [self::encode($c[0]), self::encode($c[1]), self::encode($c[2])], static fn (array $c): array => [self::decode($c[0]), self::decode($c[1]), self::decode($c[2])]],
            'srgb-linear' => [static fn (array $c): array => [$c[0], $c[1], $c[2]], static fn (array $c): array => $c],
            'oklab', 'oklch' => [static fn (array $c): array => self::srgbLinearToOklab($c[0], $c[1], $c[2]), static fn (array $c): array => self::oklabToSrgbLinear($c[0], $c[1], $c[2])],
            default => throw new InvalidArgumentException("{$context} color-mix() supports srgb, srgb-linear, oklab and oklch."),
        };
        $a = $convert[0]($linear[0]);
        $b = $convert[0]($linear[1]);
        if ($space[1] === 'oklch') {
            // Interpolate lightness/chroma/hue (shorter arc) like CSS.
            $polar = static fn (array $lab): array => [$lab[0], sqrt($lab[1] ** 2 + $lab[2] ** 2), atan2($lab[2], $lab[1])];
            $pa = $polar($a);
            $pb = $polar($b);
            $delta = $pb[2] - $pa[2];
            if ($delta > M_PI) {
                $delta -= 2 * M_PI;
            } elseif ($delta < -M_PI) {
                $delta += 2 * M_PI;
            }
            $weightA = $linear[0][3] * $first;
            $weightB = $linear[1][3] * $second;
            $sum = max($weightA + $weightB, 1e-9);
            $lightness = ($pa[0] * $weightA + $pb[0] * $weightB) / $sum;
            $chroma = ($pa[1] * $weightA + $pb[1] * $weightB) / $sum;
            $hue = $pa[2] + $delta * $second;
            $mixed = [$lightness, $chroma * cos($hue), $chroma * sin($hue)];
        } else {
            // Premultiplied interpolation.
            $mixed = [];
            for ($channel = 0; $channel < 3; $channel++) {
                $mixed[] = $alpha > 0
                    ? ($a[$channel] * $linear[0][3] * $first + $b[$channel] * $linear[1][3] * $second) / $alpha
                    : 0.0;
            }
        }
        $result = $convert[1]($mixed);

        return [$result[0], $result[1], $result[2], $alpha * $alphaMultiplier];
    }

    private static function decode(float $value): float
    {
        $sign = $value < 0 ? -1 : 1;
        $absolute = abs($value);

        return $absolute <= 0.04045 ? $value / 12.92 : $sign * (($absolute + 0.055) / 1.055) ** 2.4;
    }

    private static function encode(float $value): float
    {
        $value = max(0.0, min(1.0, $value));

        return $value <= 0.0031308 ? 12.92 * $value : 1.055 * $value ** (1 / 2.4) - 0.055;
    }

    /** @return array{float, float, float} */
    private static function hslToRgb(float $hue, float $saturation, float $lightness): array
    {
        $chroma = (1 - abs(2 * $lightness - 1)) * $saturation;
        $segment = $hue / 60;
        $x = $chroma * (1 - abs(fmod($segment, 2) - 1));
        [$red, $green, $blue] = match ((int) floor($segment) % 6) {
            0 => [$chroma, $x, 0.0],
            1 => [$x, $chroma, 0.0],
            2 => [0.0, $chroma, $x],
            3 => [0.0, $x, $chroma],
            4 => [$x, 0.0, $chroma],
            default => [$chroma, 0.0, $x],
        };
        $offset = $lightness - $chroma / 2;

        return [$red + $offset, $green + $offset, $blue + $offset];
    }

    /** @return array{float, float, float} */
    private static function labToSrgbLinear(float $lightness, float $a, float $b): array
    {
        $epsilon = 216 / 24389;
        $kappa = 24389 / 27;
        $fy = ($lightness + 16) / 116;
        $fx = $a / 500 + $fy;
        $fz = $fy - $b / 200;
        $x = ($fx ** 3 > $epsilon ? $fx ** 3 : (116 * $fx - 16) / $kappa) * 0.3457 / 0.3585;
        $y = $lightness > $kappa * $epsilon ? $fy ** 3 : $lightness / $kappa;
        $z = ($fz ** 3 > $epsilon ? $fz ** 3 : (116 * $fz - 16) / $kappa) * (1 - 0.3457 - 0.3585) / 0.3585;
        // Bradford D50 -> D65, then XYZ D65 -> linear sRGB.
        $x65 = 0.955473421488075 * $x - 0.02309845494876471 * $y + 0.06325924320057072 * $z;
        $y65 = -0.0283697093338637 * $x + 1.0099953980813041 * $y + 0.021041441191917323 * $z;
        $z65 = 0.012314014864481998 * $x - 0.020507649298898964 * $y + 1.330365926242124 * $z;

        return [
            3.2409699419045226 * $x65 - 1.537383177570094 * $y65 - 0.4986107602930034 * $z65,
            -0.9692436362808796 * $x65 + 1.8759675015077202 * $y65 + 0.04155505740717559 * $z65,
            0.05563007969699366 * $x65 - 0.20397695888897652 * $y65 + 1.0569715142428786 * $z65,
        ];
    }

    /** @return array{float, float, float} */
    private static function oklabToSrgbLinear(float $lightness, float $a, float $b): array
    {
        $l = ($lightness + 0.3963377774 * $a + 0.2158037573 * $b) ** 3;
        $m = ($lightness - 0.1055613458 * $a - 0.0638541728 * $b) ** 3;
        $s = ($lightness - 0.0894841775 * $a - 1.2914855480 * $b) ** 3;

        return [
            4.0767416621 * $l - 3.3077115913 * $m + 0.2309699292 * $s,
            -1.2684380046 * $l + 2.6097574011 * $m - 0.3413193965 * $s,
            -0.0041960863 * $l - 0.7034186147 * $m + 1.7076147010 * $s,
        ];
    }

    /** @return array{float, float, float} */
    private static function srgbLinearToOklab(float $red, float $green, float $blue): array
    {
        $l = (0.4122214708 * $red + 0.5363325363 * $green + 0.0514459929 * $blue) ** (1 / 3);
        $m = (0.2119034982 * $red + 0.6806995451 * $green + 0.1073969566 * $blue) ** (1 / 3);
        $s = (0.0883024619 * $red + 0.2817188376 * $green + 0.6299787005 * $blue) ** (1 / 3);

        return [
            0.2104542553 * $l + 0.7936177850 * $m - 0.0040720468 * $s,
            1.9779984951 * $l - 2.4285922050 * $m + 0.4505937099 * $s,
            0.0259040371 * $l + 0.7827717662 * $m - 0.8086757660 * $s,
        ];
    }

    /** @return array{float, float, float} */
    private static function p3ToSrgbLinear(float $red, float $green, float $blue): array
    {
        return [
            1.2249401762805598 * $red - 0.22494017628055996 * $green,
            -0.042056954709688163 * $red + 1.0420569547096881 * $green,
            -0.019637554590334432 * $red - 0.07863604555063189 * $green + 1.0982736001409663 * $blue,
        ];
    }

    private static function hex(string $raw, string $context): int
    {
        $hex = substr($raw, 1);
        if (preg_match('/^[0-9a-f]+$/D', $hex) !== 1) {
            throw new InvalidArgumentException(
                "{$context} has an invalid CSS hex color.",
            );
        }

        return match (strlen($hex)) {
            3 => self::argb(
                255,
                hexdec($hex[0].$hex[0]),
                hexdec($hex[1].$hex[1]),
                hexdec($hex[2].$hex[2]),
            ),
            4 => self::argb(
                hexdec($hex[3].$hex[3]),
                hexdec($hex[0].$hex[0]),
                hexdec($hex[1].$hex[1]),
                hexdec($hex[2].$hex[2]),
            ),
            6 => self::argb(255, ...self::rgbFromHex($hex)),
            8 => self::argb(
                hexdec(substr($hex, 6, 2)),
                hexdec(substr($hex, 0, 2)),
                hexdec(substr($hex, 2, 2)),
                hexdec(substr($hex, 4, 2)),
            ),
            default => throw new InvalidArgumentException(
                "{$context} has an invalid CSS hex color.",
            ),
        };
    }

    private static function rgbFunction(string $body, string $context): int
    {
        [$channels, $alpha] = self::functionalParts($body, $context);
        if (count($channels) !== 3) {
            throw new InvalidArgumentException("{$context} rgb() requires three channels.");
        }
        $rgb = array_map(
            static function (string $channel) use ($context): int {
                if (str_ends_with($channel, '%')) {
                    return self::byte(self::number(substr($channel, 0, -1), $context) * 2.55);
                }

                return self::byte(self::number($channel, $context));
            },
            $channels,
        );

        return self::argb(self::alpha($alpha, $context), $rgb[0], $rgb[1], $rgb[2]);
    }

    private static function hslFunction(string $body, string $context): int
    {
        [$channels, $alpha] = self::functionalParts($body, $context);
        if (count($channels) === 3 && !str_contains($body, ',')) {
            $channels = array_map(
                static fn (string $channel): string => $channel === 'none' ? '0%' : (str_ends_with($channel, '%') ? $channel : $channel.'%'),
                $channels,
            );
            $channels[0] = rtrim($channels[0], '%');
        }
        if (
            count($channels) !== 3
            || !str_ends_with($channels[1], '%')
            || !str_ends_with($channels[2], '%')
        ) {
            throw new InvalidArgumentException(
                "{$context} hsl() requires a hue and two percentage channels.",
            );
        }
        $hue = self::hue($channels[0], $context);
        $saturation = self::unit(
            self::number(substr($channels[1], 0, -1), $context) / 100,
        );
        $lightness = self::unit(
            self::number(substr($channels[2], 0, -1), $context) / 100,
        );
        $chroma = (1 - abs(2 * $lightness - 1)) * $saturation;
        $segment = $hue / 60;
        $x = $chroma * (1 - abs(fmod($segment, 2) - 1));
        [$red, $green, $blue] = match ((int) floor($segment) % 6) {
            0 => [$chroma, $x, 0.0],
            1 => [$x, $chroma, 0.0],
            2 => [0.0, $chroma, $x],
            3 => [0.0, $x, $chroma],
            4 => [$x, 0.0, $chroma],
            default => [$chroma, 0.0, $x],
        };
        $match = $lightness - $chroma / 2;

        return self::argb(
            self::alpha($alpha, $context),
            self::byte(($red + $match) * 255),
            self::byte(($green + $match) * 255),
            self::byte(($blue + $match) * 255),
        );
    }

    /**
     * @return array{list<string>, ?string}
     */
    private static function functionalParts(string $body, string $context): array
    {
        $value = trim($body);
        $alpha = null;
        if (str_contains($value, '/')) {
            $parts = explode('/', $value);
            if (count($parts) !== 2) {
                throw new InvalidArgumentException("{$context} has an invalid alpha channel.");
            }
            [$value, $alpha] = array_map('trim', $parts);
        }
        $commaSyntax = str_contains($value, ',');
        $channels = $commaSyntax
            ? array_map('trim', explode(',', $value))
            : (preg_split('/\s+/', $value) ?: []);
        if ($commaSyntax && $alpha === null && count($channels) === 4) {
            $alpha = array_pop($channels);
        }
        if (in_array('', $channels, true)) {
            throw new InvalidArgumentException("{$context} has an empty color channel.");
        }

        return [array_values($channels), $alpha];
    }

    private static function hue(string $value, string $context): float
    {
        $raw = trim($value);
        $factor = 1.0;
        foreach (['turn' => 360.0, 'grad' => 0.9, 'rad' => 180 / M_PI, 'deg' => 1.0] as $unit => $next) {
            if (str_ends_with($raw, $unit)) {
                $raw = substr($raw, 0, -strlen($unit));
                $factor = $next;
                break;
            }
        }
        $degrees = fmod(self::number($raw, $context) * $factor, 360.0);

        return $degrees < 0 ? $degrees + 360.0 : $degrees;
    }

    private static function alpha(?string $value, string $context): int
    {
        if ($value === null || $value === '') {
            return 255;
        }
        $alpha = str_ends_with($value, '%')
            ? self::number(substr($value, 0, -1), $context) / 100
            : self::number($value, $context);

        return self::byte(self::unit($alpha) * 255);
    }

    private static function number(string $value, string $context): float
    {
        if (!is_numeric(trim($value))) {
            throw new InvalidArgumentException("{$context} contains a non-numeric color channel.");
        }

        return (float) $value;
    }

    private static function unit(float $value): float
    {
        return max(0.0, min(1.0, $value));
    }

    private static function byte(float|int $value): int
    {
        return (int) round(max(0.0, min(255.0, (float) $value)));
    }

    /** @return array{int, int, int} */
    private static function rgbFromHex(string $hex): array
    {
        return [
            hexdec(substr($hex, 0, 2)),
            hexdec(substr($hex, 2, 2)),
            hexdec(substr($hex, 4, 2)),
        ];
    }

    private static function argb(int $alpha, int $red, int $green, int $blue): int
    {
        return ($alpha << 24) | ($red << 16) | ($green << 8) | $blue;
    }
}

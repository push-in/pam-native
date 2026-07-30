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

        throw new InvalidArgumentException(
            "{$context} must be a CSS named, hex, rgb(), hsl(), or transparent color.",
        );
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

<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use InvalidArgumentException;
use JsonException;
use Pam\Native\PropKey;
use RuntimeException;

/**
 * Font icons with react-native-vector-icons semantics: one glyph of a bundled
 * icon font rendered as text, so it stays crisp at any size, takes any color
 * and is measured exactly like React Native's `<Icon>` (a `Text` with the
 * icon font, `allowFontScaling={false}`, default size 12 and black).
 *
 * Fonts are resolved from `register()` or by convention from the project:
 * `assets/fonts/{Font}.ttf` plus the react-native-vector-icons glyph map
 * `assets/fonts/{Font}.json` (`{"name": codepoint}`).
 */
final class Icon
{
    /** @var array<string, array{source: string, glyphs: array<string, int>|null, map: string|null}> */
    private static array $fonts = [];

    private function __construct()
    {
    }

    /**
     * @param string $source packaged font, e.g. `asset://assets/fonts/Ionicons.ttf`
     * @param string|array<string, int> $glyphMap project-relative JSON path or name => codepoint
     */
    public static function register(string $font, string $source, string|array $glyphMap): void
    {
        self::assertFontName($font);
        if (preg_match('/^asset:\/\/[A-Za-z0-9_.\/-]+\.(?:ttf|otf)$/Di', $source) !== 1 || str_contains($source, '..')) {
            throw new InvalidArgumentException('Icon fonts must be packaged asset://…ttf|otf files.');
        }
        self::$fonts[$font] = [
            'source' => $source,
            'glyphs' => is_array($glyphMap) ? self::validatedGlyphs($glyphMap, $font) : null,
            'map' => is_string($glyphMap) ? $glyphMap : null,
        ];
    }

    public static function make(
        string $name,
        string $font = 'Ionicons',
        float $size = 12.0,
        int $color = 0xFF000000,
    ): Text {
        $definition = self::font($font);
        $codepoint = self::glyphs($font)[$name] ?? null;

        return Text::make($codepoint === null ? '?' : self::utf8($codepoint))
            ->property(PropKey::FontFamily, $definition['source'])
            ->property(PropKey::FontSize, $size > 0 ? $size : 12.0)
            ->property(PropKey::TextColor, $color)
            ->property(PropKey::TextAllowFontScaling, false);
    }

    private static function utf8(int $codepoint): string
    {
        return match (true) {
            $codepoint < 0x80 => chr($codepoint),
            $codepoint < 0x800 => chr(0xC0 | ($codepoint >> 6)).chr(0x80 | ($codepoint & 0x3F)),
            $codepoint < 0x10000 => chr(0xE0 | ($codepoint >> 12)).chr(0x80 | (($codepoint >> 6) & 0x3F)).chr(0x80 | ($codepoint & 0x3F)),
            default => chr(0xF0 | ($codepoint >> 18)).chr(0x80 | (($codepoint >> 12) & 0x3F))
                .chr(0x80 | (($codepoint >> 6) & 0x3F)).chr(0x80 | ($codepoint & 0x3F)),
        };
    }

    public static function has(string $name, string $font = 'Ionicons'): bool
    {
        return isset(self::glyphs($font)[$name]);
    }

    /** @return array{source: string, glyphs: array<string, int>|null, map: string|null} */
    private static function font(string $font): array
    {
        self::assertFontName($font);
        if (!isset(self::$fonts[$font])) {
            foreach (['ttf', 'otf'] as $extension) {
                if (is_file(self::projectPath("assets/fonts/{$font}.{$extension}"))) {
                    self::$fonts[$font] = [
                        'source' => "asset://assets/fonts/{$font}.{$extension}",
                        'glyphs' => null,
                        'map' => "assets/fonts/{$font}.json",
                    ];
                    break;
                }
            }
        }

        return self::$fonts[$font] ?? throw new RuntimeException(
            "Unknown icon font {$font}: add assets/fonts/{$font}.ttf and {$font}.json or call Icon::register().",
        );
    }

    /** @return array<string, int> */
    private static function glyphs(string $font): array
    {
        $definition = self::font($font);
        if ($definition['glyphs'] !== null) {
            return $definition['glyphs'];
        }
        $path = self::projectPath((string) $definition['map']);
        $json = is_file($path) ? file_get_contents($path) : false;
        if (!is_string($json)) {
            throw new RuntimeException("Missing icon glyph map {$definition['map']} for {$font}.");
        }
        try {
            $decoded = json_decode($json, true, 4, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new RuntimeException("Invalid icon glyph map for {$font}.", previous: $error);
        }
        if (!is_array($decoded)) {
            throw new RuntimeException("Invalid icon glyph map for {$font}.");
        }
        self::$fonts[$font]['glyphs'] = self::validatedGlyphs($decoded, $font);

        return self::$fonts[$font]['glyphs'];
    }

    /**
     * @param array<mixed> $glyphs
     * @return array<string, int>
     */
    private static function validatedGlyphs(array $glyphs, string $font): array
    {
        $validated = [];
        foreach ($glyphs as $name => $codepoint) {
            if (!is_string($name) || !is_int($codepoint) || $codepoint < 0x20 || $codepoint > 0x10FFFF) {
                throw new InvalidArgumentException("Invalid glyph entry in the {$font} icon map.");
            }
            $validated[$name] = $codepoint;
        }

        return $validated;
    }

    private static function projectPath(string $relative): string
    {
        if ($relative === '' || str_starts_with($relative, '/') || str_contains($relative, '..')) {
            throw new InvalidArgumentException('Icon glyph maps must be project-relative paths.');
        }

        return (getcwd() ?: '.').'/'.$relative;
    }

    private static function assertFontName(string $font): void
    {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_-]{0,63}$/D', $font) !== 1) {
            throw new InvalidArgumentException("Invalid icon font name {$font}.");
        }
    }
}

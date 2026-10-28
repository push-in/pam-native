<?php

declare(strict_types=1);

namespace Pam\Native\I18n;

use Closure;
use InvalidArgumentException;

/**
 * Message catalogs and `t()`.
 *
 * - Keys are `namespace.key`. A catalog maps namespaces to flat
 *   `key => text` maps.
 * - Only the active locale and the source locale (the fallback) are ever
 *   loaded; a catalog may be an array or a loader closure.
 * - `{name}` placeholders are interpolated. Numbers are grouped for the locale.
 * - A numeric `count` picks `_zero` (only for 0, when present), then `_one` or
 *   `_other`.
 * - A missing key returns the key itself.
 *
 * The active locale is chosen once per run, as the app's language preference
 * or the system language ({@see self::resolve()}). Changing the language is
 * expected to restart the UI, so rendered copy never keeps the old one.
 */
final class I18n
{
    /** @var array<string, array<string, array<string, string>>|Closure(): array<string, array<string, string>>> */
    private static array $catalogs = [];
    /** @var array<string, array<string, array<string, string>>> */
    private static array $loaded = [];
    /** @var array<string, Closure(float|int): string> */
    private static array $pluralRules = [];
    private static string $source = 'en';
    private static ?string $locale = null;

    private function __construct()
    {
    }

    /**
     * Registers the catalog of a locale (language subtag, e.g. `pt`).
     *
     * @param array<string, array<string, string>>|Closure(): array<string, array<string, string>> $catalog
     */
    public static function catalog(string $locale, array|Closure $catalog): void
    {
        $locale = self::language($locale);
        self::$catalogs[$locale] = $catalog;
        unset(self::$loaded[$locale]);
    }

    /** The locale whose copy is the source of truth and the fallback of every other one. */
    public static function source(string $locale): void
    {
        self::$source = self::language($locale);
    }

    public static function sourceLocale(): string
    {
        return self::$source;
    }

    /** @return list<string> registered locales, source first */
    public static function locales(): array
    {
        $locales = array_keys(self::$catalogs);
        usort($locales, static fn (string $a, string $b): int => ($b === self::$source) <=> ($a === self::$source));

        return $locales;
    }

    public static function use(string $locale): void
    {
        self::$locale = self::resolve($locale);
    }

    public static function locale(): string
    {
        return self::$locale ?? self::$source;
    }

    /**
     * Picks a registered locale for a preference or a system language tag
     * ("pt_BR", "pt-PT", "es-419", "fr-CA"): the first registered match of its
     * language subtag, else the source locale.
     */
    public static function resolve(?string ...$tags): string
    {
        foreach ($tags as $tag) {
            $language = self::language((string) $tag);
            if ($language !== '' && isset(self::$catalogs[$language])) {
                return $language;
            }
        }

        return self::$source;
    }

    /**
     * Plural category (`one` | `other`) of a count. Default rule: exactly 1 is
     * `one`; French-style languages register `n < 2`.
     *
     * @param Closure(float|int): string $rule
     */
    public static function pluralRule(string $locale, Closure $rule): void
    {
        self::$pluralRules[self::language($locale)] = $rule;
    }

    public static function pluralCategory(float|int $count, ?string $locale = null): string
    {
        $locale ??= self::locale();
        $rule = self::$pluralRules[$locale] ?? null;
        if ($rule !== null) {
            return $rule($count);
        }
        $n = abs($count);
        if ($locale === 'fr') {
            return $n < 2 ? 'one' : 'other';
        }

        return $n == 1 ? 'one' : 'other';
    }

    /** @param array<string, scalar|null> $params */
    public static function t(string $key, array $params = []): string
    {
        return self::translateIn(self::locale(), $key, $params);
    }

    /** True when the key (or one of its plural forms) exists in the active or source catalog. */
    public static function has(string $key): bool
    {
        foreach ([self::locale(), self::$source] as $locale) {
            if (self::lookup($locale, $key) !== null || self::lookup($locale, $key.'_other') !== null) {
                return true;
            }
        }

        return false;
    }

    /** @param array<string, scalar|null> $params */
    public static function translateIn(string $locale, string $key, array $params = []): string
    {
        $locale = self::language($locale);
        $text = self::find($locale, $key, $params);
        if ($text === null && $locale !== self::$source) {
            $text = self::find(self::$source, $key, $params);
        }

        return $text === null ? $key : self::interpolate($text, $params, $locale);
    }

    /** Pipe-separated list entries (month names, weekday initials…). @return list<string> */
    public static function list(string $key): array
    {
        return explode('|', self::t($key));
    }

    /** Locale grouping: pt "1.234", en "1,234", es "1234" / "12.345", fr "1 234". */
    public static function formatNumber(float|int $value, ?string $locale = null, int $decimals = 0): string
    {
        $locale ??= self::locale();
        [$thousands, $decimal, $minimumGroup] = match ($locale) {
            'pt', 'de', 'it', 'nl' => ['.', ',', 4],
            'es' => ['.', ',', 5],
            'fr' => ["\u{202F}", ',', 4],
            default => [',', '.', 4],
        };
        $negative = $value < 0;
        $formatted = number_format(abs($value), $decimals, $decimal, '');
        [$integer, $fraction] = array_pad(explode($decimal, $formatted, 2), 2, null);
        if (strlen($integer) >= $minimumGroup) {
            $integer = (string) preg_replace('/\B(?=(\d{3})+(?!\d))/', $thousands, $integer);
        }

        return ($negative ? '-' : '').$integer.($fraction === null ? '' : $decimal.$fraction);
    }

    /** @internal Tests and hot reload. */
    public static function reset(): void
    {
        self::$catalogs = [];
        self::$loaded = [];
        self::$pluralRules = [];
        self::$source = 'en';
        self::$locale = null;
    }

    /** @param array<string, scalar|null> $params */
    private static function find(string $locale, string $key, array $params): ?string
    {
        $count = $params['count'] ?? null;
        if (is_int($count) || is_float($count)) {
            if ($count == 0 && ($zero = self::lookup($locale, $key.'_zero')) !== null) {
                return $zero;
            }
            $plural = self::lookup($locale, $key.'_'.self::pluralCategory($count, $locale)) ?? self::lookup($locale, $key.'_other');
            if ($plural !== null) {
                return $plural;
            }
        }

        return self::lookup($locale, $key);
    }

    private static function lookup(string $locale, string $key): ?string
    {
        $dot = strpos($key, '.');
        if ($dot === false || $dot === 0) {
            return null;
        }
        $messages = self::messages($locale);
        $value = $messages[substr($key, 0, $dot)][substr($key, $dot + 1)] ?? null;

        return is_string($value) ? $value : null;
    }

    /** @return array<string, array<string, string>> */
    private static function messages(string $locale): array
    {
        if (isset(self::$loaded[$locale])) {
            return self::$loaded[$locale];
        }
        $catalog = self::$catalogs[$locale] ?? [];
        if ($catalog instanceof Closure) {
            $catalog = $catalog();
            if (!is_array($catalog)) {
                throw new InvalidArgumentException("The {$locale} catalog loader must return an array.");
            }
        }

        return self::$loaded[$locale] = $catalog;
    }

    /** @param array<string, scalar|null> $params */
    private static function interpolate(string $text, array $params, string $locale): string
    {
        if ($params === [] || !str_contains($text, '{')) {
            return $text;
        }

        return (string) preg_replace_callback('/\{(\w+)\}/', static function (array $match) use ($params, $locale): string {
            $value = $params[$match[1]] ?? null;
            if ($value === null) {
                return $match[0];
            }
            if (is_int($value) || (is_float($value) && floor($value) === $value)) {
                return self::formatNumber($value, $locale);
            }
            if (is_bool($value)) {
                return $value ? 'true' : 'false';
            }

            return (string) $value;
        }, $text);
    }

    private static function language(string $tag): string
    {
        $parts = preg_split('/[-_@.]/', strtolower(trim($tag))) ?: [''];

        return $parts[0];
    }
}

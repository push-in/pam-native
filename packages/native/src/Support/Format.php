<?php

declare(strict_types=1);

namespace Pam\Native\Support;

use DateTimeImmutable;
use DateTimeInterface;
use DateTimeZone;
use InvalidArgumentException;

use function is_int;
use function in_array;
use function is_string;

/**
 * Locale-aware display formatting for Portuguese, English, Spanish and French
 * without ext-intl.
 *
 *     Format::count(12345);                     // "12.345"
 *     Format::compact(1234);                    // "1,2 mil"   (en: "1.2K")
 *     Format::relative($createdAt);             // "há 5 min"  (en: "5m ago")
 *     Format::bytes(1_572_864);                 // "1,5 MB"
 *     Format::duration(65);                     // "1:05"
 *     Format::time($sentAt, 'America/Sao_Paulo'); // "14:05"  (en: "2:05 PM")
 *     Format::date($sentAt, DateStyle::Medium);   // "12 de out." (en: "Oct 12")
 *
 * The locale is the one given per call, else Format::useLocale(), else the
 * active I18n locale when the app registered catalogs, else pt-BR. Tags are
 * matched by language: "pt…", "es…", "fr…"; anything else is English.
 * Spanish and French use 24 h clocks, day/month dates and their own grouping
 * (es "12.345" from five digits, fr "12 345" with a narrow no-break space).
 */
final class Format
{
    private const PT_MONTHS_SHORT = ['jan.', 'fev.', 'mar.', 'abr.', 'mai.', 'jun.', 'jul.', 'ago.', 'set.', 'out.', 'nov.', 'dez.'];
    private const PT_MONTHS_LONG = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'];
    private const EN_MONTHS_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    private const EN_MONTHS_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
    private const ES_MONTHS_SHORT = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sept', 'oct', 'nov', 'dic'];
    private const ES_MONTHS_LONG = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];
    private const FR_MONTHS_SHORT = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];
    private const FR_MONTHS_LONG = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
    private const FR_GROUP = "\u{202F}";

    private static ?string $locale = null;

    private function __construct()
    {
    }

    /** Fixes the default locale; null follows I18n again (pt-BR without catalogs). */
    public static function useLocale(?string $locale): void
    {
        if ($locale === '') {
            throw new InvalidArgumentException('Locale must not be empty.');
        }
        self::$locale = $locale;
    }

    /** The default locale: useLocale(), else the active I18n locale when catalogs exist, else pt-BR. */
    public static function locale(): string
    {
        if (self::$locale !== null) {
            return self::$locale;
        }

        return \Pam\Native\I18n\I18n::locales() !== [] ? \Pam\Native\I18n\I18n::locale() : 'pt-BR';
    }

    /** Integer with thousands separators: "12.345" / "12,345" / es "1234", "12.345" / fr "12 345". */
    public static function count(int|float $value, ?string $locale = null): string
    {
        return self::group(number_format(abs((float) round($value)), 0, '.', ''), self::language($locale), (float) round($value) < 0);
    }

    /** "1 item" / "1.234 itens": count() plus singular or plural noun. */
    public static function countLabel(int $value, string $singular, string $plural, ?string $locale = null): string
    {
        return self::count($value, $locale).' '.($value === 1 ? $singular : $plural);
    }

    /**
     * Short magnitude: "1,2 mil", "12 mil", "3,4 mi", "1,2 bi" (en "1.2K",
     * "12K", "3.4M", "1.2B"). Values below $plainBelow use count(). One
     * decimal is shown while the scaled value is below $decimalsBelow
     * (10 → "12 mil"; 1000 → "12,3 mil"); a trailing ",0" is dropped.
     * $truncate floors instead of rounding, so 1,99 mil never shows "2 mil".
     */
    public static function compact(
        int|float $value,
        ?string $locale = null,
        bool $truncate = false,
        int $plainBelow = 1_000,
        int $decimalsBelow = 10,
    ): string {
        $language = self::language($locale);
        $sign = $value < 0 ? '-' : '';
        $absolute = abs((float) $value);
        if ($absolute < max(1_000, $plainBelow)) {
            return $sign.self::count($truncate ? floor($absolute) : $absolute, $locale);
        }
        $units = match ($language) {
            'pt' => [' mil', ' mi', ' bi', ' tri'],
            'es' => [' mil', ' M', ' mil M', ' B'],
            'fr' => [' k', ' M', ' Md', ' Bn'],
            default => ['K', 'M', 'B', 'T'],
        };
        $comma = $language !== 'en';
        $unit = 0;
        $scaled = $absolute / 1_000;
        while ($scaled >= 1_000 && $unit < 3) {
            $scaled /= 1_000;
            ++$unit;
        }
        $decimals = $scaled < $decimalsBelow ? 1 : 0;
        $factor = $decimals === 1 ? 10 : 1;
        $shown = $truncate ? floor($scaled * $factor + 1e-9) / $factor : round($scaled * $factor) / $factor;
        if ($shown >= 1_000 && $unit < 3) {
            $shown = round($shown / 1_000 * 10) / 10;
            ++$unit;
            $decimals = 1;
        }
        $text = number_format($shown, $decimals, $comma ? ',' : '.', '');
        if ($decimals === 1) {
            $text = rtrim(rtrim($text, '0'), $comma ? ',' : '.');
        }

        return $sign.$text.$units[$unit];
    }

    /**
     * Elapsed time since $time: "agora", "há 5 min", "há 3 h", "há 2 d",
     * "há 2 sem", then a calendar date ("12 out.", with the year when it
     * differs; $dateStyle picks another wording). Days are used up to
     * $maxDays and weeks up to $maxWeeks (0 jumps from days to the date).
     * Future times read as "agora". Invalid times or zones give ''.
     *
     * @param int|string|DateTimeInterface $time Unix seconds, ISO-8601 string or date
     * @param int|null $now Unix seconds; defaults to time()
     */
    public static function relative(
        int|string|DateTimeInterface $time,
        ?int $now = null,
        RelativeTimeStyle $style = RelativeTimeStyle::Short,
        ?string $locale = null,
        int $maxWeeks = 4,
        DateTimeZone|string|null $timeZone = null,
        int $maxDays = 6,
        ?DateStyle $dateStyle = null,
        bool $padDay = false,
        ?bool $withYear = null,
    ): string {
        $timestamp = self::timestamp($time);
        if ($timestamp === null) {
            return '';
        }
        $language = self::language($locale);
        $now ??= time();
        $elapsed = max(0, $now - $timestamp);
        if ($elapsed < 60) {
            return match ($language) {
                'pt' => 'agora',
                'es' => 'ahora',
                'fr' => "à l'instant",
                default => 'now',
            };
        }
        $dayLimit = $maxDays >= PHP_INT_MAX / 86_400 - 1 ? PHP_INT_MAX : ($maxDays + 1) * 86_400;
        $steps = [[3_600, 60, 'min'], [86_400, 3_600, 'h'], [max(86_400, $dayLimit), 86_400, 'd']];
        if ($maxWeeks > 0 && $dayLimit !== PHP_INT_MAX) {
            $steps[] = [max($dayLimit, ($maxWeeks + 1) * 604_800), 604_800, 'w'];
        }
        foreach ($steps as [$limit, $unitSeconds, $unit]) {
            if ($elapsed < $limit) {
                return self::elapsed(intdiv($elapsed, $unitSeconds), $unit, $style, $language);
            }
        }

        return self::date(
            $timestamp,
            $dateStyle ?? ($style === RelativeTimeStyle::Long ? DateStyle::Long : DateStyle::Short),
            $timeZone,
            $locale,
            $withYear,
            $now,
            $padDay,
        );
    }

    /**
     * Calendar date: "12 out.", "12 de out.", "12 de outubro", "12/10",
     * "12/10/2025" (en "Oct 12", "October 12", "10/12"). The year is added
     * when it differs from $now's year unless $withYear says otherwise;
     * $padDay writes "05 de out.".
     *
     * @param int|null $now Unix seconds; defaults to time()
     */
    public static function date(
        int|string|DateTimeInterface $time,
        DateStyle $style = DateStyle::Medium,
        DateTimeZone|string|null $timeZone = null,
        ?string $locale = null,
        ?bool $withYear = null,
        ?int $now = null,
        bool $padDay = false,
    ): string {
        $date = self::moment($time, $timeZone);
        $today = self::moment($now ?? time(), $timeZone);
        if ($date === null || $today === null) {
            return '';
        }
        $language = self::language($locale);
        $dayFirst = $language !== 'en';
        $dayNumber = (int) $date->format('j');
        $day = $padDay ? str_pad((string) $dayNumber, 2, '0', STR_PAD_LEFT) : (string) $dayNumber;
        $month = (int) $date->format('n') - 1;
        $year = (int) $date->format('Y');
        $showYear = $withYear ?? ($year !== (int) $today->format('Y'));

        return match ($style) {
            DateStyle::Numeric => $dayFirst ? $date->format('d/m') : $date->format('m/d'),
            DateStyle::NumericWithYear => $dayFirst ? $date->format('d/m/Y') : $date->format('m/d/Y'),
            DateStyle::NumericWithShortYear => $dayFirst ? $date->format('d/m/y') : $date->format('m/d/y'),
            DateStyle::Long => match ($language) {
                'pt' => $day.' de '.self::PT_MONTHS_LONG[$month].($showYear ? ' de '.$year : ''),
                'es' => $day.' de '.self::ES_MONTHS_LONG[$month].($showYear ? ' de '.$year : ''),
                'fr' => $day.' '.self::FR_MONTHS_LONG[$month].($showYear ? ' '.$year : ''),
                default => self::EN_MONTHS_LONG[$month].' '.$day.($showYear ? ', '.$year : ''),
            },
            DateStyle::Medium => match ($language) {
                'pt' => $day.' de '.self::PT_MONTHS_SHORT[$month].($showYear ? ' de '.$year : ''),
                'es' => $day.' de '.self::ES_MONTHS_SHORT[$month].($showYear ? ' de '.$year : ''),
                'fr' => $day.' '.self::FR_MONTHS_SHORT[$month].($showYear ? ' '.$year : ''),
                default => self::EN_MONTHS_SHORT[$month].' '.$day.($showYear ? ', '.$year : ''),
            },
            DateStyle::Short => match ($language) {
                'pt' => $day.' '.self::PT_MONTHS_SHORT[$month].($showYear ? ' '.$year : ''),
                'es' => $day.' '.self::ES_MONTHS_SHORT[$month].($showYear ? ' '.$year : ''),
                'fr' => $day.' '.self::FR_MONTHS_SHORT[$month].($showYear ? ' '.$year : ''),
                default => self::EN_MONTHS_SHORT[$month].' '.$day.($showYear ? ', '.$year : ''),
            },
        };
    }

    /**
     * File size in binary units: "512 B", "1,5 KB", "12 MB", "1,2 GB".
     * One decimal below 10 units, trailing ",0" dropped.
     */
    public static function bytes(int $bytes, ?string $locale = null): string
    {
        $language = self::language($locale);
        $bytes = max(0, $bytes);
        if ($bytes < 1_024) {
            return $bytes.($language === 'fr' ? ' o' : ' B');
        }
        $units = $language === 'fr' ? ['Ko', 'Mo', 'Go', 'To'] : ['KB', 'MB', 'GB', 'TB'];
        $value = $bytes / 1_024;
        $unit = 0;
        while ($value >= 1_024 && $unit < 3) {
            $value /= 1_024;
            ++$unit;
        }
        if (round($value, 1) >= 1_024 && $unit < 3) {
            $value /= 1_024;
            ++$unit;
        }
        $decimals = round($value, 1) < 10 ? 1 : 0;
        $comma = $language !== 'en';
        if ($decimals === 0) {
            return self::group(number_format($value, 0, '.', ''), $language).' '.$units[$unit];
        }
        $text = rtrim(rtrim(number_format($value, 1, $comma ? ',' : '.', ''), '0'), $comma ? ',' : '.');

        return $text.' '.$units[$unit];
    }

    /**
     * Media clock: "0:07", "1:05", "1:02:03"; with $padMinutes "01:05".
     * Seconds are rounded unless $floor (elapsed playback counters).
     */
    public static function duration(float $seconds, bool $padMinutes = false, bool $floor = false): string
    {
        $total = max(0, (int) ($floor ? floor($seconds) : round($seconds)));
        $hours = intdiv($total, 3_600);
        $minutes = intdiv($total % 3_600, 60);
        $secondsPart = str_pad((string) ($total % 60), 2, '0', STR_PAD_LEFT);
        if ($hours > 0) {
            return $hours.':'.str_pad((string) $minutes, 2, '0', STR_PAD_LEFT).':'.$secondsPart;
        }

        return ($padMinutes ? str_pad((string) $minutes, 2, '0', STR_PAD_LEFT) : (string) $minutes).':'.$secondsPart;
    }

    /**
     * Date and clock time joined by $separator: "12/10/2025 14:05",
     * "12 de out., 14:05".
     */
    public static function dateTime(
        int|string|DateTimeInterface $time,
        DateStyle $style = DateStyle::NumericWithYear,
        string $separator = ' ',
        DateTimeZone|string|null $timeZone = null,
        ?string $locale = null,
        ?bool $withYear = null,
        ?int $now = null,
        bool $padDay = false,
    ): string {
        $date = self::date($time, $style, $timeZone, $locale, $withYear, $now, $padDay);

        return $date === '' ? '' : $date.$separator.self::time($time, $timeZone, $locale);
    }

    /** Clock time in $timeZone (default: the process zone): "14:05" / "2:05 PM". */
    public static function time(
        int|string|DateTimeInterface $time,
        DateTimeZone|string|null $timeZone = null,
        ?string $locale = null,
    ): string {
        $date = self::moment($time, $timeZone);
        if ($date === null) {
            return '';
        }

        return $date->format(self::language($locale) === 'en' ? 'g:i A' : 'H:i');
    }

    private static function elapsed(int $count, string $unit, RelativeTimeStyle $style, string $language): string
    {
        if ($style === RelativeTimeStyle::Long) {
            $words = match ($language) {
                'pt' => ['min' => ['minuto', 'minutos'], 'h' => ['hora', 'horas'], 'd' => ['dia', 'dias'], 'w' => ['semana', 'semanas']],
                'es' => ['min' => ['minuto', 'minutos'], 'h' => ['hora', 'horas'], 'd' => ['día', 'días'], 'w' => ['semana', 'semanas']],
                'fr' => ['min' => ['minute', 'minutes'], 'h' => ['heure', 'heures'], 'd' => ['jour', 'jours'], 'w' => ['semaine', 'semaines']],
                default => ['min' => ['minute', 'minutes'], 'h' => ['hour', 'hours'], 'd' => ['day', 'days'], 'w' => ['week', 'weeks']],
            };
            $word = $words[$unit][$count === 1 ? 0 : 1];

            return match ($language) {
                'pt' => "há {$count} {$word}",
                'es' => "hace {$count} {$word}",
                'fr' => "il y a {$count} {$word}",
                default => "{$count} {$word} ago",
            };
        }
        if ($style === RelativeTimeStyle::Tiny) {
            return $count.match (true) {
                $unit === 'min' => 'm',
                $unit === 'w' && $language !== 'en' => 'sem',
                $unit === 'd' && $language === 'fr' => 'j',
                default => $unit,
            };
        }
        if ($language === 'en') {
            $short = $count.($unit === 'min' ? 'm' : $unit);

            return $style === RelativeTimeStyle::Narrow ? $short : $short.' ago';
        }
        $short = $count.' '.match (true) {
            $unit === 'w' => $language === 'fr' ? 'sem.' : 'sem',
            $unit === 'd' && $language === 'fr' => 'j',
            default => $unit,
        };
        if ($style === RelativeTimeStyle::Narrow) {
            return $short;
        }

        return match ($language) {
            'es' => 'hace '.$short,
            'fr' => 'il y a '.$short,
            default => 'há '.$short,
        };
    }

    private static function timestamp(int|string|DateTimeInterface $time): ?int
    {
        if (is_int($time)) {
            return $time > 0 ? $time : null;
        }
        if ($time instanceof DateTimeInterface) {
            return $time->getTimestamp();
        }
        if ($time === '') {
            return null;
        }
        $parsed = strtotime($time);

        return $parsed === false || $parsed <= 0 ? null : $parsed;
    }

    private static function moment(int|string|DateTimeInterface $time, DateTimeZone|string|null $timeZone): ?DateTimeImmutable
    {
        $timestamp = self::timestamp($time);
        if ($timestamp === null) {
            return null;
        }
        try {
            $zone = is_string($timeZone) ? new DateTimeZone($timeZone) : ($timeZone ?? new DateTimeZone(date_default_timezone_get()));
        } catch (\Exception) {
            return null;
        }

        return (new DateTimeImmutable('@'.$timestamp))->setTimezone($zone);
    }

    /** "pt", "es", "fr" or "en" (any other language). */
    private static function language(?string $locale): string
    {
        $language = strtolower(substr($locale ?? self::locale(), 0, 2));

        return in_array($language, ['pt', 'es', 'fr'], true) ? $language : 'en';
    }

    /** Groups an unsigned integer string: pt/es ".", fr narrow no-break space, en ","; es only from five digits. */
    private static function group(string $digits, string $language, bool $negative = false): string
    {
        [$separator, $minimum] = match ($language) {
            'pt' => ['.', 4],
            'es' => ['.', 5],
            'fr' => [self::FR_GROUP, 4],
            default => [',', 4],
        };
        if (strlen($digits) >= $minimum) {
            $digits = (string) preg_replace('/\B(?=(\d{3})+(?!\d))/', $separator, $digits);
        }

        return ($negative ? '-' : '').$digits;
    }
}

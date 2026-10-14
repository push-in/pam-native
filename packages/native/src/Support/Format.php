<?php

declare(strict_types=1);

namespace Pam\Native\Support;

use DateTimeImmutable;
use DateTimeInterface;
use DateTimeZone;
use InvalidArgumentException;

use function is_int;
use function is_string;

/**
 * Locale-aware display formatting for pt-BR and English without ext-intl.
 *
 *     Format::count(12345);                     // "12.345"
 *     Format::compact(1234);                    // "1,2 mil"   (en: "1.2K")
 *     Format::relative($createdAt);             // "há 5 min"  (en: "5m ago")
 *     Format::bytes(1_572_864);                 // "1,5 MB"
 *     Format::duration(65);                     // "1:05"
 *     Format::time($sentAt, 'America/Sao_Paulo'); // "14:05"  (en: "2:05 PM")
 *
 * The locale defaults to pt-BR; set it once with Format::useLocale('en') or
 * pass it per call. Any "pt…" tag selects Portuguese, anything else English.
 */
final class Format
{
    private const PT_MONTHS_SHORT = ['jan.', 'fev.', 'mar.', 'abr.', 'mai.', 'jun.', 'jul.', 'ago.', 'set.', 'out.', 'nov.', 'dez.'];
    private const PT_MONTHS_LONG = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'];
    private const EN_MONTHS_SHORT = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    private const EN_MONTHS_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

    private static string $locale = 'pt-BR';

    private function __construct()
    {
    }

    public static function useLocale(string $locale): void
    {
        if ($locale === '') {
            throw new InvalidArgumentException('Locale must not be empty.');
        }
        self::$locale = $locale;
    }

    public static function locale(): string
    {
        return self::$locale;
    }

    /** Integer with thousands separators: "12.345" / "12,345". */
    public static function count(int|float $value, ?string $locale = null): string
    {
        $pt = self::portuguese($locale);

        return number_format((float) round($value), 0, $pt ? ',' : '.', $pt ? '.' : ',');
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
        $pt = self::portuguese($locale);
        $sign = $value < 0 ? '-' : '';
        $absolute = abs((float) $value);
        if ($absolute < max(1_000, $plainBelow)) {
            return $sign.self::count($truncate ? floor($absolute) : $absolute, $locale);
        }
        $units = $pt ? [' mil', ' mi', ' bi', ' tri'] : ['K', 'M', 'B', 'T'];
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
        $text = number_format($shown, $decimals, $pt ? ',' : '.', '');
        if ($decimals === 1) {
            $text = rtrim(rtrim($text, '0'), $pt ? ',' : '.');
        }

        return $sign.$text.$units[$unit];
    }

    /**
     * Elapsed time since $time: "agora", "há 5 min", "há 3 h", "há 2 d",
     * "há 2 sem", then a date ("12 de out.", with the year when it differs).
     * Weeks are used up to $maxWeeks (0 jumps from days to the date).
     * Future times read as "agora".
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
    ): string {
        $timestamp = self::timestamp($time);
        if ($timestamp === null) {
            return '';
        }
        $pt = self::portuguese($locale);
        $now ??= time();
        $elapsed = max(0, $now - $timestamp);
        if ($elapsed < 60) {
            return $pt ? 'agora' : 'now';
        }
        $steps = [[3_600, 60, 'min'], [86_400, 3_600, 'h'], [604_800, 86_400, 'd']];
        if ($maxWeeks > 0) {
            $steps[] = [($maxWeeks + 1) * 604_800, 604_800, 'w'];
        }
        foreach ($steps as [$limit, $unitSeconds, $unit]) {
            if ($elapsed < $limit) {
                return self::elapsed(intdiv($elapsed, $unitSeconds), $unit, $style, $pt);
            }
        }

        return self::calendarDate($timestamp, $now, $style, $pt, $timeZone);
    }

    /**
     * File size in binary units: "512 B", "1,5 KB", "12 MB", "1,2 GB".
     * One decimal below 10 units, trailing ",0" dropped.
     */
    public static function bytes(int $bytes, ?string $locale = null): string
    {
        $pt = self::portuguese($locale);
        $bytes = max(0, $bytes);
        if ($bytes < 1_024) {
            return $bytes.' B';
        }
        $units = ['KB', 'MB', 'GB', 'TB'];
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
        $text = number_format($value, $decimals, $pt ? ',' : '.', $pt ? '.' : ',');
        if ($decimals === 1) {
            $text = rtrim(rtrim($text, '0'), $pt ? ',' : '.');
        }

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

    /** Clock time in $timeZone (default: the process zone): "14:05" / "2:05 PM". */
    public static function time(
        int|string|DateTimeInterface $time,
        DateTimeZone|string|null $timeZone = null,
        ?string $locale = null,
    ): string {
        $date = self::date($time, $timeZone);
        if ($date === null) {
            return '';
        }

        return $date->format(self::portuguese($locale) ? 'H:i' : 'g:i A');
    }

    private static function elapsed(int $count, string $unit, RelativeTimeStyle $style, bool $pt): string
    {
        if ($style === RelativeTimeStyle::Long) {
            $words = $pt
                ? ['min' => ['minuto', 'minutos'], 'h' => ['hora', 'horas'], 'd' => ['dia', 'dias'], 'w' => ['semana', 'semanas']]
                : ['min' => ['minute', 'minutes'], 'h' => ['hour', 'hours'], 'd' => ['day', 'days'], 'w' => ['week', 'weeks']];
            $word = $words[$unit][$count === 1 ? 0 : 1];

            return $pt ? "há {$count} {$word}" : "{$count} {$word} ago";
        }
        if ($pt) {
            $short = $count.' '.($unit === 'w' ? 'sem' : $unit);

            return $style === RelativeTimeStyle::Narrow ? $short : 'há '.$short;
        }
        $short = $count.($unit === 'min' ? 'm' : $unit);

        return $style === RelativeTimeStyle::Narrow ? $short : $short.' ago';
    }

    private static function calendarDate(int $timestamp, int $now, RelativeTimeStyle $style, bool $pt, DateTimeZone|string|null $timeZone): string
    {
        $date = self::date($timestamp, $timeZone);
        $today = self::date($now, $timeZone);
        if ($date === null || $today === null) {
            return '';
        }
        $day = (int) $date->format('j');
        $month = (int) $date->format('n') - 1;
        $year = (int) $date->format('Y');
        $otherYear = $year !== (int) $today->format('Y');
        if ($style === RelativeTimeStyle::Long) {
            return $pt
                ? $day.' de '.self::PT_MONTHS_LONG[$month].($otherYear ? ' de '.$year : '')
                : self::EN_MONTHS_LONG[$month].' '.$day.($otherYear ? ', '.$year : '');
        }

        return $pt
            ? $day.' '.self::PT_MONTHS_SHORT[$month].($otherYear ? ' '.$year : '')
            : self::EN_MONTHS_SHORT[$month].' '.$day.($otherYear ? ', '.$year : '');
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

    private static function date(int|string|DateTimeInterface $time, DateTimeZone|string|null $timeZone): ?DateTimeImmutable
    {
        $timestamp = self::timestamp($time);
        if ($timestamp === null) {
            return null;
        }
        try {
            $zone = is_string($timeZone) ? new DateTimeZone($timeZone) : ($timeZone ?? new DateTimeZone(date_default_timezone_get()));
        } catch (\Exception) {
            $zone = new DateTimeZone('UTC');
        }

        return (new DateTimeImmutable('@'.$timestamp))->setTimezone($zone);
    }

    private static function portuguese(?string $locale): bool
    {
        return str_starts_with(strtolower($locale ?? self::$locale), 'pt');
    }
}

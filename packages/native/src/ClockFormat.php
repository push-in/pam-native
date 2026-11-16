<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * How a native clock text (`Text::clock()`, `Text::countdown()`,
 * `clockStartedAt`/`clockEndsAt` in templates) renders its time. A clock
 * counting up shows whole seconds elapsed (rounded down); a countdown shows
 * the seconds left rounded up, so it reads `5` at the start and `0` only at
 * the end.
 */
enum ClockFormat: int
{
    /** `0:42`, `1:01:05` past an hour (template `m:ss`). */
    case MinutesSeconds = 1;
    /** `00:42`, `75:03`: minutes keep counting past an hour (template `mm:ss`). */
    case PaddedMinutesSeconds = 2;
    /** `0:00:42` (template `h:mm:ss`). */
    case HoursMinutesSeconds = 3;
    /** Whole seconds, `42` (template `seconds`). */
    case Seconds = 4;

    /** The template spelling (`clockFormat="mm:ss"`). */
    public function templateName(): string
    {
        return match ($this) {
            self::MinutesSeconds => 'm:ss',
            self::PaddedMinutesSeconds => 'mm:ss',
            self::HoursMinutesSeconds => 'h:mm:ss',
            self::Seconds => 'seconds',
        };
    }

    /**
     * The label of [seconds] whole seconds, exactly as the native renderers
     * draw it (Android `PamClockFormat`, iOS `PamClockFormat`); negative
     * values read as zero.
     */
    public function format(int $seconds): string
    {
        $total = max(0, $seconds);
        $hours = intdiv($total, 3_600);
        $minutes = intdiv($total % 3_600, 60);
        $rest = $total % 60;

        return match ($this) {
            self::PaddedMinutesSeconds => self::twoDigits(intdiv($total, 60)).':'.self::twoDigits($rest),
            self::HoursMinutesSeconds => $hours.':'.self::twoDigits($minutes).':'.self::twoDigits($rest),
            self::Seconds => (string) $total,
            self::MinutesSeconds => $hours > 0
                ? $hours.':'.self::twoDigits($minutes).':'.self::twoDigits($rest)
                : $minutes.':'.self::twoDigits($rest),
        };
    }

    public static function fromTemplate(string $name): self
    {
        foreach (self::cases() as $case) {
            if ($case->templateName() === $name) {
                return $case;
            }
        }

        throw new \InvalidArgumentException("Unknown clock format {$name}; use m:ss, mm:ss, h:mm:ss or seconds.");
    }

    private static function twoDigits(int $value): string
    {
        return $value < 10 ? '0'.$value : (string) $value;
    }
}

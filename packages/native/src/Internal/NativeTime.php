<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use InvalidArgumentException;
use Pam\Native\ClockFormat;
use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

use function array_key_last;
use function array_pop;
use function intdiv;
use function is_bool;
use function is_finite;
use function is_float;
use function is_int;
use function strlen;

/**
 * Wire values of the native time primitives (clock texts, timed progress,
 * text reveal), shared by the element builders and templates.
 *
 * @internal
 */
final class NativeTime
{
    /** Longest `revealKey`: a restart token, not content. */
    private const int KEY_BYTES = 256;

    /**
     * How long before a clock counting up gains a character (9:59 -> 10:00)
     * the framework already renders the longer label: the engine measures it
     * before the native clock draws it, so the label is never clipped.
     */
    public const int CLOCK_GROWTH_LEAD_MS = 1_000;

    /** Beyond this many seconds (~31,700 years) a label is taken as fixed. */
    private const int MAX_CLOCK_SECONDS = 1_000_000_000_000;

    /**
     * Earliest instant (epoch ms) at which each component rendering now must
     * render again for its clock texts, innermost component last.
     *
     * @var list<?int>
     */
    private static array $renderFrames = [];

    /** @var (Closure(): int)|null */
    private static ?Closure $clock = null;

    private function __construct()
    {
    }

    /**
     * Epoch milliseconds or a duration, `>= 0`; `null`, `false` and `''`
     * remove the property (the primitive turns off).
     */
    public static function milliseconds(mixed $value, string $label): ?int
    {
        if ($value === null || $value === false || $value === '') {
            return null;
        }
        if (is_int($value)) {
            return max(0, $value);
        }
        if (is_float($value) && is_finite($value)) {
            // `microtime(true) * 1000` is a float.
            return max(0, (int) floor($value));
        }
        if (is_string($value) && preg_match('/^-?\\d+(\\.\\d+)?$/', $value) === 1) {
            return max(0, (int) floor((float) $value));
        }

        throw new InvalidArgumentException("{$label} must be a number of milliseconds.");
    }

    /** Like {@see milliseconds()}, but `0` also removes the property. */
    public static function positiveMilliseconds(mixed $value, string $label): ?int
    {
        $ms = self::milliseconds($value, $label);

        return $ms === null || $ms === 0 ? null : $ms;
    }

    public static function format(mixed $value): int
    {
        if ($value instanceof ClockFormat) {
            return $value->value;
        }
        if (is_int($value)) {
            return ClockFormat::from($value)->value;
        }
        if (is_string($value)) {
            return ClockFormat::fromTemplate($value)->value;
        }

        throw new InvalidArgumentException('clockFormat must be a ClockFormat, m:ss, mm:ss, h:mm:ss or seconds.');
    }

    /** Wall-clock epoch milliseconds, the clock the native renderers read. */
    public static function nowMs(): int
    {
        return self::$clock !== null ? (self::$clock)() : (int) floor(microtime(true) * 1_000);
    }

    /**
     * @internal Tests: replaces the wall clock (null restores it).
     * @param (Closure(): int)|null $now
     */
    public static function useClock(?Closure $now): void
    {
        self::$clock = $now;
    }

    /**
     * True when the properties turn a Text's native clock on (any of
     * `clockStartedAt`, `clockEndsAt`, `clockDurationMs`, `clockOffsetMs`).
     *
     * @param array<int, mixed> $properties
     */
    public static function hasClock(array $properties): bool
    {
        return isset($properties[PropKey::ClockStartedAt->value])
            || isset($properties[PropKey::ClockEndsAt->value])
            || isset($properties[PropKey::ClockDurationMs->value])
            || isset($properties[PropKey::ClockOffsetMs->value]);
    }

    /**
     * The label a Text's native clock shows at [nowMs] (Android/iOS
     * `PamClockSpec.label`), or null when the properties have no clock.
     *
     * @param array<int, mixed> $properties
     */
    public static function clockLabel(array $properties, int $nowMs): ?string
    {
        $clock = self::clockOf($properties);

        return $clock === null ? null : $clock[5]->format(self::clockSeconds($clock, $nowMs));
    }

    /**
     * The next instant (epoch ms) after [nowMs] at which the clock's label
     * changes length: it gains a character counting up (9:59 -> 10:00) and
     * loses one counting down (10 -> 9). Null when it no longer changes
     * length (paused, not started yet, at zero) or has no clock.
     *
     * @param array<int, mixed> $properties
     */
    public static function clockLengthChangeAt(array $properties, int $nowMs): ?int
    {
        $clock = self::clockOf($properties);

        return $clock === null ? null : self::lengthChangeAt($clock, $nowMs);
    }

    /**
     * What the framework renders for a clock Text at [nowMs]: the label the
     * engine measures (and screen readers get until the renderer takes
     * over), and when the component must render again so the measure follows
     * the label's length (null: never). Counting up, the longer label is
     * rendered CLOCK_GROWTH_LEAD_MS before the native clock reaches it;
     * counting down, the component renders once the label got shorter.
     *
     * @param array<int, mixed> $properties
     * @return array{0: string, 1: ?int}|null
     */
    public static function clockLayout(array $properties, int $nowMs): ?array
    {
        $clock = self::clockOf($properties);
        if ($clock === null) {
            return null;
        }
        $format = $clock[5];
        $label = $format->format(self::clockSeconds($clock, $nowMs));
        $change = self::lengthChangeAt($clock, $nowMs);
        if ($change === null) {
            return [$label, null];
        }
        if ($clock[1] !== null || $clock[2] !== null) {
            return [$label, $change];
        }
        if ($change - $nowMs > self::CLOCK_GROWTH_LEAD_MS) {
            return [$label, $change - self::CLOCK_GROWTH_LEAD_MS];
        }
        $next = self::lengthChangeAt($clock, $change);

        return [
            $format->format(self::clockSeconds($clock, $change)),
            $next === null ? null : $next - self::CLOCK_GROWTH_LEAD_MS,
        ];
    }

    /**
     * @internal A Text whose native clock is on gets its label as content
     * ({@see clockLayout()}), whatever the placeholder: the engine measures
     * the real label and the component rendering it is told when to render
     * again. Rich text (styled runs) keeps its content.
     */
    public static function labelled(Element $element): Element
    {
        $properties = $element->properties;
        if (
            $element->kind !== NodeKind::Text
            || !self::hasClock($properties)
            || isset($properties[PropKey::TextSpans->value])
        ) {
            return $element;
        }
        $layout = self::clockLayout($properties, self::nowMs());
        if ($layout === null) {
            return $element;
        }
        self::renderAgainAt($layout[1]);

        return ($properties[PropKey::Text->value] ?? null) === $layout[0]
            ? $element
            : $element->__pamWithProperties([PropKey::Text->value => $layout[0]]);
    }

    /** @internal A component starts its own render (Component). */
    public static function beginRender(): void
    {
        self::$renderFrames[] = null;
    }

    /**
     * @internal The component's render ended: the earliest instant its clock
     * texts need it to render again, null for none.
     */
    public static function endRender(): ?int
    {
        return array_pop(self::$renderFrames);
    }

    private static function renderAgainAt(?int $dueMs): void
    {
        if ($dueMs === null) {
            return;
        }
        $top = array_key_last(self::$renderFrames);
        if ($top !== null) {
            $current = self::$renderFrames[$top];
            if ($current === null || $dueMs < $current) {
                self::$renderFrames[$top] = $dueMs;
            }

            return;
        }
        // Built for a component after its render (a lazy list row): it keeps
        // the earliest instant asked of it until it renders again.
        ComponentLifecycle::current()?->__pamClockRenderAt($dueMs, true);
    }

    /**
     * The clock as the renderers read it (`syncTimeBinding`): [startedAt,
     * endsAt, durationMs, offsetMs, running, format], or null when off.
     *
     * @param array<int, mixed> $properties
     * @return array{0: ?int, 1: ?int, 2: ?int, 3: int, 4: bool, 5: ClockFormat}|null
     */
    private static function clockOf(array $properties): ?array
    {
        $start = self::wireMillis($properties[PropKey::ClockStartedAt->value] ?? null);
        $end = self::wireMillis($properties[PropKey::ClockEndsAt->value] ?? null);
        $duration = self::wireMillis($properties[PropKey::ClockDurationMs->value] ?? null);
        $offset = self::wireMillis($properties[PropKey::ClockOffsetMs->value] ?? null);
        if ($start === null && $end === null && $duration === null && $offset === null) {
            return null;
        }
        $running = $properties[PropKey::ClockRunning->value] ?? true;
        $format = self::wireMillis($properties[PropKey::ClockFormat->value] ?? null) ?? ClockFormat::MinutesSeconds->value;

        return [
            $start,
            $end,
            $duration,
            $offset ?? 0,
            is_bool($running) ? $running : true,
            ClockFormat::tryFrom($format) ?? ClockFormat::MinutesSeconds,
        ];
    }

    /** An integer wire value, as the renderers read one (other types: absent). */
    private static function wireMillis(mixed $value): ?int
    {
        if (is_int($value)) {
            return $value;
        }

        return is_float($value) && is_finite($value) && abs($value) < 9.2e18 ? (int) $value : null;
    }

    /**
     * Whole seconds shown at [nowMs]: elapsed rounded down, or left rounded
     * up for a countdown (`PamClockSpec.valueAt`/`label`).
     *
     * @param array{0: ?int, 1: ?int, 2: ?int, 3: int, 4: bool, 5: ClockFormat} $clock
     */
    private static function clockSeconds(array $clock, int $nowMs): int
    {
        [$start, $end, $duration, $offset, $running] = $clock;
        if ($end !== null) {
            return intdiv(max(0, $end - $nowMs) + 999, 1_000);
        }
        $elapsed = max(0, $offset);
        if ($start !== null && $running) {
            $elapsed += max(0, $nowMs - $start);
        }

        return $duration !== null
            ? intdiv(max(0, $duration - $elapsed) + 999, 1_000)
            : intdiv($elapsed, 1_000);
    }

    /**
     * Labels only get longer as the seconds grow (in every format), so the
     * next length change is found by searching the seconds.
     *
     * @param array{0: ?int, 1: ?int, 2: ?int, 3: int, 4: bool, 5: ClockFormat} $clock
     */
    private static function lengthChangeAt(array $clock, int $nowMs): ?int
    {
        [$start, $end, $duration, $offset, $running, $format] = $clock;
        if ($end === null && ($start === null || !$running)) {
            return null;
        }
        $seconds = self::clockSeconds($clock, $nowMs);
        $length = strlen($format->format($seconds));
        if ($end === null && $duration === null) {
            // Counting up: the first second with a longer label.
            $low = $seconds;
            $high = $seconds + 1;
            while (strlen($format->format($high)) === $length) {
                if ($high >= self::MAX_CLOCK_SECONDS) {
                    return null;
                }
                $low = $high;
                $high = min(self::MAX_CLOCK_SECONDS, $high * 2);
            }
            while ($high - $low > 1) {
                $middle = intdiv($low + $high, 2);
                if (strlen($format->format($middle)) === $length) {
                    $low = $middle;
                } else {
                    $high = $middle;
                }
            }

            // Shown once the elapsed time reaches $high seconds.
            return $start + $high * 1_000 - max(0, $offset);
        }
        // Counting down: the last second with a shorter label.
        if ($seconds === 0 || strlen($format->format(0)) === $length) {
            return null;
        }
        $low = 0;
        $high = $seconds;
        while ($high - $low > 1) {
            $middle = intdiv($low + $high, 2);
            if (strlen($format->format($middle)) < $length) {
                $low = $middle;
            } else {
                $high = $middle;
            }
        }
        // Shown once at most $low seconds are left.
        $left = $low * 1_000;

        return $end !== null ? $end - $left : $start + $duration - max(0, $offset) - $left;
    }

    public static function key(mixed $value): ?string
    {
        if ($value === null || $value === false) {
            return null;
        }
        if (!is_string($value) && !is_int($value) && !$value instanceof \Stringable) {
            throw new InvalidArgumentException('revealKey must be a string or an integer.');
        }
        $key = (string) $value;
        if (strlen($key) > self::KEY_BYTES) {
            throw new InvalidArgumentException('revealKey cannot exceed '.self::KEY_BYTES.' bytes.');
        }

        return $key;
    }
}

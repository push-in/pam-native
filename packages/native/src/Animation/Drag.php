<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

use InvalidArgumentException;
use Stringable;

/**
 * UI-thread drag contract for a pan `GestureDetector` (react-native-gesture-handler
 * `Gesture.Pan()` + Reanimated shared values). The target follows the finger
 * on one axis inside optional bounds, driver properties are interpolated from
 * the translation every frame, and on release the target settles onto a snap
 * point with a spring or timing curve. PHP only receives `on:gestureEnd`
 * (with `snapIndex`/`thresholdReached`) and `on:gestureSettle`.
 *
 * ```php
 * // Story drag-to-dismiss
 * Drag::vertical()
 *     ->bounds(min: 0)
 *     ->snapPoints(0, '100%')
 *     ->threshold(120, velocity: 900)
 *     ->settle(new Spring(230, 22, 0.72))
 *     ->settleAt(1, Easing::EaseOut, 190)
 *     ->drive('', 'scale', [0, '50%'], [1, 0.955])
 *     ->drive('', 'borderRadius', [0, 120], [0, 18]);
 * ```
 */
final readonly class Drag implements Stringable
{
    /**
     * @param list<string> $snaps
     * @param array<int, string> $settles
     * @param list<string> $drivers
     */
    private function __construct(
        private bool $horizontal,
        private ?float $minimum = null,
        private ?float $maximum = null,
        private float $rubber = 0.0,
        private string $target = '',
        private array $snaps = ['0'],
        private string $settle = 'spring:260:22:1',
        private array $settles = [],
        private float $threshold = 0.0,
        private float $velocity = 0.0,
        private bool $haptic = false,
        private string $group = '',
        private array $drivers = [],
    ) {
    }

    public static function horizontal(): self
    {
        return new self(true);
    }

    public static function vertical(): self
    {
        return new self(false);
    }

    /** Translation limits in dp relative to the resting position (null = unbounded). */
    public function bounds(?float $min = null, ?float $max = null): self
    {
        if ($min !== null && $max !== null && $min > $max) {
            throw new InvalidArgumentException('Drag minimum cannot exceed its maximum.');
        }

        return new self(
            $this->horizontal,
            $min,
            $max,
            $this->rubber,
            $this->target,
            $this->snaps,
            $this->settle,
            $this->settles,
            $this->threshold,
            $this->velocity,
            $this->haptic,
            $this->group,
            $this->drivers,
        );
    }

    /** Fraction of finger movement applied past the bounds (0 = hard stop, 0.5 = iOS-like). */
    public function rubberBand(float $factor): self
    {
        return $this->with(rubber: max(0.0, min(1.0, $factor)));
    }

    /** `nativeRef` of the view that moves; defaults to the detector's child. */
    public function target(string $nativeRef): self
    {
        return $this->with(target: self::ref($nativeRef));
    }

    /** Resting positions in dp or percent of the target extent ("100%"). */
    public function snapPoints(float|int|string ...$points): self
    {
        if ($points === [] || count($points) > 16) {
            throw new InvalidArgumentException('Drags require between 1 and 16 snap points.');
        }

        return $this->with(snaps: array_map(Motion::value(...), array_values($points)));
    }

    /**
     * Release rule: crossing [distance] dp from the starting snap, or flinging
     * faster than [velocity] dp/s, moves to the adjacent snap in that direction.
     */
    public function threshold(float $distance, float $velocity = 0.0): self
    {
        return $this->with(threshold: max(0.0, $distance), velocity: max(0.0, $velocity));
    }

    /** Default settle animation. */
    public function settle(Spring|Easing|string $curve, int $durationMs = 200): self
    {
        return $this->with(settle: self::curve($curve, $durationMs));
    }

    /** Settle animation used when landing on snap [index] (e.g. a timed dismiss). */
    public function settleAt(int $index, Spring|Easing|string $curve, int $durationMs = 200): self
    {
        if ($index < 0 || $index > 15) {
            throw new InvalidArgumentException('Snap index must be between 0 and 15.');
        }
        $settles = $this->settles;
        $settles[$index] = self::curve($curve, $durationMs);

        return $this->with(settles: $settles);
    }

    /** Light haptic tick when the threshold is crossed (swipe-to-reply). */
    public function haptic(bool $enabled = true): self
    {
        return $this->with(haptic: $enabled);
    }

    /** Only one drag per group rests away from zero (Swipeable rows). */
    public function group(string $name): self
    {
        if (preg_match('/^[A-Za-z0-9_.:-]{1,64}$/D', $name) !== 1) {
            throw new InvalidArgumentException('Drag group names use 1-64 letters, digits, "_", ".", ":" or "-".');
        }

        return $this->with(group: $name);
    }

    /**
     * Interpolates [property] of `nativeRef` [ref] ('' = the target) from the
     * drag translation (dp or "%" of the target extent), clamped at the ends.
     *
     * @param list<float|int|string> $input
     * @param list<float|int|string> $output
     */
    public function drive(string $ref, string $property, array $input, array $output): self
    {
        if (count($input) < 2 || count($input) !== count($output) || count($input) > 8) {
            throw new InvalidArgumentException('Drag drivers need 2-8 matching input and output points.');
        }
        if (count($this->drivers) >= 16) {
            throw new InvalidArgumentException('Drags support up to 16 drivers.');
        }
        $driver = ($ref === '' ? '' : self::ref($ref)).'|'.Motion::property($property).'|'
            .implode(',', array_map(Motion::value(...), $input)).'|'
            .implode(',', array_map(Motion::value(...), $output));

        return $this->with(drivers: [...$this->drivers, $driver]);
    }

    public function encode(): string
    {
        $lines = ['axis='.($this->horizontal ? 'x' : 'y')];
        if ($this->minimum !== null) $lines[] = 'min='.Motion::number($this->minimum);
        if ($this->maximum !== null) $lines[] = 'max='.Motion::number($this->maximum);
        if ($this->rubber > 0) $lines[] = 'rubber='.Motion::number($this->rubber);
        if ($this->target !== '') $lines[] = 'target='.$this->target;
        $lines[] = 'snaps='.implode(',', $this->snaps);
        $lines[] = 'settle='.$this->settle;
        $settles = $this->settles;
        ksort($settles);
        foreach ($settles as $index => $settle) {
            $lines[] = "settle.{$index}={$settle}";
        }
        if ($this->threshold > 0) $lines[] = 'threshold='.Motion::number($this->threshold);
        if ($this->velocity > 0) $lines[] = 'velocity='.Motion::number($this->velocity);
        if ($this->haptic) $lines[] = 'haptic=1';
        if ($this->group !== '') $lines[] = 'group='.$this->group;
        foreach ($this->drivers as $driver) {
            $lines[] = 'drive='.$driver;
        }

        return implode("\n", $lines);
    }

    public function __toString(): string
    {
        return $this->encode();
    }

    /**
     * Encodes a programmatic snap request for `dragSnap`: snap [index], issued
     * again whenever [request] changes (e.g. a "close row" counter). Templates
     * may also pass "index@request".
     */
    public static function snapRequest(int $index, int $request = 0): int
    {
        if ($index < 0 || $index > 15 || $request < 0 || $request > 100_000_000) {
            throw new InvalidArgumentException('Snap requests need an index 0-15 and a non-negative request.');
        }

        return $request * 64 + $index;
    }

    /** @internal */
    public static function snapRequestValue(mixed $value): int
    {
        if (is_string($value) && preg_match('/^(\d{1,2})@(\d{1,9})$/D', trim($value), $match) === 1) {
            return self::snapRequest((int) $match[1], (int) $match[2]);
        }
        if (is_int($value) || (is_string($value) && ctype_digit($value))) {
            return self::snapRequest((int) $value % 64, intdiv((int) $value, 64));
        }
        throw new InvalidArgumentException('dragSnap expects an index or "index@request".');
    }

    public static function ref(string $ref): string
    {
        if (preg_match('/^[A-Za-z0-9_.:-]{1,64}$/D', $ref) !== 1) {
            throw new InvalidArgumentException('nativeRef values use 1-64 letters, digits, "_", ".", ":" or "-".');
        }

        return $ref;
    }

    private static function curve(Spring|Easing|string $curve, int $durationMs): string
    {
        if ($curve instanceof Spring) {
            return $curve->token();
        }
        Motion::milliseconds($durationMs, 'Drag settle duration');

        return 'timing:'.$durationMs.':'.Easing::token($curve);
    }

    /**
     * @param list<string>|null $snaps
     * @param array<int, string>|null $settles
     * @param list<string>|null $drivers
     */
    private function with(
        ?float $rubber = null,
        ?string $target = null,
        ?array $snaps = null,
        ?string $settle = null,
        ?array $settles = null,
        ?float $threshold = null,
        ?float $velocity = null,
        ?bool $haptic = null,
        ?string $group = null,
        ?array $drivers = null,
    ): self {
        return new self(
            $this->horizontal,
            $this->minimum,
            $this->maximum,
            $rubber ?? $this->rubber,
            $target ?? $this->target,
            $snaps ?? $this->snaps,
            $settle ?? $this->settle,
            $settles ?? $this->settles,
            $threshold ?? $this->threshold,
            $velocity ?? $this->velocity,
            $haptic ?? $this->haptic,
            $group ?? $this->group,
            $drivers ?? $this->drivers,
        );
    }
}

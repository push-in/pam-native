<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * React Native `Keyboard.metrics()` / `KeyboardEvent.endCoordinates`: the
 * settled soft keyboard over the app window. `height` is the visible keyboard
 * height in points measured from the window bottom (0 while hidden);
 * `durationMs` is the platform show/hide animation length when the host knows
 * it (iOS always, Android when the IME animates), otherwise 0.
 *
 * Hosts publish one value per settled change (show, hide, resize such as a
 * suggestion strip or an emoji keyboard), never one per animation frame.
 */
final readonly class KeyboardMetrics
{
    public function __construct(
        public bool $visible,
        public float $height,
        public int $durationMs = 0,
    ) {
    }

    /**
     * Wire payload of `EventKind::KeyboardVisibility`: `"1"`/`"0"` from hosts
     * older than the height API, else `"<visible>;<heightDp>;<durationMs>"`.
     */
    public static function fromPayload(string $payload, float $previousHeight = 0.0): self
    {
        $parts = explode(';', $payload, 4);
        $visible = ($parts[0] ?? '') === '1';
        if (!isset($parts[1])) {
            // Legacy host: visibility only; keep the last known height while visible.
            return new self($visible, $visible ? $previousHeight : 0.0);
        }
        $height = is_numeric($parts[1]) ? (float) $parts[1] : 0.0;
        $height = is_finite($height) ? round(max(0.0, min($height, 10_000.0)), 1) : 0.0;
        $duration = isset($parts[2]) && is_numeric($parts[2]) ? (int) $parts[2] : 0;

        return new self($visible, $visible ? $height : 0.0, max(0, min($duration, 5_000)));
    }

    /** True when the visible state or the height differ (duration is metadata). */
    public function differsFrom(self $other): bool
    {
        return $this->visible !== $other->visible || abs($this->height - $other->height) >= 0.5;
    }
}

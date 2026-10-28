<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use InvalidArgumentException;

/**
 * Motion of a shared element (see `Element::sharedTransition()`). The open
 * curve flies the element into the route being shown; `returning()` sets a
 * different curve for the flight back when that route is popped (React
 * Navigation / WhatsApp: spring in, quick ease-in home). The native host
 * reads the style of the element on the top route.
 */
final readonly class SharedTransitionStyle
{
    public function __construct(
        public int $durationMs = 500,
        public SharedTransitionEasing $easing = SharedTransitionEasing::EaseInOut,
        public SharedTransitionResizeMode $resizeMode = SharedTransitionResizeMode::Scale,
        public bool $crossFade = true,
        public float $damping = 0.82,
        public float $stiffness = 220.0,
        public float $mass = 1.0,
        public ?int $returnDurationMs = null,
        public ?SharedTransitionEasing $returnEasing = null,
        public bool $launch = false,
        public string $launchBackdrop = '#000000',
    ) {
        if (preg_match('/^#[0-9A-Fa-f]{6}$/', $launchBackdrop) !== 1) {
            throw new InvalidArgumentException('Shared transition launch backdrop must be a #RRGGBB color.');
        }
        if ($durationMs < 0 || $durationMs > 2_000) {
            throw new InvalidArgumentException('Shared transition duration must be between 0 and 2000 ms.');
        }
        if ($damping <= 0.0 || $damping > 1.0) {
            throw new InvalidArgumentException('Shared transition damping must be in (0, 1].');
        }
        if ($stiffness < 1.0 || $stiffness > 1_000.0) {
            throw new InvalidArgumentException('Shared transition stiffness must be between 1 and 1000.');
        }
        if ($returnDurationMs !== null && ($returnDurationMs < 0 || $returnDurationMs > 2_000)) {
            throw new InvalidArgumentException('Shared transition return duration must be between 0 and 2000 ms.');
        }
        if ($mass < 0.1 || $mass > 10.0) {
            throw new InvalidArgumentException('Shared transition mass must be between 0.1 and 10.');
        }
    }

    public static function timing(int $durationMs = 500): self
    {
        return new self(durationMs: $durationMs);
    }

    public static function spring(
        int $durationMs = 500,
        float $damping = 0.82,
        float $stiffness = 220.0,
        float $mass = 1.0,
    ): self {
        return new self($durationMs, SharedTransitionEasing::Spring, damping: $damping, stiffness: $stiffness, mass: $mass);
    }

    public function resize(SharedTransitionResizeMode $mode): self
    {
        return new self($this->durationMs, $this->easing, $mode, $this->crossFade, $this->damping, $this->stiffness, $this->mass, $this->returnDurationMs, $this->returnEasing, $this->launch, $this->launchBackdrop);
    }

    public function crossFade(bool $enabled = true): self
    {
        return new self($this->durationMs, $this->easing, $this->resizeMode, $enabled, $this->damping, $this->stiffness, $this->mass, $this->returnDurationMs, $this->returnEasing, $this->launch, $this->launchBackdrop);
    }

    /** Curve of the flight back when the route holding this element is popped. */
    public function returning(int $durationMs, SharedTransitionEasing $easing = SharedTransitionEasing::EaseIn): self
    {
        return new self($this->durationMs, $this->easing, $this->resizeMode, $this->crossFade, $this->damping, $this->stiffness, $this->mass, $durationMs, $easing, $this->launch, $this->launchBackdrop);
    }

    /**
     * Put on the element that is tapped (a thumbnail): its flight starts on
     * the tap itself, natively, toward the media contain-fit on the whole
     * screen over a [backdrop] that fades in, before the app has handled the
     * press. When the route it opens is pushed the flight continues to the
     * real destination; if no route comes, it flies back home. Only short
     * taps launch (a held press may be a long press).
     */
    public function launchOnTap(string $backdrop = '#000000'): self
    {
        return new self($this->durationMs, $this->easing, $this->resizeMode, $this->crossFade, $this->damping, $this->stiffness, $this->mass, $this->returnDurationMs, $this->returnEasing, true, $backdrop);
    }

    public function toJson(): string
    {
        return json_encode([
            'durationMs' => $this->durationMs,
            'easing' => $this->easing->value,
            'resizeMode' => $this->resizeMode->value,
            'crossFade' => $this->crossFade,
            'damping' => $this->damping,
            'stiffness' => $this->stiffness,
            'mass' => $this->mass,
            ...($this->returnDurationMs === null ? [] : ['returnDurationMs' => $this->returnDurationMs]),
            ...($this->returnEasing === null ? [] : ['returnEasing' => $this->returnEasing->value]),
            ...($this->launch ? ['launch' => true, 'launchBackdrop' => $this->launchBackdrop] : []),
        ], JSON_THROW_ON_ERROR);
    }
}

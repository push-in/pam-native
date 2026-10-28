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
    ) {
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
        return new self($this->durationMs, $this->easing, $mode, $this->crossFade, $this->damping, $this->stiffness, $this->mass, $this->returnDurationMs, $this->returnEasing);
    }

    public function crossFade(bool $enabled = true): self
    {
        return new self($this->durationMs, $this->easing, $this->resizeMode, $enabled, $this->damping, $this->stiffness, $this->mass, $this->returnDurationMs, $this->returnEasing);
    }

    /** Curve of the flight back when the route holding this element is popped. */
    public function returning(int $durationMs, SharedTransitionEasing $easing = SharedTransitionEasing::EaseIn): self
    {
        return new self($this->durationMs, $this->easing, $this->resizeMode, $this->crossFade, $this->damping, $this->stiffness, $this->mass, $durationMs, $easing);
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
        ], JSON_THROW_ON_ERROR);
    }
}

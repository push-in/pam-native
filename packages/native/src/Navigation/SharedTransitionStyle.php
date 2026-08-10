<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use InvalidArgumentException;

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
        return new self($this->durationMs, $this->easing, $mode, $this->crossFade, $this->damping, $this->stiffness, $this->mass);
    }

    public function crossFade(bool $enabled = true): self
    {
        return new self($this->durationMs, $this->easing, $this->resizeMode, $enabled, $this->damping, $this->stiffness, $this->mass);
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
        ], JSON_THROW_ON_ERROR);
    }
}

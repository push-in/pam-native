<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

use InvalidArgumentException;

/**
 * Physical spring parameters (Reanimated `withSpring` defaults: stiffness
 * 100, damping 10, mass 1).
 */
final readonly class Spring
{
    public function __construct(
        public float $stiffness = 100.0,
        public float $damping = 10.0,
        public float $mass = 1.0,
    ) {
        if (!is_finite($stiffness) || $stiffness <= 0 || $stiffness > 100_000) {
            throw new InvalidArgumentException('Spring stiffness must be between 0 and 100000.');
        }
        if (!is_finite($damping) || $damping < 0 || $damping > 100_000) {
            throw new InvalidArgumentException('Spring damping must be between 0 and 100000.');
        }
        if (!is_finite($mass) || $mass <= 0 || $mass > 1_000) {
            throw new InvalidArgumentException('Spring mass must be between 0 and 1000.');
        }
    }

    public function token(): string
    {
        return 'spring:'.Motion::number($this->stiffness).':'.Motion::number($this->damping)
            .':'.Motion::number($this->mass);
    }

    /**
     * Settling time in milliseconds for a move of [distance] units, using the
     * same closed-form oscillator and rest thresholds as the native runtime.
     */
    public function durationMs(float $from, float $to, float $velocity = 0.0): int
    {
        $x0 = $from - $to;
        $omega = sqrt($this->stiffness / $this->mass);
        $zeta = $this->damping / (2 * sqrt($this->stiffness * $this->mass));
        $scale = max(abs($to - $from), abs($velocity) * 0.05, 1e-3);
        $restDisplacement = $scale * 0.002;
        $restVelocity = $scale * 0.02;
        $displacement = function (float $t) use ($x0, $omega, $zeta, $velocity): float {
            if ($zeta < 1) {
                $omegaD = $omega * sqrt(1 - $zeta * $zeta);
                return exp(-$zeta * $omega * $t) * ($x0 * cos($omegaD * $t)
                    + ($velocity + $zeta * $omega * $x0) / $omegaD * sin($omegaD * $t));
            }
            if ($zeta == 1.0) {
                return ($x0 + ($velocity + $omega * $x0) * $t) * exp(-$omega * $t);
            }
            $root = sqrt($zeta * $zeta - 1);
            $r1 = -$omega * ($zeta - $root);
            $r2 = -$omega * ($zeta + $root);
            $a = ($velocity - $r2 * $x0) / ($r1 - $r2);
            return $a * exp($r1 * $t) + ($x0 - $a) * exp($r2 * $t);
        };
        if (abs($x0) < $restDisplacement && abs($velocity) < $restVelocity) {
            return 0;
        }
        for ($ms = 1; $ms < 10_000; $ms++) {
            $t = $ms / 1000;
            $v = ($displacement($t + 5e-4) - $displacement($t - 5e-4)) / 1e-3;
            if (abs($displacement($t)) < $restDisplacement && abs($v) < $restVelocity) {
                return $ms;
            }
        }
        return 10_000;
    }
}

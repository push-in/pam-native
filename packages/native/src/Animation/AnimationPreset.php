<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

/**
 * Ready-made UI-thread motions matching the Zé Chat React Native app.
 */
final class AnimationPreset
{
    private function __construct()
    {
    }

    /**
     * Reels double-tap heart: pops in with an overshooting spring, holds,
     * floats up while fading out (ReelPage `animateLike`).
     */
    public static function heartBurst(): Animation
    {
        $scale = Animation::sequence(
            Animation::set(['scale' => 0.5]),
            Animation::spring(['scale' => 1.1], stiffness: 300, damping: 7, mass: 0.5),
            Animation::timing(['scale' => 1], 120, Easing::EaseInOutQuad),
        );
        $opacity = Animation::sequence(
            Animation::set(['opacity' => 0]),
            Animation::timing(['opacity' => 1], 60, Easing::Linear),
            Animation::wait(360),
            Animation::timing(['opacity' => 0], 260, Easing::EaseInQuad),
        );
        $float = Animation::sequence(
            Animation::set(['translateY' => 0]),
            Animation::wait(420),
            Animation::timing(['translateY' => -36], 260, Easing::EaseInQuad),
        );

        return $scale->with($opacity)->with($float);
    }

    /** Like button bounce: quick squash, overshoot, settle. */
    public static function likeBounce(): Animation
    {
        return Animation::sequence(
            Animation::timing(['scale' => 0.82], 70, Easing::EaseOutQuad),
            Animation::spring(['scale' => 1.0], stiffness: 420, damping: 9, mass: 0.6),
        );
    }

    /** Back-to-top / floating button entrance: slide up with a spring and fade in. */
    public static function backToTop(bool $visible = true): Animation
    {
        return $visible
            ? Animation::spring(['translateY' => 0, 'scale' => 1], stiffness: 260, damping: 20)
                ->with(Animation::timing(['opacity' => 1], 160, Easing::EaseOut))
            : Animation::timing(['translateY' => 24, 'scale' => 0.9, 'opacity' => 0], 160, Easing::EaseIn);
    }

    /** Skeleton shimmer: sweeps a highlight across its own width forever. */
    public static function shimmer(int $durationMs = 1200): Animation
    {
        return Animation::sequence(
            Animation::set(['translateX' => '-100%']),
            Animation::timing(['translateX' => '100%'], $durationMs, Easing::Linear),
        )->repeat();
    }

    /** Opacity pulse used by placeholders and recording indicators. */
    public static function pulse(float $minimum = 0.35, int $durationMs = 700): Animation
    {
        return Animation::sequence(
            Animation::timing(['opacity' => $minimum], $durationMs, Easing::EaseInOut),
            Animation::timing(['opacity' => 1], $durationMs, Easing::EaseInOut),
        )->repeat();
    }

    /**
     * Ticker that scrolls an overflowing track by [distance] dp at [speed]
     * dp/s, holding at each end (ReelInfoBlock `ReelMarquee`). For single-line
     * text prefer `ellipsizeMode="marquee"`, which needs no measurement.
     */
    public static function marquee(float $distance, float $speed = 30.0, int $holdMs = 1200): Animation
    {
        $duration = (int) round(max(0.0, $distance) / max(1.0, $speed) * 1000);

        return Animation::sequence(
            Animation::wait($holdMs),
            Animation::timing(['translateX' => -abs($distance)], min(60_000, $duration), Easing::Linear),
            Animation::wait($holdMs),
            Animation::timing(['translateX' => 0], 260, Easing::EaseOutQuad),
        )->repeat();
    }

    /** Row entrance for live chat messages (`FadeInUp.duration(180)`). */
    public static function fadeInUp(int $durationMs = 180, float $offset = 14.0): Animation
    {
        return Animation::set(['opacity' => 0, 'translateY' => $offset])
            ->then(Animation::timing(['opacity' => 1, 'translateY' => 0], $durationMs, Easing::EaseOut));
    }
}

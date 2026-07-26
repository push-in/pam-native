<?php

declare(strict_types=1);

namespace Pam\Native;

enum MotionPreset: int
{
    case None = 1;
    case FadeIn = 2;
    case ScaleIn = 3;
    case SlideUp = 4;
    case SlideDown = 5;
    case Success = 6;
    case Shake = 7;

    public function animationKind(): AnimationKind
    {
        return match ($this) {
            self::None => AnimationKind::None,
            self::FadeIn => AnimationKind::FadeIn,
            self::ScaleIn => AnimationKind::ScaleIn,
            self::SlideUp => AnimationKind::SlideUp,
            self::SlideDown => AnimationKind::SlideDown,
            self::Success => AnimationKind::Success,
            self::Shake => AnimationKind::Shake,
        };
    }
}

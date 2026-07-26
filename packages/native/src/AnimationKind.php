<?php

declare(strict_types=1);

namespace Pam\Native;

enum AnimationKind: int
{
    case None = 1;
    case Pulse = 2;
    case FadeIn = 3;
    case ScaleIn = 4;
    case SlideUp = 5;
    case SlideDown = 6;
    case Success = 7;
    case Shake = 8;
}

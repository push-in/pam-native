<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum SharedTransitionEasing: int
{
    case Linear = 1;
    case EaseInOut = 2;
    /** Damped spring from damping ratio, stiffness and mass (overshoot clamped). */
    case Spring = 3;
    /** Cubic ease-in: accelerates into the destination (a close flying home). */
    case EaseIn = 4;
}

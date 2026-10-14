<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * How a `mediaClock` text renders its player's position, natively and
 * without waking PHP: `m:ss` (`h:mm:ss` past an hour).
 */
enum MediaClockFormat: int
{
    /** Position played so far (`0:42`). */
    case Elapsed = 1;
    /** Time left until the end (`1:18`). */
    case Remaining = 2;
    /** Position and duration (`0:42 / 2:00`). */
    case ElapsedAndDuration = 3;
    /** Media duration once known (`2:00`). */
    case Duration = 4;
    /** Whole seconds left, rounded up (`5`), for story/ad countdowns. */
    case CountdownSeconds = 5;
}

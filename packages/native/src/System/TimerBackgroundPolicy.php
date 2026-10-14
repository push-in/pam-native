<?php

declare(strict_types=1);

namespace Pam\Native\System;

/** What a component timer does while the app is in the background. */
enum TimerBackgroundPolicy: int
{
    /** Stop the native timer and re-arm it on return (timeouts keep their remaining delay). */
    case Suspend = 1;
    /** Cancel the timer for good. */
    case Cancel = 2;
    /** Keep running; use only for work that must finish in the background. */
    case Continue = 3;
}

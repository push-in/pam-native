<?php

declare(strict_types=1);

namespace Pam\Native;

/** Outcome detail of System\Biometrics::authenticate. */
enum BiometricError: int
{
    case None = 1;
    /** The user dismissed the prompt or chose the negative button. */
    case Cancelled = 2;
    /** No enrolled biometrics, unsupported OS version or no prompt host. */
    case Unavailable = 3;
    /** Too many failed attempts; the platform locked biometrics for now. */
    case Lockout = 4;
}

<?php

declare(strict_types=1);

namespace Pam\Native;

/** Biometric hardware the device can authenticate with (System\Biometrics::status). */
enum BiometricKind: int
{
    case None = 1;
    case Fingerprint = 2;
    case Face = 3;
    case Iris = 4;
    case Other = 5;
}

<?php

declare(strict_types=1);

namespace Pam\Native;

/** Which implementation Pam\Native\Crypto uses for a primitive. */
enum CryptoBackend: int
{
    /** ext-sodium / ext-openssl in this PHP build (desktop, server, tests). */
    case Extension = 1;
    /** The Android/iOS host through pam_native_crypto() (JCA, Kotlin, CryptoKit). */
    case Native = 2;
    /** Neither: calls throw CryptoUnavailableException. */
    case Unavailable = 3;
}

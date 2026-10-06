<?php

declare(strict_types=1);

namespace Pam\Native;

use RuntimeException;

/**
 * Neither the PHP extension nor the native host can perform a Pam\Native\Crypto
 * primitive (for example a PAM Native host older than 1.19.0 on a device).
 */
final class CryptoUnavailableException extends RuntimeException
{
}

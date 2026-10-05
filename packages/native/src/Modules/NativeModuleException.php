<?php

declare(strict_types=1);

namespace Pam\Native\Modules;

use RuntimeException;
use Throwable;

/**
 * A native module reported a failure result (for example "Native module value
 * is too large" when a SQLite result exceeds the 1 MiB bridge limit). It is
 * passed to the call's failure handler when one exists and only escapes to
 * the runtime error overlay when the caller registered none.
 */
final class NativeModuleException extends RuntimeException
{
    public function __construct(
        public readonly string $module,
        public readonly string $method,
        string $message,
        ?Throwable $previous = null,
    ) {
        parent::__construct($message !== '' ? $message : "Native module {$module}.{$method} failed", 0, $previous);
    }
}

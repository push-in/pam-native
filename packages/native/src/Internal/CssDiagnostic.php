<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use RuntimeException;
use Throwable;

/**
 * A CSS compile error tied to one declaration. The outermost style compile
 * rewrites it as "<file>:<line>: <message>".
 */
final class CssDiagnostic extends RuntimeException
{
    public function __construct(
        string $message,
        public readonly string $property,
        public ?string $selector = null,
        ?Throwable $previous = null,
    ) {
        parent::__construct($message, 0, $previous);
    }
}

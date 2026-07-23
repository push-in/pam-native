<?php

declare(strict_types=1);

namespace Pam\Native;

use RuntimeException;

final class TemplateException extends RuntimeException
{
    public function __construct(
        string $message,
        public readonly string $template,
        public readonly int $templateLine,
        public readonly int $templateColumn = 1,
    ) {
        parent::__construct("{$message} at {$template}:{$templateLine}:{$templateColumn}");
    }
}

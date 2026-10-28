<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

/** @internal Marks a loader result that settles later through then(). */
final class ResourcePending
{
    private static ?self $instance = null;

    private function __construct()
    {
    }

    public static function instance(): self
    {
        return self::$instance ??= new self();
    }
}

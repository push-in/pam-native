<?php

declare(strict_types=1);

namespace Pam\Native;

final class BuildConfiguration
{
    private function __construct()
    {
    }

    public static function mode(): BuildMode
    {
        return match (strtolower((string) getenv('PAM_NATIVE_MODE'))) {
            'production', 'release' => BuildMode::Production,
            'benchmark' => BuildMode::Benchmark,
            default => BuildMode::Development,
        };
    }

    public static function strict(): bool
    {
        return self::mode() !== BuildMode::Development
            || getenv('PAM_NATIVE_STRICT') === '1';
    }
}

<?php

declare(strict_types=1);

namespace Pam\Native;

final class Protocol
{
    public const string SDK_VERSION = '0.5.26';
    public const int VERSION = 1;
    public const string TREE_MAGIC = 'PNT1';
    public const string PATCH_MAGIC = 'PNP1';
    public const string BATCH_MAGIC = 'PNB1';

    private function __construct()
    {
    }

    public static function supports(int $version): bool
    {
        return $version === self::VERSION;
    }
}

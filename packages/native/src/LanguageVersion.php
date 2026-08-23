<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * Source-language generations are integer capabilities on purpose: bundles
 * never need to compare marketing version strings at runtime.
 */
enum LanguageVersion: int
{
    case Language1 = 1;
    case Language2 = 2;

    public const self CURRENT = self::Language2;
}

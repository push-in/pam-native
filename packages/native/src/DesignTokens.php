<?php

declare(strict_types=1);

namespace Pam\Native;

final class DesignTokens
{
    public const float Space1 = 4.0;
    public const float Space2 = 8.0;
    public const float Space3 = 12.0;
    public const float Space4 = 16.0;
    public const float Space6 = 24.0;
    public const float Space8 = 32.0;

    public const float RadiusSmall = 8.0;
    public const float RadiusMedium = 12.0;
    public const float RadiusLarge = 20.0;

    public const float TextLabel = 14.0;
    public const float TextBody = 16.0;
    public const float TextTitle = 20.0;
    public const float TextHeadline = 24.0;

    public const float TouchTarget = 48.0;
    public const int MotionFastMs = 150;
    public const int MotionStandardMs = 240;

    private function __construct()
    {
    }
}

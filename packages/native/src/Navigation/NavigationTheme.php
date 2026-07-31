<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

final readonly class NavigationTheme
{
    public function __construct(
        public bool $dark,
        public int $primary,
        public int $background,
        public int $card,
        public int $text,
        public int $border,
        public int $notification,
        public int $scrim,
    ) {
    }

    public static function light(): self
    {
        return new self(false, 0xFF2563EB, 0xFFF8FAFC, 0xFFFFFFFF, 0xFF111827, 0x1F000000, 0xFFDC2626, 0x52000000);
    }

    public static function dark(): self
    {
        return new self(true, 0xFF60A5FA, 0xFF020617, 0xFF0F172A, 0xFFF8FAFC, 0x3DFFFFFF, 0xFFF87171, 0x99000000);
    }
}

<?php

declare(strict_types=1);

namespace Pam\Native\UI\Ir;

use Pam\Native\LanguageVersion;

final readonly class UiIr
{
    public const int VERSION = 2;

    /** @return array{version: int, language: int, capabilities: list<int>} */
    public static function manifest(LanguageVersion $language): array
    {
        return [
            'version' => self::VERSION,
            'language' => $language->value,
            'capabilities' => $language === LanguageVersion::Language2
                ? array_map(
                    static fn (UiCapability $capability): int => $capability->value,
                    UiCapability::cases(),
                )
                : [],
        ];
    }
}

<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Pam\Native\System\DeviceInfo;

/**
 * Reactive system metadata; app preferences and formatting live in pam-native-i18n.
 * @property-read string $value system locale/zone (fallback: en / PHP default zone)
 * @property-read string $device raw system value
 * @property-read bool $known whether the host has reported metadata
 * @property-read string $language language subtag
 * @property-read ?string $region region subtag
 */
final class LocaleState extends ReactiveObject
{
    /** @param SharedSource<DeviceInfo|null> $device */
    public function __construct(private readonly SharedSource $device)
    {
    }

    protected function properties(): array
    {
        return ['value', 'language', 'region', 'device', 'known'];
    }

    protected function read(string $name): mixed
    {
        Sources::requestDevice();
        $info = $this->device->signal->get();
        $device = $info?->locale ?? '';
        $value = $device !== '' ? $device : 'en';

        return match ($name) {
            'value' => $value,
            'language' => strtolower((preg_split('/[-_@.]/', $value) ?: ['en'])[0]),
            'region' => self::region($value),
            'device' => $device,
            'known' => $info !== null,
        };
    }

    private static function region(string $tag): ?string
    {
        foreach (array_slice(preg_split('/[-_]/', $tag) ?: [], 1) as $part) {
            if (preg_match('/^(?:[A-Za-z]{2}|\d{3})$/D', $part) === 1) return strtoupper($part);
        }

        return null;
    }
}

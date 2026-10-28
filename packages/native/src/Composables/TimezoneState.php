<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use DateTimeImmutable;
use DateTimeZone;
use Pam\Native\System\DeviceInfo;

/**
 * Reactive system metadata; app preferences and formatting live in pam-native-i18n.
 * @property-read string $value system locale/zone (fallback: en / PHP default zone)
 * @property-read string $device raw system value
 * @property-read bool $known whether the host has reported metadata
 */
final class TimezoneState extends ReactiveObject
{
    /** @param SharedSource<DeviceInfo|null> $device */
    public function __construct(private readonly SharedSource $device)
    {
    }

    protected function properties(): array
    {
        return ['value', 'device', 'known'];
    }

    protected function read(string $name): mixed
    {
        Sources::requestDevice();
        $info = $this->device->signal->get();
        $device = $info?->timeZone ?? '';

        return match ($name) {
            'value' => $device !== '' ? $device : date_default_timezone_get(),
            'device' => $device,
            'known' => $info !== null,
        };
    }

    public function zone(): DateTimeZone
    {
        $value = (string) $this->read('value');
        try {
            return new DateTimeZone($value);
        } catch (\Exception) {
            return new DateTimeZone('UTC');
        }
    }

    /** The current time in the effective zone. */
    public function now(): DateTimeImmutable
    {
        return new DateTimeImmutable('now', $this->zone());
    }
}

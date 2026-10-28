<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use DateTimeImmutable;
use DateTimeZone;
use Pam\Native\I18n\I18n;
use Pam\Native\System\DeviceInfo;

/**
 * `useTimezone()`: the device time zone, with the I18n::useTimezone()
 * override on top. Reactive like useLocale().
 *
 * @property-read string $value effective IANA name: the override, else the device, else PHP's default zone
 * @property-read string $device system zone, '' until the host reported it
 * @property-read bool $known true once the host reported the device zone
 * @property-read bool $overridden true while I18n::useTimezone() overrides the device
 */
final class TimezoneState extends ReactiveObject
{
    /**
     * @param SharedSource<DeviceInfo|null> $device
     * @param SharedSource<array{0: ?string, 1: ?string}> $overrides
     */
    public function __construct(
        private readonly SharedSource $device,
        private readonly SharedSource $overrides,
    ) {
    }

    protected function properties(): array
    {
        return ['value', 'device', 'known', 'overridden'];
    }

    protected function read(string $name): mixed
    {
        Sources::requestDevice();
        $info = $this->device->signal->get();
        [, $override] = $this->overrides->signal->get();
        $device = $info?->timeZone ?? '';

        return match ($name) {
            'value' => $override ?? ($device !== '' ? $device : date_default_timezone_get()),
            'device' => $device,
            'known' => $info !== null,
            default => $override !== null,
        };
    }

    /** Overrides the zone (I18n::useTimezone()); null follows the device again. */
    public function set(?string $timezone): void
    {
        I18n::useTimezone($timezone);
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

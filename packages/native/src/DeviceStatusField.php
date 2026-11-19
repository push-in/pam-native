<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * What a `DeviceStatus::watch()` listens to. The host only registers the
 * system sources a watch asks for, and only a change in those fields wakes
 * PHP; the delivered `DeviceStatus` still carries every current value.
 */
enum DeviceStatusField: int
{
    /** batteryLevel and charging (battery broadcasts). */
    case Battery = 1;
    /** networkType and expensiveNetwork (default network callback). */
    case Network = 2;
    /** lowPowerMode (power-save broadcast). */
    case PowerSave = 4;

    /** @param list<self> $fields empty means every field */
    public static function mask(array $fields): int
    {
        $mask = 0;
        foreach ($fields as $field) {
            $mask |= $field->value;
        }
        return $mask === 0 ? 7 : $mask;
    }
}

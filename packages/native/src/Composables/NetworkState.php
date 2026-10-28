<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Pam\Native\DeviceStatus;
use Pam\Native\NetworkType;

/**
 * `useNetwork()`: connectivity pushed by the host on change. Until the first
 * report the app is assumed online (`known` is false).
 *
 * @property-read bool $value alias of $online
 * @property-read bool $online
 * @property-read bool $known true once the host reported a status
 * @property-read NetworkType|null $type
 * @property-read bool $expensive metered network (cellular, hotspot)
 */
final class NetworkState extends ReactiveObject
{
    /** @param SharedSource<DeviceStatus|null> $source */
    public function __construct(private readonly SharedSource $source)
    {
    }

    protected function properties(): array
    {
        return ['value', 'online', 'known', 'type', 'expensive'];
    }

    protected function read(string $name): mixed
    {
        $status = $this->source->signal->get();

        return match ($name) {
            'value', 'online' => $status === null || $status->networkType !== NetworkType::None,
            'known' => $status !== null,
            'type' => $status?->networkType,
            default => $status !== null && $status->expensiveNetwork,
        };
    }
}

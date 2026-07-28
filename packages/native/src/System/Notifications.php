<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\NotificationImportance;
use RuntimeException;
use InvalidArgumentException;

final class Notifications
{
    private function __construct()
    {
    }

    /** @param Closure(bool): void $callback */
    public static function requestPermission(Closure $callback): int
    {
        return self::call('requestPermission', [], static function (array $values) use ($callback): void {
            $callback((bool) ($values['granted'] ?? false));
        });
    }

    public static function schedule(
        string $id,
        string $title,
        string $body,
        int $delaySeconds = 0,
        NotificationImportance $importance = NotificationImportance::Default,
        ?Closure $callback = null,
        array $data = [],
        ?string $deepLink = null,
    ): int {
        if ($id === '' || strlen($id) > 128) {
            throw new InvalidArgumentException('Notification id must contain between 1 and 128 bytes.');
        }
        if ($title === '' || strlen($title) > 4_096 || strlen($body) > 16_384) {
            throw new InvalidArgumentException('Notification title or body exceeds the native limit.');
        }
        $dataJson = json_encode($data, JSON_THROW_ON_ERROR);
        if (strlen($dataJson) > 262_144) {
            throw new InvalidArgumentException('Notification data exceeds 256 KiB.');
        }
        if ($deepLink !== null && strlen($deepLink) > 8_192) {
            throw new InvalidArgumentException('Notification deep link exceeds 8192 bytes.');
        }
        return self::call(
            'schedule',
            [
                'id' => $id,
                'title' => $title,
                'body' => $body,
                'delaySeconds' => max(0, min(31_536_000, $delaySeconds)),
                'importance' => $importance->value,
                'data' => $dataJson,
                'deepLink' => $deepLink ?? '',
            ],
            static fn (array $_): mixed => $callback?->__invoke(),
        );
    }

    public static function cancel(string $id, ?Closure $callback = null): int
    {
        return self::call(
            'cancel',
            ['id' => $id],
            static fn (array $_): mixed => $callback?->__invoke(),
        );
    }

    private static function call(string $method, array $payload, Closure $callback): int
    {
        return NativeModules::call(
            'notifications',
            $method,
            $payload,
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $callback($result->payload === '' ? [] : Wire::decodeMap($result->payload));
            },
        );
    }
}

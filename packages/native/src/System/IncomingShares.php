<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use JsonException;
use LogicException;
use Pam\Native\FileReference;
use Pam\Native\IncomingShare;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class IncomingShares
{
    private static int $nextSubscription = 1;

    /** @var array<int, Closure(IncomingShare): void> */
    private static array $subscriptions = [];

    private function __construct()
    {
    }

    /** @param Closure(?IncomingShare): void $callback */
    public static function initial(Closure $callback): int
    {
        return NativeModules::call('incoming-share', 'initial', [], static function ($result) use ($callback): void {
            if ($result->status === ModuleResultStatus::Failure) {
                throw new RuntimeException($result->payload);
            }
            $callback(self::decode($result->payload));
        });
    }

    /** @param Closure(IncomingShare): void $callback */
    public static function listen(Closure $callback): int
    {
        if (self::$subscriptions !== []) {
            throw new LogicException('Only one incoming-share listener may be active.');
        }
        $subscription = self::$nextSubscription++;
        self::$subscriptions[$subscription] = $callback;
        self::arm($subscription);

        return $subscription;
    }

    public static function unsubscribe(int $subscription): void
    {
        unset(self::$subscriptions[$subscription]);
    }

    /** @internal */
    public static function resetRuntime(): void
    {
        self::$subscriptions = [];
        self::$nextSubscription = 1;
    }

    private static function arm(int $subscription): void
    {
        if (!isset(self::$subscriptions[$subscription])) {
            return;
        }
        NativeModules::call('incoming-share', 'next', [], static function ($result) use ($subscription): void {
            if (!isset(self::$subscriptions[$subscription])) {
                return;
            }
            if ($result->status === ModuleResultStatus::Failure) {
                unset(self::$subscriptions[$subscription]);
                throw new RuntimeException($result->payload);
            }
            $share = self::decode($result->payload);
            if ($share instanceof IncomingShare) {
                self::$subscriptions[$subscription]($share);
            }
            self::arm($subscription);
        });
    }

    private static function decode(string $payload): ?IncomingShare
    {
        $values = Wire::decodeMap($payload);
        if (!(bool) ($values['available'] ?? false)) {
            return null;
        }
        try {
            $items = json_decode((string) ($values['files'] ?? '[]'), true, flags: JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new RuntimeException('Native incoming-share payload is invalid.', previous: $error);
        }
        if (!is_array($items) || !array_is_list($items)) {
            throw new RuntimeException('Native incoming-share payload is invalid.');
        }
        $files = [];
        foreach ($items as $item) {
            if (!is_array($item)) {
                continue;
            }
            $files[] = new FileReference(
                path: (string) ($item['path'] ?? ''),
                name: (string) ($item['name'] ?? ''),
                mimeType: (string) ($item['mimeType'] ?? 'application/octet-stream'),
                size: max(0, (int) ($item['size'] ?? 0)),
            );
        }

        return new IncomingShare(
            text: (string) ($values['text'] ?? ''),
            subject: (string) ($values['subject'] ?? ''),
            mimeType: (string) ($values['mimeType'] ?? ''),
            files: $files,
        );
    }
}

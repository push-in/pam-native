<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\NativeOperation;
use Pam\Native\Navigation\Navigator;
use LogicException;
use RuntimeException;

final class Linking
{
    private static int $nextSubscription = 1;

    /** @var array<int, Closure(string): void> */
    private static array $subscriptions = [];

    private function __construct()
    {
    }

    public static function open(string $url, ?Closure $opened = null): int
    {
        return Runtime::callNative(
            NativeOperation::OpenUrl,
            Wire::map(['url' => $url]),
            static function (ModuleResultStatus $status, string $payload) use ($opened): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $opened?->__invoke();
            },
        );
    }

    /** @param Closure(bool): void $callback */
    public static function canOpen(string $url, Closure $callback): int
    {
        return Runtime::callNative(
            NativeOperation::CanOpenUrl,
            Wire::map(['url' => $url]),
            static function (ModuleResultStatus $status, string $payload) use ($callback): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $values = Wire::decodeMap($payload);
                $callback((bool) ($values['supported'] ?? false));
            },
        );
    }

    /** @param Closure(?string): void $callback */
    public static function initial(Closure $callback): int
    {
        return NativeModules::call(
            'linking',
            'initialUrl',
            [],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $values = Wire::decodeMap($result->payload);
                $url = trim((string) ($values['url'] ?? ''));
                $callback($url !== '' ? $url : null);
            },
        );
    }

    /** @param Closure(string): void $callback */
    public static function listen(Closure $callback): int
    {
        if (self::$subscriptions !== []) {
            throw new LogicException('Only one Linking listener may be active.');
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

    /** @param null|Closure(string, bool): void $callback */
    public static function listenAndRoute(
        Navigator $navigator,
        ?Closure $callback = null,
    ): int {
        self::initial(static function (?string $url) use ($navigator, $callback): void {
            if ($url === null) {
                return;
            }
            $handled = $navigator->open($url);
            $callback?->__invoke($url, $handled);
        });

        return self::listen(static function (string $url) use ($navigator, $callback): void {
            $handled = $navigator->open($url);
            $callback?->__invoke($url, $handled);
        });
    }

    private static function arm(int $subscription): void
    {
        if (!isset(self::$subscriptions[$subscription])) {
            return;
        }
        NativeModules::call(
            'linking',
            'nextUrl',
            [],
            static function ($result) use ($subscription): void {
                if (!isset(self::$subscriptions[$subscription])) {
                    return;
                }
                if ($result->status === ModuleResultStatus::Failure) {
                    unset(self::$subscriptions[$subscription]);
                    throw new RuntimeException($result->payload);
                }
                $values = Wire::decodeMap($result->payload);
                $url = trim((string) ($values['url'] ?? ''));
                if ($url !== '') {
                    self::$subscriptions[$subscription]($url);
                }
                self::arm($subscription);
            },
        );
    }
}

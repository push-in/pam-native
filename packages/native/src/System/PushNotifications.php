<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\PushToken;
use Pam\Native\PushProvider;
use Pam\Native\PushEventType;
use Pam\Native\PushMessage;
use Pam\Native\Navigation\Navigator;
use RuntimeException;
use LogicException;

final class PushNotifications
{
    private static int $nextSubscription = 1;

    /** @var array<int, Closure(PushMessage): void> */
    private static array $subscriptions = [];

    private function __construct()
    {
    }

    /** @param Closure(PushToken): void $callback */
    public static function register(Closure $callback): int
    {
        return NativeModules::call(
            'notifications',
            'registerPush',
            [],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $values = Wire::decodeMap($result->payload);
                $callback(new PushToken(
                    value: (string) ($values['token'] ?? ''),
                    provider: PushProvider::from((int) ($values['provider'] ?? 0)),
                ));
            },
        );
    }

    /** @param Closure(PushMessage): void $callback */
    public static function listen(Closure $callback): int
    {
        if (self::$subscriptions !== []) {
            throw new LogicException('Only one PushNotifications listener may be active.');
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

    /** @param null|Closure(PushMessage): void $callback */
    public static function listenAndRoute(
        Navigator $navigator,
        ?Closure $callback = null,
    ): int {
        return self::listen(static function (PushMessage $message) use ($navigator, $callback): void {
            if ($message->event === PushEventType::Opened && $message->deepLink !== null) {
                $navigator->open($message->deepLink);
            }
            $callback?->__invoke($message);
        });
    }

    private static function arm(int $subscription): void
    {
        if (!isset(self::$subscriptions[$subscription])) {
            return;
        }
        NativeModules::call('notifications', 'nextPushEvent', [], static function ($result) use ($subscription): void {
            if (!isset(self::$subscriptions[$subscription])) {
                return;
            }
            if ($result->status === ModuleResultStatus::Failure) {
                unset(self::$subscriptions[$subscription]);
                throw new RuntimeException($result->payload);
            }
            $values = Wire::decodeMap($result->payload);
            $data = json_decode((string) ($values['data'] ?? '{}'), true, flags: JSON_THROW_ON_ERROR);
            $message = new PushMessage(
                event: PushEventType::from((int) ($values['event'] ?? 1)),
                id: (string) ($values['id'] ?? ''),
                title: (string) ($values['title'] ?? ''),
                body: (string) ($values['body'] ?? ''),
                data: is_array($data) ? $data : [],
                deepLink: ($values['deepLink'] ?? '') !== '' ? (string) $values['deepLink'] : null,
            );
            self::$subscriptions[$subscription]($message);
            self::arm($subscription);
        });
    }
}

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
use Pam\Native\Notifications\ConversationNotification;
use Pam\Native\Notifications\NotificationAction;
use Pam\Native\Notifications\NotificationActionType;

final class Notifications
{
    private static int $nextActionSubscription = 1;

    /** @var array<int, Closure(NotificationAction): void> */
    private static array $actionSubscriptions = [];

    private function __construct()
    {
    }

    /** Starts a MessagingStyle conversation notification identified by $key. */
    public static function conversation(string $key): ConversationNotification
    {
        return new ConversationNotification($key);
    }

    /** Removes a conversation notification and forgets its message history. */
    public static function cancelGroup(string $key, ?Closure $callback = null): int
    {
        new ConversationNotification($key);

        return self::call(
            'cancelConversation',
            ['key' => $key],
            static fn (array $_): mixed => $callback?->__invoke(),
        );
    }

    /**
     * Receives inline replies and mark-as-read taps. Actions performed while
     * PHP was not running are queued natively and delivered on subscription.
     *
     * @param Closure(NotificationAction): void $callback
     */
    public static function onAction(Closure $callback): int
    {
        // A single native queue feeds one listener; a new listener (for example
        // after hot reload) replaces the previous one.
        self::$actionSubscriptions = [];
        $subscription = self::$nextActionSubscription++;
        self::$actionSubscriptions[$subscription] = $callback;
        self::armActions($subscription);

        return $subscription;
    }

    public static function offAction(int $subscription): void
    {
        unset(self::$actionSubscriptions[$subscription]);
    }

    /** @internal */
    public static function resetRuntime(): void
    {
        self::$actionSubscriptions = [];
        self::$nextActionSubscription = 1;
    }

    private static function armActions(int $subscription): void
    {
        if (!isset(self::$actionSubscriptions[$subscription])) {
            return;
        }
        NativeModules::call('notifications', 'nextAction', [], static function ($result) use ($subscription): void {
            if (!isset(self::$actionSubscriptions[$subscription])) {
                return;
            }
            if ($result->status === ModuleResultStatus::Failure) {
                unset(self::$actionSubscriptions[$subscription]);

                return;
            }
            $values = Wire::decodeMap($result->payload);
            $type = NotificationActionType::tryFrom((int) ($values['type'] ?? 0));
            if ($type !== null) {
                $data = json_decode((string) ($values['data'] ?? '{}'), true);
                self::$actionSubscriptions[$subscription](new NotificationAction(
                    type: $type,
                    conversation: (string) ($values['conversation'] ?? ''),
                    text: (string) ($values['text'] ?? ''),
                    data: is_array($data) ? $data : [],
                    deepLink: ($values['deepLink'] ?? '') !== '' ? (string) $values['deepLink'] : null,
                    handledNatively: (bool) ($values['handledNatively'] ?? false),
                    statusCode: (int) ($values['statusCode'] ?? 0),
                    timestamp: (int) ($values['timestamp'] ?? 0),
                ));
            }
            self::armActions($subscription);
        });
    }

    /**
     * @param Closure(bool): void $callback
     * @param Closure(string): void|null $failure
     */
    public static function requestPermission(Closure $callback, ?Closure $failure = null): int
    {
        return self::call('requestPermission', [], static function (array $values) use ($callback): void {
            $callback((bool) ($values['granted'] ?? false));
        }, $failure);
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

    private static function call(string $method, array $payload, Closure $callback, ?Closure $failure = null): int
    {
        return NativeModules::call(
            'notifications',
            $method,
            $payload,
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);
                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $callback($result->payload === '' ? [] : Wire::decodeMap($result->payload));
            },
        );
    }
}

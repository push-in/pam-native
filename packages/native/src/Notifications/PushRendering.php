<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

use Pam\Native\Modules\NativeModules;

/**
 * Native rendering of data-only pushes while PHP is suspended or not running.
 *
 *     PushRendering::forType('chat.message')
 *         ->conversation('{chat_id}', title: '{chat_title}', group: '{is_group}')
 *         ->message(sender: '{sender_name}', text: '{body}', avatar: '{sender_avatar}', timestamp: '{sent_at}', id: '{message_id}')
 *         ->reply('Reply', ActionEndpoint::post('https://api.example.com/chats/{chat_id}/messages')
 *             ->bearerFromStorage('auth.token')->json(['body' => '{reply}']))
 *         ->markRead('Mark as read')
 *         ->deepLink('myapp://chat/{chat_id}')
 *         ->suppressWhenRoute('chat/{chat_id}')
 *         ->register();
 *
 * Android only (Firebase data messages). Pushes with a `notification` payload
 * are displayed by the OS and never reach the rules.
 */
final class PushRendering
{
    private static bool $tracking = false;

    /** @var array{name: string, params: string, path: string}|null */
    private static ?array $reported = null;

    private function __construct()
    {
    }

    /** Matches pushes whose data[$field] equals $type. */
    public static function forType(string $type, string $field = 'type'): PushRenderingRule
    {
        return new PushRenderingRule($type, $field);
    }

    public static function forget(string $type, string $field = 'type'): int
    {
        return NativeModules::call('notifications', 'forgetPushRendering', ['type' => $type, 'field' => $field], static fn (): null => null);
    }

    public static function clear(): int
    {
        return NativeModules::call('notifications', 'forgetPushRendering', ['all' => true], static fn (): null => null);
    }

    /** @internal Enables focused-route reporting once a rule needs it. */
    public static function trackRoutes(): void
    {
        self::$tracking = true;
    }

    /**
     * @internal Called by navigators when a route gains focus.
     * @param array<string, mixed> $params
     */
    public static function routeFocused(string $name, array $params, ?string $path): void
    {
        if (!self::$tracking) {
            return;
        }
        $scalars = array_filter($params, static fn (mixed $value): bool => is_scalar($value));
        $route = [
            'name' => $name,
            'params' => json_encode((object) $scalars, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE),
            'path' => $path ?? '',
        ];
        if ($route === self::$reported) {
            return;
        }
        self::$reported = $route;
        NativeModules::call('notifications', 'setActiveRoute', $route, static fn (): null => null);
    }

    /** @internal */
    public static function resetRuntime(): void
    {
        self::$tracking = false;
        self::$reported = null;
    }
}

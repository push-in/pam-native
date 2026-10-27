<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

use BackedEnum;
use InvalidArgumentException;
use Pam\Native\Modules\NativeModules;

use function is_scalar;

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
 *
 * A screen known by more than one id (opened by a legacy id, loaded under its
 * canonical one) adds the other ids to its focused route with routeAliases(),
 * and the rule suppresses on either parameter:
 *
 *     $handle = PushRendering::routeAliases('chat', ['chatId' => $routeId], ['loadedChatId' => $loadedId]);
 *     ...->suppressWhenRoute('chat', ['chatId' => '{chat_id}'])
 *         ->suppressWhenRoute('chat', ['loadedChatId' => '{chat_id}']);
 *     PushRendering::forgetRouteAliases($handle); // when the screen goes away
 */
final class PushRendering
{
    private static bool $tracking = false;

    /** @var array{name: string, params: string, path: string}|null */
    private static ?array $reported = null;

    /** @var array{name: string, params: array<string, mixed>, path: ?string}|null */
    private static ?array $focused = null;

    /** @var array<int, array{route: string, match: array<string, string>, aliases: array<string, string>}> */
    private static array $aliases = [];

    private static int $nextAlias = 1;

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
        self::$focused = ['name' => $name, 'params' => $params, 'path' => $path];
        if (!self::$tracking) {
            return;
        }
        $scalars = array_filter($params, static fn (mixed $value): bool => is_scalar($value));
        foreach (self::$aliases as $alias) {
            if ($alias['route'] !== $name) {
                continue;
            }
            foreach ($alias['match'] as $key => $value) {
                if (!array_key_exists($key, $scalars) || (string) $scalars[$key] !== $value) {
                    continue 2;
                }
            }
            // Route parameters always win over an alias of the same name.
            $scalars += $alias['aliases'];
        }
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

    /**
     * Extra parameters reported for the focused route whose name is $route and
     * whose parameters contain $match, so suppressWhenRoute() rules can match
     * them (e.g. the canonical id a screen loaded besides its route id).
     * Re-reports the focused route at once when it is concerned.
     *
     * @param array<string, scalar> $match
     * @param array<string, scalar> $aliases
     * @return int handle for forgetRouteAliases()
     */
    public static function routeAliases(string|BackedEnum $route, array $match, array $aliases): int
    {
        $route = $route instanceof BackedEnum ? (string) $route->value : $route;
        if ($route === '' || strlen($route) > 1_024) {
            throw new InvalidArgumentException('Aliased routes must contain between 1 and 1024 bytes.');
        }
        $handle = self::$nextAlias++;
        self::$aliases[$handle] = [
            'route' => $route,
            'match' => self::strings($match),
            'aliases' => array_filter(self::strings($aliases), static fn (string $value): bool => $value !== ''),
        ];
        self::refocus();

        return $handle;
    }

    /** Drops aliases registered by routeAliases(); unknown handles are ignored. */
    public static function forgetRouteAliases(int $handle): void
    {
        if (!isset(self::$aliases[$handle])) {
            return;
        }
        unset(self::$aliases[$handle]);
        self::refocus();
    }

    /** @internal */
    public static function resetRuntime(): void
    {
        self::$tracking = false;
        self::$reported = null;
        self::$focused = null;
        self::$aliases = [];
        self::$nextAlias = 1;
    }

    private static function refocus(): void
    {
        if (self::$focused !== null) {
            self::routeFocused(self::$focused['name'], self::$focused['params'], self::$focused['path']);
        }
    }

    /**
     * @param array<array-key, mixed> $values
     * @return array<string, string>
     */
    private static function strings(array $values): array
    {
        $strings = [];
        foreach ($values as $key => $value) {
            if (is_scalar($value) && (string) $key !== '') {
                $strings[(string) $key] = (string) $value;
            }
        }

        return $strings;
    }
}

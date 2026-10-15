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

    /** @param null|Closure(string): void $failure */
    public static function open(
        string $url,
        ?Closure $opened = null,
        ?Closure $failure = null,
    ): int
    {
        return Runtime::callNative(
            NativeOperation::OpenUrl,
            Wire::map(['url' => $url]),
            static function (ModuleResultStatus $status, string $payload) use ($opened, $failure): void {
                if ($status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($payload);

                        return;
                    }
                    throw new RuntimeException($payload);
                }

                $opened?->__invoke();
            },
        );
    }

    /**
     * @param Closure(bool): void $callback
     * @param null|Closure(string): void $failure
     */
    public static function canOpen(
        string $url,
        Closure $callback,
        ?Closure $failure = null,
    ): int
    {
        return Runtime::callNative(
            NativeOperation::CanOpenUrl,
            Wire::map(['url' => $url]),
            static function (ModuleResultStatus $status, string $payload) use ($callback, $failure): void {
                if ($status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($payload);

                        return;
                    }
                    throw new RuntimeException($payload);
                }

                $values = Wire::decodeMap($payload);
                $callback((bool) ($values['supported'] ?? false));
            },
        );
    }

    /**
     * @param Closure(?string): void $callback
     * @param null|Closure(string): void $failure
     */
    public static function initial(Closure $callback, ?Closure $failure = null): int
    {
        return NativeModules::call(
            'linking',
            'initialUrl',
            [],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
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

    /** @internal */
    public static function resetRuntime(): void
    {
        self::$subscriptions = [];
        self::$nextSubscription = 1;
    }

    /** @param null|Closure(string, bool): void $callback */
    /** The launch URL already routed by {@see routeLaunchUrl()} (initial() skips it once). */
    private static ?string $routedLaunchUrl = null;
    private static bool $launchUrlTaken = false;

    /**
     * The URL the app was launched with, as the host reported it at boot
     * (`PAM_NATIVE_LAUNCH_URL`), once per process: later PHP requests (hot
     * reload) start without it.
     */
    public static function takeLaunchUrl(): ?string
    {
        if (self::$launchUrlTaken) {
            return null;
        }
        self::$launchUrlTaken = true;
        $url = getenv('PAM_NATIVE_LAUNCH_URL');
        if (!is_string($url) || trim($url) === '') {
            return null;
        }
        putenv('PAM_NATIVE_LAUNCH_URL');

        return trim($url);
    }

    /**
     * Opens the launch URL on $navigator before the first frame, like React
     * Navigation's linking resolves the initial state: a deep-linked cold
     * start renders its screen first instead of the initial route followed
     * by a navigation. The asynchronous initial-URL delivery of
     * {@see listenAndRoute()} then reports it without opening it again.
     */
    public static function routeLaunchUrl(Navigator $navigator): void
    {
        $url = self::takeLaunchUrl();
        if ($url !== null && $navigator->openInitial($url)) {
            self::$routedLaunchUrl = $url;
        }
    }

    public static function listenAndRoute(
        Navigator $navigator,
        ?Closure $callback = null,
    ): int {
        self::initial(static function (?string $url) use ($navigator, $callback): void {
            if ($url === null) {
                return;
            }
            if (self::$routedLaunchUrl !== null && $url === self::$routedLaunchUrl) {
                self::$routedLaunchUrl = null;
                $callback?->__invoke($url, true);

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

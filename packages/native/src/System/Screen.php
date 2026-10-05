<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;

/**
 * Screen privacy. Secure mode blocks screenshots, screen recording and
 * recent-apps previews (Android FLAG_SECURE).
 *
 * Prefer route-scoped protection, which clears itself when the route is popped:
 *
 *     Route::screen('wallet', WalletScreen::class)->secure();
 *
 * Screen::secure() adds an explicit, app-wide claim on top of route claims.
 * iOS has no public equivalent; the callback then receives false.
 */
final class Screen
{
    private static bool $manual = false;

    /** @var array<string, true> */
    private static array $routes = [];

    private static ?bool $applied = null;

    private function __construct()
    {
    }

    /** @param null|Closure(bool): void $callback Receives whether the platform enforces secure mode. */
    public static function secure(bool $enabled = true, ?Closure $callback = null): void
    {
        self::$manual = $enabled;
        self::sync($callback, force: $callback !== null);
    }

    public static function isSecure(): bool
    {
        return self::$manual || self::$routes !== [];
    }

    /** @internal Navigators report whether their active route requires secure mode. */
    public static function claimForRoute(string $navigator, bool $secure): void
    {
        if ($secure) {
            self::$routes[$navigator] = true;
        } else {
            unset(self::$routes[$navigator]);
        }
        self::sync();
    }

    /** @internal */
    public static function resetRuntime(): void
    {
        self::$manual = false;
        self::$routes = [];
        self::$applied = null;
    }

    private static function sync(?Closure $callback = null, bool $force = false): void
    {
        $enabled = self::isSecure();
        if (!$force && self::$applied === $enabled) {
            return;
        }
        if (!$force && self::$applied === null && !$enabled) {
            self::$applied = false;

            return;
        }
        self::$applied = $enabled;
        NativeModules::call('window', 'secure', ['enabled' => $enabled], static function ($result) use ($callback): void {
            if ($result->status === ModuleResultStatus::Failure) {
                $callback?->__invoke(false);

                return;
            }
            $values = Wire::decodeMap($result->payload);
            $callback?->__invoke((bool) ($values['supported'] ?? false) && (bool) ($values['enabled'] ?? false));
        });
    }
}

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
 * Independent features (an app lock, a screen showing a secret) use named
 * claims: Screen::claim('app-lock') / Screen::release('app-lock'); secure
 * mode stays on while any claim is held.
 * iOS has no public equivalent; the callback then receives false.
 */
final class Screen
{
    private static bool $manual = false;

    /** @var array<string, true> */
    private static array $routes = [];

    /** @var array<string, true> */
    private static array $claims = [];

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
        return self::$manual || self::$routes !== [] || self::$claims !== [];
    }

    /**
     * Holds secure mode on behalf of $owner until release($owner).
     *
     * @param null|Closure(bool): void $callback Receives whether the platform enforces secure mode.
     */
    public static function claim(string $owner, ?Closure $callback = null): void
    {
        if ($owner === '') {
            throw new \InvalidArgumentException('A secure-screen claim needs an owner.');
        }
        self::$claims[$owner] = true;
        self::sync($callback, force: $callback !== null);
    }

    /** @param null|Closure(bool): void $callback */
    public static function release(string $owner, ?Closure $callback = null): void
    {
        unset(self::$claims[$owner]);
        self::sync($callback, force: $callback !== null);
    }

    public static function claimed(string $owner): bool
    {
        return isset(self::$claims[$owner]);
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
        self::$claims = [];
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

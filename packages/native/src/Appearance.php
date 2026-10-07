<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Modules\NativeModules;

use function array_key_exists;
use function is_string;

/**
 * First-class light/dark appearance control.
 *
 * The native host persists the selected {@see AppearanceMode} and applies it
 * to the window before the first PHP frame, so `@media (prefers-color-scheme)`
 * and {@see WindowMetrics::$appearance} already describe the effective scheme
 * while the application boots. Every read is synchronous.
 */
final class Appearance
{
    private const string MODULE = 'appearance';

    private static ?AppearanceMode $mode = null;

    private static ?UserInterfaceAppearance $system = null;

    private static bool $systemResolved = false;

    private static int $pendingNativeWrites = 0;

    private static int $nextSubscription = 1;

    /** @var array<int, Closure(UserInterfaceAppearance, AppearanceMode): void> */
    private static array $listeners = [];

    private function __construct()
    {
    }

    /** The persisted preference: System, Light or Dark. */
    public static function mode(): AppearanceMode
    {
        return self::$mode ??= self::bootMode();
    }

    /** The effective scheme used by this frame, after applying the preference. */
    public static function current(): UserInterfaceAppearance
    {
        return Runtime::windowMetrics()->appearance;
    }

    public static function isDark(): bool
    {
        return self::current() === UserInterfaceAppearance::Dark;
    }

    /**
     * The operating-system scheme, or null while the host cannot observe it
     * (for example, an Android 12+ per-app override with a scheduled system
     * dark theme).
     */
    public static function system(): ?UserInterfaceAppearance
    {
        if (!self::$systemResolved) {
            self::$systemResolved = true;
            self::$system = self::bootSystemAppearance();
        }

        return self::$system;
    }

    /**
     * Selects and persists the application's appearance. The PHP tree restyles
     * immediately; the native window, system bars and next cold start follow.
     *
     * @param (Closure(bool): void)|null $completion receives whether the host persisted it
     */
    public static function set(AppearanceMode $mode, ?Closure $completion = null): void
    {
        $previousMode = self::mode();
        $previous = self::current();
        self::$mode = $mode;
        $effective = self::resolve($mode, self::system(), $previous);
        if ($effective !== $previous) {
            Runtime::replaceAppearance($effective);
        }
        if ($effective !== $previous || $mode !== $previousMode) {
            self::notify($effective, $mode);
        }

        self::$pendingNativeWrites++;
        NativeModules::call(
            self::MODULE,
            'set',
            ['mode' => $mode->value],
            static function ($result) use ($completion): void {
                self::$pendingNativeWrites = max(0, self::$pendingNativeWrites - 1);
                $completion?->__invoke($result->succeeded());
            },
        );
    }

    /**
     * @param Closure(UserInterfaceAppearance, AppearanceMode): void $listener
     */
    public static function onChange(Closure $listener): int
    {
        $subscription = self::$nextSubscription++;
        self::$listeners[$subscription] = $listener;

        return $subscription;
    }

    public static function unsubscribe(int $subscription): void
    {
        unset(self::$listeners[$subscription]);
    }

    /** @internal Boot-time scheme exported by the host before PHP starts. */
    public static function bootAppearance(): UserInterfaceAppearance
    {
        return match (self::bootMode()) {
            AppearanceMode::Light => UserInterfaceAppearance::Light,
            AppearanceMode::Dark => UserInterfaceAppearance::Dark,
            AppearanceMode::System => getenv('PAM_SYSTEM_DARK') === '1'
                ? UserInterfaceAppearance::Dark
                : UserInterfaceAppearance::Light,
        };
    }

    /**
     * @internal Reconciles a host metrics event with the local preference.
     *
     * Runtime has already installed the event's metrics. The host is the
     * authority once it reports the same preference PHP holds; while a local
     * write is in flight, or for hosts that do not report a preference, the
     * local preference is applied over the reported system scheme.
     *
     * @param array<string, string|int|float|bool> $values
     */
    public static function synchronize(array $values, UserInterfaceAppearance $previous): void
    {
        $previousMode = self::mode();
        $reported = Runtime::windowMetrics()->appearance;
        $reportedMode = AppearanceMode::tryFrom((int) ($values['appearanceMode'] ?? 0));
        if ($reportedMode !== null && self::$pendingNativeWrites === 0) {
            self::$mode = $reportedMode;
        }
        if (array_key_exists('systemAppearance', $values)) {
            self::$systemResolved = true;
            self::$system = UserInterfaceAppearance::tryFrom((int) $values['systemAppearance']);
        } elseif ($reportedMode === null) {
            self::$systemResolved = true;
            self::$system = $reported;
        }
        $mode = self::mode();
        $effective = $reportedMode === $mode
            ? $reported
            : self::resolve($mode, self::$system, $reported);
        if ($effective !== $reported) {
            Runtime::replaceAppearance($effective, render: false);
        }
        if ($effective !== $previous || $mode !== $previousMode) {
            self::notify($effective, $mode);
        }
    }

    /** @internal */
    public static function resetRuntime(): void
    {
        self::$mode = null;
        self::$system = null;
        self::$systemResolved = false;
        self::$pendingNativeWrites = 0;
        self::$listeners = [];
        self::$nextSubscription = 1;
    }

    private static function notify(UserInterfaceAppearance $appearance, AppearanceMode $mode): void
    {
        foreach (self::$listeners as $listener) {
            $listener($appearance, $mode);
        }
    }

    private static function resolve(
        AppearanceMode $mode,
        ?UserInterfaceAppearance $system,
        UserInterfaceAppearance $fallback,
    ): UserInterfaceAppearance {
        return match ($mode) {
            AppearanceMode::Light => UserInterfaceAppearance::Light,
            AppearanceMode::Dark => UserInterfaceAppearance::Dark,
            AppearanceMode::System => $system ?? $fallback,
        };
    }

    private static function bootMode(): AppearanceMode
    {
        $value = getenv('PAM_APPEARANCE_MODE');

        return is_string($value) && preg_match('/^[1-9]$/D', $value) === 1
            ? AppearanceMode::tryFrom((int) $value) ?? AppearanceMode::System
            : AppearanceMode::System;
    }

    private static function bootSystemAppearance(): ?UserInterfaceAppearance
    {
        $value = getenv('PAM_SYSTEM_APPEARANCE');
        if (is_string($value) && preg_match('/^[0-9]$/D', $value) === 1) {
            return UserInterfaceAppearance::tryFrom((int) $value);
        }

        return self::bootMode() === AppearanceMode::System ? self::bootAppearance() : null;
    }
}

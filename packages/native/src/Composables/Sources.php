<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Pam\Native\App;
use Pam\Native\Appearance;
use Pam\Native\AppearanceMode;
use Pam\Native\AppState;
use Pam\Native\DeviceStatus;
use Pam\Native\I18n\I18n;
use Pam\Native\System\DeviceInfo;
use Pam\Native\Internal\ComponentLifecycle;
use Pam\Native\Internal\Runtime;
use Pam\Native\KeyboardMetrics;
use Pam\Native\System\DeviceStatus as DeviceStatusModule;
use Pam\Native\System\Keyboard;
use Pam\Native\UserInterfaceAppearance;
use Pam\Native\WindowMetrics;

/**
 * The shared push sources behind the framework composables.
 *
 * @internal
 */
final class Sources
{
    private function __construct()
    {
    }

    /** @return SharedSource<array{0: UserInterfaceAppearance, 1: AppearanceMode}> */
    public static function appearance(): SharedSource
    {
        return SharedSource::named(
            'appearance',
            static fn (): array => [Appearance::current(), Appearance::mode()],
            static function (\Closure $push): \Closure {
                $id = Appearance::onChange(static function (UserInterfaceAppearance $appearance, AppearanceMode $mode) use ($push): void {
                    $push([$appearance, $mode]);
                });

                return static fn () => Appearance::unsubscribe($id);
            },
        );
    }

    /** @return SharedSource<WindowMetrics> */
    public static function window(): SharedSource
    {
        return SharedSource::named(
            'window',
            static fn (): WindowMetrics => Runtime::windowMetrics(),
            static function (\Closure $push): \Closure {
                $id = Runtime::onWindowMetrics($push);

                return static fn () => Runtime::offWindowMetrics($id);
            },
        );
    }

    /** @return SharedSource<KeyboardMetrics> */
    public static function keyboard(): SharedSource
    {
        return SharedSource::named(
            'keyboard',
            static fn (): KeyboardMetrics => Keyboard::metrics(),
            static function (\Closure $push): \Closure {
                $id = Keyboard::onChange($push);

                return static fn () => Keyboard::offChange($id);
            },
        );
    }

    /** @return SharedSource<AppState> */
    public static function appState(): SharedSource
    {
        return SharedSource::named(
            'appState',
            static fn (): AppState => App::state() ?? ComponentLifecycle::currentAppState(),
            static function (\Closure $push): \Closure {
                $id = App::onStateChange($push);

                return static fn () => App::offStateChange($id);
            },
        );
    }

    /** @var DeviceStatus|null last status the host reported, kept across restarts of the watch */
    private static ?DeviceStatus $lastStatus = null;

    /** @return SharedSource<DeviceStatus|null> */
    public static function network(): SharedSource
    {
        return SharedSource::named(
            'network',
            static fn (): ?DeviceStatus => self::$lastStatus,
            static function (\Closure $push): \Closure {
                // The host pushes only changes (network, metered, battery...).
                $id = DeviceStatusModule::watch(static function (DeviceStatus $status) use ($push): void {
                    self::$lastStatus = $status;
                    $push($status);
                }, 2_000);

                return static fn () => DeviceStatusModule::unwatch($id);
            },
        );
    }

    /** @var DeviceInfo|null last device info the host reported (locale, time zone) */
    private static ?DeviceInfo $lastDevice = null;

    /** Asks the host for DeviceInfo while the device source runs; null when stopped. */
    private static ?\Closure $askDevice = null;
    private static bool $deviceAsked = false;

    /**
     * Device locale and time zone. Lazy: nothing is asked until a reader
     * first reads a value (requestDevice()); then once more each time the
     * app returns to the foreground (the user may have changed them in the
     * system settings). Never polled.
     *
     * @return SharedSource<DeviceInfo|null>
     */
    public static function device(): SharedSource
    {
        return SharedSource::named(
            'device',
            static fn (): ?DeviceInfo => self::$lastDevice,
            static function (\Closure $push): \Closure {
                $running = true;
                $ask = static function () use (&$running, $push): void {
                    DeviceInfo::get(static function (DeviceInfo $info) use (&$running, $push): void {
                        if (!$running) return;
                        self::$lastDevice = $info;
                        $push($info);
                    }, static function (): void {
                        // Keep the last known values; the next foreground asks again.
                    });
                };
                self::$askDevice = $ask;
                self::$deviceAsked = false;
                $id = App::onStateChange(static function (AppState $state) use ($ask): void {
                    if ($state === AppState::Active && self::$deviceAsked) $ask();
                });

                return static function () use (&$running, $id): void {
                    $running = false;
                    self::$askDevice = null;
                    self::$deviceAsked = false;
                    App::offStateChange($id);
                };
            },
        );
    }

    /** @internal Called by useLocale()/useTimezone() reads: the first one asks the host. */
    public static function requestDevice(): void
    {
        if (self::$deviceAsked || self::$askDevice === null) return;
        self::$deviceAsked = true;
        (self::$askDevice)();
    }

    /** @internal Tests: the device locale and time zone as if the host reported them. */
    public static function reportDevice(DeviceInfo $info): void
    {
        self::$lastDevice = $info;
        self::device()->signal->set($info);
    }

    /**
     * Locale and time zone overrides (I18n::use(), I18n::useTimezone()).
     *
     * @return SharedSource<array{0: ?string, 1: ?string}>
     */
    public static function i18n(): SharedSource
    {
        return SharedSource::named(
            'i18n',
            static fn (): array => [I18n::preferredLocale(), I18n::timezone()],
            static function (\Closure $push): \Closure {
                $id = I18n::onChange(static fn () => $push([I18n::preferredLocale(), I18n::timezone()]));

                return static fn () => I18n::offChange($id);
            },
        );
    }

    /** @internal */
    public static function reset(): void
    {
        self::$lastDevice = null;
        self::$askDevice = null;
        self::$deviceAsked = false;
        self::$lastStatus = null;
        SharedSource::reset();
    }
}

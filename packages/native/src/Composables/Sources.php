<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Pam\Native\App;
use Pam\Native\Appearance;
use Pam\Native\AppearanceMode;
use Pam\Native\AppState;
use Pam\Native\DeviceStatus;
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

    /** @internal */
    public static function reset(): void
    {
        self::$lastStatus = null;
        SharedSource::reset();
    }
}

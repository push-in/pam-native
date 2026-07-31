<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Pam\Native\App;
use Pam\Native\Internal\Runtime;
use Pam\Native\NativeOperation;
use WeakReference;

final class NavigationBackCoordinator
{
    /** @var WeakReference<Navigator>|null */
    private static ?WeakReference $root = null;
    private static bool $explicitRoot = false;

    public static function register(Navigator $navigator, bool $explicitRoot = false): void
    {
        if (self::$root?->get() === null) self::$explicitRoot = false;
        if (self::$explicitRoot && !$explicitRoot) return;
        self::$root = WeakReference::create($navigator);
        self::$explicitRoot = $explicitRoot;
        App::onBack(static function (): void {
            $root = self::$root?->get();
            if ($root instanceof Navigator && $root->dispatchSystemBack()) return;
            Runtime::callNative(
                NativeOperation::CloseApp,
                '',
                static function (): void {
                },
            );
        });
    }
}

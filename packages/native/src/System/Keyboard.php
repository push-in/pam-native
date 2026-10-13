<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Component;
use Pam\Native\Internal\DependencyTracker;
use Pam\Native\Internal\Runtime;
use Pam\Native\NativeOperation;
use ReflectionFunction;

final class Keyboard
{
    private static bool $visible = false;
    private static bool $active = true;
    private static int $nextSubscription = 1;

    /** @var array<int, Closure(bool): void> */
    private static array $listeners = [];

    /** Last native visibility; input focus does not imply a visible keyboard. */
    public static function isVisible(): bool
    {
        return self::$visible;
    }

    /** @param Closure(bool): void $listener Called only when visibility changes. */
    public static function onVisibilityChange(Closure $listener): int
    {
        $id = self::$nextSubscription++;
        $owner = (new ReflectionFunction($listener))->getClosureThis();
        self::$listeners[$id] = static function (bool $visible) use ($listener, $owner): void {
            if ($owner instanceof Component) {
                DependencyTracker::markDirty($owner);
            } else {
                DependencyTracker::invalidateAll();
            }
            $listener($visible);
        };

        return $id;
    }

    public static function offVisibilityChange(int $subscription): void
    {
        unset(self::$listeners[$subscription]);
    }

    /** @internal Native hosts deduplicate their inset animation frames. */
    public static function synchronize(bool $visible): bool
    {
        $visible = $visible && self::$active;
        if (self::$visible === $visible) {
            return false;
        }
        self::$visible = $visible;
        foreach (array_keys(self::$listeners) as $id) {
            // A previous listener can unsubscribe another during notification.
            (self::$listeners[$id] ?? null)?->__invoke($visible);
        }

        return true;
    }

    /** @internal */
    public static function setActive(bool $active): void
    {
        self::$active = $active;
        if (!$active) {
            self::synchronize(false);
        }
    }

    /** @internal */
    public static function resetRuntime(): void
    {
        self::$visible = false;
        self::$active = true;
        self::$listeners = [];
        self::$nextSubscription = 1;
    }

    private function __construct()
    {
    }

    public static function dismiss(): int
    {
        return Runtime::callNative(
            NativeOperation::KeyboardDismiss,
            '',
            static function (): void {
            },
        );
    }
}

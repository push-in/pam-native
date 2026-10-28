<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Component;
use Pam\Native\Internal\DependencyTracker;
use Pam\Native\Internal\Runtime;
use Pam\Native\KeyboardMetrics;
use Pam\Native\NativeOperation;
use ReflectionFunction;

final class Keyboard
{
    private static bool $visible = false;
    private static bool $active = true;
    private static int $nextSubscription = 1;

    private static float $height = 0.0;
    private static float $lastHeight = 0.0;
    private static int $durationMs = 0;

    /** @var array<int, Closure(bool): void> */
    private static array $listeners = [];

    /** @var array<int, Closure(KeyboardMetrics): void> */
    private static array $metricsListeners = [];

    /** Last native visibility; input focus does not imply a visible keyboard. */
    public static function isVisible(): bool
    {
        return self::$visible;
    }

    /**
     * Settled visible keyboard height in points from the window bottom; 0
     * while hidden (React Native `Keyboard.metrics()?.height`).
     */
    public static function height(): float
    {
        return self::$height;
    }

    /**
     * Height of the last keyboard shown in this process (0 before the first
     * one). A panel that replaces the keyboard (emoji, GIF, attachments)
     * opens at this height so the composer does not jump.
     */
    public static function lastHeight(): float
    {
        return self::$lastHeight;
    }

    public static function metrics(): KeyboardMetrics
    {
        return new KeyboardMetrics(self::$visible, self::$height, self::$durationMs);
    }

    /** @param Closure(bool): void $listener Called only when visibility changes. */
    public static function onVisibilityChange(Closure $listener): int
    {
        $id = self::$nextSubscription++;
        self::$listeners[$id] = self::owned($listener);

        return $id;
    }

    public static function offVisibilityChange(int $subscription): void
    {
        unset(self::$listeners[$subscription]);
    }

    /**
     * React Native `keyboardWillShow`/`keyboardWillChangeFrame`/`keyboardWillHide`
     * in one subscription: called when the keyboard shows, hides or settles at
     * a new height (suggestion strip, emoji keyboard, rotation). Never called
     * per animation frame; use KeyboardAvoidingView for frame-accurate motion.
     *
     * @param Closure(KeyboardMetrics): void $listener
     */
    public static function onChange(Closure $listener): int
    {
        $id = self::$nextSubscription++;
        self::$metricsListeners[$id] = self::owned($listener);

        return $id;
    }

    public static function offChange(int $subscription): void
    {
        unset(self::$metricsListeners[$subscription]);
    }

    /** @internal Visibility-only update (legacy hosts and lifecycle). */
    public static function synchronize(bool $visible): bool
    {
        return self::apply(new KeyboardMetrics($visible, $visible ? self::$height : 0.0));
    }

    /** @internal Native hosts publish settled values only, never animation frames. */
    public static function synchronizePayload(string $payload): bool
    {
        return self::apply(KeyboardMetrics::fromPayload($payload, self::$height));
    }

    private static function apply(KeyboardMetrics $next): bool
    {
        if (!self::$active) {
            $next = new KeyboardMetrics(false, 0.0);
        }
        $current = self::metrics();
        if (!$next->differsFrom($current)) {
            return false;
        }
        $visibilityChanged = $next->visible !== self::$visible;
        self::$visible = $next->visible;
        self::$height = $next->height;
        self::$durationMs = $next->durationMs;
        if ($next->visible && $next->height > 0.0) {
            self::$lastHeight = $next->height;
        }
        if ($visibilityChanged) {
            foreach (array_keys(self::$listeners) as $id) {
                // A previous listener can unsubscribe another during notification.
                (self::$listeners[$id] ?? null)?->__invoke($next->visible);
            }
        }
        $metrics = self::metrics();
        foreach (array_keys(self::$metricsListeners) as $id) {
            (self::$metricsListeners[$id] ?? null)?->__invoke($metrics);
        }

        return true;
    }

    /**
     * A component-bound listener dirties only its component; other callbacks
     * invalidate the tree.
     */
    private static function owned(Closure $listener): Closure
    {
        $owner = (new ReflectionFunction($listener))->getClosureThis();

        return static function (mixed $value) use ($listener, $owner): void {
            if ($owner instanceof Component) {
                DependencyTracker::markDirty($owner);
            } else {
                DependencyTracker::invalidateAll();
            }
            $listener($value);
        };
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
        self::$height = 0.0;
        self::$lastHeight = 0.0;
        self::$durationMs = 0;
        self::$listeners = [];
        self::$metricsListeners = [];
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

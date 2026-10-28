<?php

declare(strict_types=1);

namespace Pam\Native\Testing;

use Closure;
use LogicException;
use Pam\Native\Composables\Sources;
use Pam\Native\Element;
use Pam\Native\I18n\I18n;
use Pam\Native\Internal\DependencyTracker;
use Pam\Native\Internal\Runtime;
use Pam\Native\Renderable;
use Pam\Native\Route;
use Pam\Native\Routing\MiddlewareRegistry;
use Pam\Native\Routing\RouteCollection;
use Pam\Native\UI\Column;
use Throwable;

/**
 * Runs mounted test roots through the real runtime: one root renders every
 * Mounted handle, so setup(), mount(), onMounted(), focus hooks, unmount and
 * the scope release happen exactly as on a device (without a host, frames
 * are encoded and dropped).
 *
 * @internal Use Testing\mount(), Testing\routes() and Testing\reset().
 */
final class Harness
{
    /** @var array<int, Mounted> */
    private static array $mounted = [];
    private static ?Closure $root = null;

    private function __construct()
    {
    }

    public static function mount(Renderable $root): Mounted
    {
        self::boot();
        $mounted = new Mounted($root);
        self::$mounted[spl_object_id($mounted)] = $mounted;
        try {
            self::render();
        } catch (Throwable $error) {
            unset(self::$mounted[spl_object_id($mounted)]);
            try {
                self::render();
            } catch (Throwable) {
                // The original failure is the one the test needs.
            }

            throw $error;
        }

        return $mounted;
    }

    public static function unmount(Mounted $mounted): void
    {
        if (!isset(self::$mounted[spl_object_id($mounted)])) return;
        unset(self::$mounted[spl_object_id($mounted)]);
        self::render();
    }

    public static function mounted(Mounted $mounted): bool
    {
        return isset(self::$mounted[spl_object_id($mounted)]);
    }

    /** A full render pass of every mounted root. */
    public static function render(): void
    {
        if (self::$root === null || Runtime::root() !== self::$root) {
            throw new LogicException('Nothing is mounted by Testing; the runtime was shut down or replaced.');
        }
        DependencyTracker::invalidateAll();
        Runtime::render();
    }

    /** Unmounts everything and resets the runtime and the app-wide registries tests touch. */
    public static function reset(): void
    {
        self::$mounted = [];
        if (self::$root !== null && Runtime::root() === self::$root) {
            try {
                Runtime::render();
            } catch (Throwable) {
                // Shutdown below releases whatever the failed pass left.
            }
        }
        if (Runtime::root() !== null) Runtime::shutdown();
        self::$root = null;
        RouteCollection::activate(null);
        Route::flush();
        MiddlewareRegistry::reset();
        Sources::reset();
        I18n::use(null);
        I18n::useTimezone(null);
    }

    private static function boot(): void
    {
        $current = Runtime::root();
        if ($current !== null && $current === self::$root) return;
        if ($current !== null) {
            throw new LogicException('Testing\mount() needs the runtime for itself, but an app is already running; call Testing\reset() first.');
        }
        self::$root = static function (): Element {
            $children = [];
            foreach (self::$mounted as $mounted) {
                $children[] = $mounted->renderElement();
            }

            return Column::make(...$children)->toElement();
        };
        Runtime::boot(self::$root);
    }
}

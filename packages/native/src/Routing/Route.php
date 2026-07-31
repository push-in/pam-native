<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use LogicException;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\TabNavigator;
use Pam\Native\Renderable;

final class Route
{
    private static ?RouteRegistrar $registrar = null;
    private static ?TabRegistrar $tabRegistrar = null;

    private function __construct()
    {
    }

    public static function tabs(string $name, string $initial, Closure $routes): TabNavigator
    {
        if (self::$registrar !== null || self::$tabRegistrar !== null) {
            throw new LogicException('Named tab routes cannot be declared inside another route group.');
        }
        $registrar = new TabRegistrar($name, $initial);
        self::$tabRegistrar = $registrar;
        try {
            $routes();
        } finally {
            self::$tabRegistrar = null;
        }
        $navigator = $registrar->build();
        Navigation::attach($navigator);
        return $navigator;
    }

    /** @param class-string<Renderable>|Renderable|Closure $screen */
    public static function tab(
        string $name,
        string|Renderable|Closure $screen,
        string $label,
        ?Renderable $icon = null,
        ?string $badge = null,
    ): void {
        $registrar = self::$tabRegistrar
            ?? throw new LogicException('Route::tab() must be declared inside Route::tabs().');
        $content = match (true) {
            $screen instanceof Closure, $screen instanceof Renderable => $screen,
            default => static fn (): Renderable => ScreenFactory::make(
                $screen,
                new \Pam\Native\Navigation\RouteContext($name),
            ),
        };
        $registrar->add($name, $label, $content, $icon, $badge);
    }

    public static function stack(
        string $name,
        string $initial,
        Closure $routes,
        NavigationTransition $transition = NavigationTransition::PlatformDefault,
        int $durationMs = 240,
        bool $restoreState = true,
    ): Navigator {
        if (self::$registrar !== null) {
            throw new LogicException('Named route stacks cannot be declared inside another stack.');
        }

        $registrar = new RouteRegistrar($name, $initial);
        self::$registrar = $registrar;
        try {
            $routes();
        } finally {
            self::$registrar = null;
        }
        $registrar->transitions($transition, $durationMs);
        $registrar->restoreState($restoreState);
        $navigator = $registrar->build();
        Navigation::attach($navigator);

        return $navigator;
    }

    /**
     * @param class-string<Renderable>|Renderable|Closure $screen
     */
    public static function screen(string $name, string|Renderable|Closure $screen): PendingRoute
    {
        return self::add($name, $screen);
    }

    /**
     * @param class-string<Renderable>|Renderable|Closure $screen
     */
    public static function modal(string $name, string|Renderable|Closure $screen): PendingRoute
    {
        return self::add($name, $screen)->fullScreen();
    }

    /**
     * @param class-string<Renderable>|Renderable|Closure $screen
     */
    private static function add(string $name, string|Renderable|Closure $screen): PendingRoute
    {
        $registrar = self::$registrar
            ?? throw new LogicException('Route::screen() must be declared inside Route::stack().');

        $factory = match (true) {
            $screen instanceof Closure => $screen,
            $screen instanceof Renderable => static fn (): Renderable => $screen,
            default => static fn (\Pam\Native\Navigation\RouteContext $route): Renderable => ScreenFactory::make($screen, $route),
        };

        return $registrar->add($name, $factory);
    }
}

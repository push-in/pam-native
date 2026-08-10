<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use BackedEnum;
use Closure;
use LogicException;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\DrawerNavigator;
use Pam\Native\Navigation\DrawerRouter;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\ScreenOptionsPatch;
use Pam\Native\Navigation\TabNavigator;
use Pam\Native\Navigation\TopTabNavigator;
use Pam\Native\Navigation\TopTabRouter;
use Pam\Native\Renderable;

final class Route
{
    private static ?RouteRegistrar $registrar = null;
    private static ?TabRegistrar $tabRegistrar = null;
    private static ?TopTabRegistrar $topTabRegistrar = null;
    private static ?DrawerRegistrar $drawerRegistrar = null;

    private function __construct()
    {
    }

    public static function tabs(
        string $name,
        string|BackedEnum $initial,
        Closure $routes,
    ): TabNavigator
    {
        $nested = self::isNested();
        $parentRegistrar = self::$tabRegistrar;
        $registrar = new TabRegistrar($name, RouteName::value($initial));
        self::$tabRegistrar = $registrar;
        try {
            $routes();
        } finally {
            self::$tabRegistrar = $parentRegistrar;
        }
        $navigator = $registrar->build();
        if (!$nested) Navigation::attach($navigator);
        return $navigator;
    }

    /** @param class-string<Renderable>|Renderable|Closure $screen */
    public static function tab(
        string|BackedEnum $name,
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
        $registrar->add(RouteName::value($name), $label, $content, $icon, $badge);
    }

    /** @param null|Closure(TopTabRouter): TopTabRouter $configure */
    public static function topTabs(
        string $name,
        string|BackedEnum $initial,
        Closure $routes,
        ?Closure $configure = null,
    ): TopTabNavigator {
        $nested = self::isNested();
        $parentRegistrar = self::$topTabRegistrar;
        $registrar = new TopTabRegistrar($name, RouteName::value($initial));
        self::$topTabRegistrar = $registrar;
        try {
            $routes();
        } finally {
            self::$topTabRegistrar = $parentRegistrar;
        }
        $navigator = $registrar->build($configure);
        if (!$nested) Navigation::attach($navigator);
        return $navigator;
    }

    /** @param class-string<Renderable>|Renderable|Closure $screen */
    public static function topTab(
        string|BackedEnum $name,
        string|Renderable|Closure $screen,
        string $label,
        ?Renderable $icon = null,
        ?string $badge = null,
    ): void {
        $registrar = self::$topTabRegistrar
            ?? throw new LogicException('Route::topTab() must be declared inside Route::topTabs().');
        $registrar->add(RouteName::value($name), $label, self::itemContent($name, $screen), $icon, $badge);
    }

    /** @param null|Closure(DrawerRouter): DrawerRouter $configure */
    public static function drawer(
        string $name,
        string|BackedEnum $initial,
        Closure $routes,
        ?Closure $configure = null,
    ): DrawerNavigator {
        $nested = self::isNested();
        $parentRegistrar = self::$drawerRegistrar;
        $registrar = new DrawerRegistrar($name, RouteName::value($initial));
        self::$drawerRegistrar = $registrar;
        try {
            $routes();
        } finally {
            self::$drawerRegistrar = $parentRegistrar;
        }
        $navigator = $registrar->build($configure);
        if (!$nested) Navigation::attach($navigator);
        return $navigator;
    }

    /** @param class-string<Renderable>|Renderable|Closure $screen */
    public static function drawerScreen(
        string|BackedEnum $name,
        string|Renderable|Closure $screen,
        string $label,
        ?Renderable $icon = null,
        ?string $badge = null,
        ?string $group = null,
    ): void {
        $registrar = self::$drawerRegistrar
            ?? throw new LogicException('Route::drawerScreen() must be declared inside Route::drawer().');
        $registrar->add(RouteName::value($name), $label, self::itemContent($name, $screen), $icon, $badge, $group);
    }

    public static function stack(
        string $name,
        string|BackedEnum $initial,
        Closure $routes,
        NavigationTransition $transition = NavigationTransition::PlatformDefault,
        int $durationMs = 240,
        bool $restoreState = true,
        ScreenOptions|Closure|null $options = null,
    ): Navigator {
        $parentRegistrar = self::$registrar;
        $nested = self::isNested();
        $registrar = new RouteRegistrar($name, RouteName::value($initial));
        self::$registrar = $registrar;
        try {
            $routes();
        } finally {
            self::$registrar = $parentRegistrar;
        }
        $registrar->transitions($transition, $durationMs);
        $registrar->restoreState($restoreState);
        $registrar->defaultOptions($options);
        $navigator = $registrar->build();
        if (!$nested) Navigation::attach($navigator);

        return $navigator;
    }

    public static function preset(ScreenOptions|ScreenOptionsPatch|Closure $options): RoutePreset
    {
        return new RoutePreset($options);
    }

    public static function group(ScreenOptionsPatch|RoutePreset|Closure $options, Closure $routes): void
    {
        $registrar = self::$registrar
            ?? throw new LogicException('Route::group() must be declared inside Route::stack().');
        $registrar->beginGroup($options instanceof RoutePreset ? $options->options : $options);
        try {
            $routes();
        } finally {
            $registrar->endGroup();
        }
    }

    /**
     * @param class-string<Renderable>|Renderable|Closure $screen
     */
    public static function screen(string|BackedEnum $name, string|Renderable|Closure $screen): PendingRoute
    {
        return self::add($name, $screen);
    }

    /** Register a child stack, tab, drawer or custom navigator as a screen. */
    public static function navigator(string|BackedEnum $name, Renderable|Closure $navigator): PendingRoute
    {
        return self::add($name, $navigator);
    }

    /**
     * @param class-string<Renderable>|Renderable|Closure $screen
     */
    public static function modal(string|BackedEnum $name, string|Renderable|Closure $screen): PendingRoute
    {
        return self::add($name, $screen)->fullScreen();
    }

    /**
     * @param class-string<Renderable>|Renderable|Closure $screen
     */
    private static function add(string|BackedEnum $name, string|Renderable|Closure $screen): PendingRoute
    {
        $registrar = self::$registrar
            ?? throw new LogicException('Route::screen() must be declared inside Route::stack().');

        $name = RouteName::value($name);
        $factory = match (true) {
            $screen instanceof Closure => $screen,
            $screen instanceof Renderable => static fn (): Renderable => $screen,
            default => static fn (\Pam\Native\Navigation\RouteContext $route): Renderable => ScreenFactory::make($screen, $route),
        };

        return $registrar->add($name, $factory);
    }

    /** @param string|int|float|bool|null ...$params */
    public static function to(string|BackedEnum $name, mixed ...$params): RouteTarget
    {
        return new RouteTarget($name, ...$params);
    }

    private static function isNested(): bool
    {
        return self::$registrar !== null
            || self::$tabRegistrar !== null
            || self::$topTabRegistrar !== null
            || self::$drawerRegistrar !== null;
    }

    /** @param class-string<Renderable>|Renderable|Closure $screen */
    private static function itemContent(
        string|BackedEnum $name,
        string|Renderable|Closure $screen,
    ): Renderable|Closure {
        return match (true) {
            $screen instanceof Closure, $screen instanceof Renderable => $screen,
            default => static fn (): Renderable => ScreenFactory::make(
                $screen,
                new \Pam\Native\Navigation\RouteContext($name),
            ),
        };
    }
}

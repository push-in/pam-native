<?php

declare(strict_types=1);

namespace Pam\Native\Testing;

use Pam\Native\App;
use Pam\Native\AppearanceMode;
use Pam\Native\Component;
use Pam\Native\Composables\Sources;
use Pam\Native\EventKind;
use Pam\Native\Internal\PamPhpRegistry;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\Navigation\RouteContext;
use Pam\Native\Plugin\PluginManager;
use Pam\Native\Renderable;
use Pam\Native\Route;
use Pam\Native\Routing\MiddlewareRegistry;
use Pam\Native\Routing\RouteCollection;
use Pam\Native\Routing\RouteNavigator;
use Pam\Native\System\DeviceInfo;
use Pam\Native\AppState;
use Pam\Native\UserInterfaceAppearance;

if (!\function_exists('Pam\Native\Testing\mount')) {
    /**
     * Mounts a component through the real lifecycle: boot(), setup() with
     * $params as route parameters, mount(), the first render and onMounted().
     * Components given route params also get onFocus(), as from the router.
     *
     * ```php
     * $chat = Testing\mount(ChatScreen::class, params: ['id' => '1']);
     * $chat->render(); $chat->blur(); $chat->unmount();
     * ```
     *
     * @template T of Renderable
     * @param class-string<T>|T $component
     * @param array<string, mixed> $props constructor arguments (or .pam.php props) by name
     * @param array<string, mixed>|null $params route parameters for setup()
     * @return Mounted<T>
     */
    function mount(string|Renderable $component, array $props = [], ?array $params = null): Mounted
    {
        if (is_string($component)) {
            $component = PamPhpRegistry::has($component)
                ? App::make($component, $props)
                : new $component(...$props);
        }
        if ($params !== null && $component instanceof Component) {
            $component->__pamRouteParams($params, new RouteContext($component::class, $params));
        }
        $mounted = Harness::mount($component);
        if ($params !== null && $component instanceof Component) $mounted->focus();

        return $mounted;
    }
}

if (!\function_exists('Pam\Native\Testing\routes')) {
    /**
     * Mounts the app's routes (the real RouteNavigator, activated for
     * navigate()/back()) and returns a tester:
     * `Testing\routes(__DIR__.'/../routes.php')->open('/chat/1')`.
     *
     * Middleware aliases come from $middleware, then from config/middleware.php
     * next to the routes file (or in the project root), the same file App::run()
     * loads. Given a path, the routes are re-registered from scratch; given the
     * result of `require 'routes.php'`, the routes registered so far are used.
     *
     * @param RouteCollection|string|int $routes path of routes.php, its require result, or a collection
     * @param array<string, mixed>|null $middleware extra or overriding aliases
     */
    function routes(RouteCollection|string|int $routes, ?array $middleware = null): RouteTester
    {
        $configRoot = PluginManager::projectRoot();
        if (is_string($routes)) {
            if (!is_file($routes)) throw new \InvalidArgumentException("Routes file {$routes} does not exist.");
            $configRoot = dirname($routes);
            Route::flush();
            $loaded = (static fn (string $file): mixed => require $file)($routes);
            $routes = $loaded instanceof RouteCollection ? $loaded : RouteCollection::current();
        } elseif (!$routes instanceof RouteCollection) {
            $routes = RouteCollection::current();
        }
        if ($middleware !== null) App::middleware($middleware);
        if ($configRoot !== null) MiddlewareRegistry::loadConfig($configRoot.'/config/middleware.php');
        $navigator = new RouteNavigator($routes);
        RouteCollection::activate($navigator);

        return new RouteTester($navigator, Harness::mount($navigator->view()));
    }
}

if (!\function_exists('Pam\Native\Testing\environment')) {
    /**
     * Forces what the host would report, through the same events: the color
     * scheme ('light'|'dark', as the system scheme), safe-area insets
     * (['top' => 24, 'bottom' => 34, ...]), the window (['width', 'height',
     * 'scale', 'fontScale']), the keyboard (its height; 0 or false hides it)
     * and the device locale and time zone. Composables and mounted
     * components update as on a device. Arguments left null keep their value.
     *
     * @param array{top?: float|int, right?: float|int, bottom?: float|int, left?: float|int}|null $insets
     * @param array{width?: float|int, height?: float|int, scale?: float|int, fontScale?: float|int}|null $window
     */
    function environment(
        ?string $scheme = null,
        ?array $insets = null,
        float|int|bool|null $keyboard = null,
        ?array $window = null,
        ?string $locale = null,
        ?string $timezone = null,
    ): void {
        if ($scheme !== null || $insets !== null || $window !== null) {
            $metrics = Runtime::windowMetrics();
            $appearance = match ($scheme) {
                null => $metrics->appearance,
                'dark' => UserInterfaceAppearance::Dark,
                'light' => UserInterfaceAppearance::Light,
                default => throw new \InvalidArgumentException("Testing\\environment() scheme must be 'light' or 'dark', '{$scheme}' given."),
            };
            $values = [
                'width' => (float) ($window['width'] ?? ($metrics->width > 0.0 ? $metrics->width : 390.0)),
                'height' => (float) ($window['height'] ?? ($metrics->height > 0.0 ? $metrics->height : 844.0)),
                'density' => (float) ($window['scale'] ?? $metrics->density),
                'fontScale' => (float) ($window['fontScale'] ?? $metrics->fontScale),
                'appearance' => $appearance->value,
                'safeAreaTop' => (float) ($insets['top'] ?? $metrics->safeAreaTop),
                'safeAreaRight' => (float) ($insets['right'] ?? $metrics->safeAreaRight),
                'safeAreaBottom' => (float) ($insets['bottom'] ?? $metrics->safeAreaBottom),
                'safeAreaLeft' => (float) ($insets['left'] ?? $metrics->safeAreaLeft),
                'refreshRate' => $metrics->refreshRate,
                'reducedMotion' => $metrics->reducedMotion,
                'deviceType' => $metrics->deviceType,
                'pointer' => $metrics->pointer,
                'inputMode' => $metrics->inputMode,
                'dynamicRange' => $metrics->dynamicRange,
                'displayMode' => $metrics->displayMode,
                'foldPosture' => $metrics->foldPosture,
                'memoryClass' => $metrics->memoryClass,
                'performanceTier' => $metrics->performanceTier,
            ];
            if ($scheme !== null) {
                // The forced scheme is the system one, followed by the System preference.
                $values['appearanceMode'] = AppearanceMode::System->value;
                $values['systemAppearance'] = $appearance->value;
            }
            Runtime::dispatchEvent(0, EventKind::Dimensions->value, Wire::map($values));
        }
        if ($keyboard !== null) {
            $height = $keyboard === true ? 300.0 : (float) $keyboard;
            Runtime::dispatchEvent(0, EventKind::KeyboardVisibility->value, ($height > 0.0 ? '1;' : '0;').$height.';0');
        }
        if ($locale !== null || $timezone !== null) {
            $previous = Sources::device()->signal->get();
            $metrics = Runtime::windowMetrics();
            Sources::reportDevice(new DeviceInfo(
                width: $metrics->width,
                height: $metrics->height,
                density: $metrics->density,
                appearance: $metrics->appearance,
                appState: $previous?->appState ?? AppState::Active,
                safeAreaTop: $metrics->safeAreaTop,
                safeAreaRight: $metrics->safeAreaRight,
                safeAreaBottom: $metrics->safeAreaBottom,
                safeAreaLeft: $metrics->safeAreaLeft,
                timeZone: $timezone ?? $previous?->timeZone ?? date_default_timezone_get(),
                locale: $locale ?? $previous?->locale ?? '',
            ));
        }
    }
}

if (!\function_exists('Pam\Native\Testing\reset')) {
    /**
     * Teardown: unmounts every Testing root (releasing their scopes), shuts
     * the runtime down and clears routes, middleware aliases, composable
     * sources and I18n overrides.
     */
    function reset(): void
    {
        Harness::reset();
    }
}

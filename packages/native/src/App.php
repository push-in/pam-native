<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use Pam\Native\Internal\PamPhpRegistry;
use Pam\Native\Internal\Runtime;
use Pam\Native\Plugin\PluginManager;
use Pam\Native\Navigation\TabNavigator;
use Throwable;

final class App
{
    private static ?Theme $activeTheme = null;

    private function __construct()
    {
    }

    public static function run(Renderable|Closure $root): void
    {
        try {
            PluginManager::boot();
            if ($root instanceof TabNavigator) {
                Runtime::onDimensions($root->dimensions(...));
            }
            Runtime::boot($root);
        } catch (Throwable $error) {
            Runtime::reportError($error);

            throw $error;
        }
    }

    public static function views(string $path, ?string $cachePath = null): void
    {
        View::configure($path, $cachePath);
    }

    public static function components(
        string $path,
        ?string $cachePath = null,
    ): void {
        try {
            PamPhpRegistry::discover(
                $path,
                $cachePath ?? getcwd().'/.pam/components',
            );
        } catch (Throwable $error) {
            Runtime::reportError($error);

            throw $error;
        }
    }

    /** @param array<string, mixed> $props */
    public static function make(string $className, array $props = []): Component
    {
        return PamPhpRegistry::make($className, $props);
    }

    public static function theme(Theme $theme): void
    {
        self::$activeTheme = $theme;
        $theme->apply();
    }

    public static function activeTheme(): ?Theme
    {
        return self::$activeTheme;
    }

    public static function appearance(): UserInterfaceAppearance
    {
        return Runtime::windowMetrics()->appearance;
    }

    public static function component(string $tag, string $view): void
    {
        TemplateRegistry::view($tag, $view);
    }

    public static function onBack(Closure $handler): void
    {
        Runtime::onBack($handler);
    }

    public static function onAppState(Closure $handler): void
    {
        Runtime::onAppState($handler);
    }

    public static function onDimensions(Closure $handler): void
    {
        Runtime::onDimensions($handler);
    }

    public static function onMemoryPressure(Closure $handler): void
    {
        Runtime::onMemoryPressure($handler);
    }
}

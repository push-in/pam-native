<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Plugin\PluginManager;

final class App
{
    private function __construct()
    {
    }

    public static function run(Renderable|Closure $root): void
    {
        PluginManager::boot();
        Runtime::boot($root);
    }

    public static function views(string $path, ?string $cachePath = null): void
    {
        View::configure($path, $cachePath);
    }

    public static function theme(Theme $theme): void
    {
        $theme->apply();
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

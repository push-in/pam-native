<?php

declare(strict_types=1);

namespace Pam\Native\Plugin;

interface PluginProvider
{
    /**
     * Register template tags, classes, services, and other plugin definitions.
     */
    public function register(): void;

    /**
     * Start work that depends on all plugin definitions being registered.
     */
    public function boot(): void;
}

<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Fixtures;

use Pam\Native\Plugin\PluginProvider;
use Pam\Native\TemplateRegistry;
use Pam\Native\UI\Text;

final class ExamplePluginProvider implements PluginProvider
{
    public static int $registered = 0;
    public static int $booted = 0;

    public function register(): void
    {
        self::$registered++;
        TemplateRegistry::component(
            'FixtureTag',
            static fn (array $_props, array $_children, ?object $_scope): Text => Text::make('Plugin fixture'),
        );
    }

    public function boot(): void
    {
        self::$booted++;
    }
}

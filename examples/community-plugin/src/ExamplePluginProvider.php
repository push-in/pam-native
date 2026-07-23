<?php

declare(strict_types=1);

namespace PamCommunity\Example;

use Pam\Native\Plugin\PluginProvider;
use Pam\Native\TemplateRegistry;
use Pam\Native\UI\CustomView;

final class ExamplePluginProvider implements PluginProvider
{
    public function register(): void
    {
        TemplateRegistry::component(
            'CommunityBadge',
            static function (array $props, array $_children, ?object $_scope): CustomView {
                $values = [];

                foreach ($props as $key => $value) {
                    if (is_string($value) || is_int($value) || is_float($value) || is_bool($value)) {
                        $values[$key] = $value;
                    }
                }

                return CustomView::make('community.badge', $values);
            },
        );
    }

    public function boot(): void
    {
        // Start plugin services here after every provider has registered.
    }
}

<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use LogicException;
use Pam\Native\Navigation\NavigationAction;
use Pam\Native\Navigation\NavigationActionHandler;
use Pam\Native\Navigation\NavigationBackHandler;

final class Navigation
{
    private static ?NavigationActionHandler $root = null;

    private function __construct()
    {
    }

    /** @internal */
    public static function attach(NavigationActionHandler $navigator): void
    {
        self::$root = $navigator;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function push(string $route, array $params = []): void
    {
        if (!self::root()->dispatch(NavigationAction::push($route, $params))) {
            throw new LogicException("Route {$route} did not handle push navigation.");
        }
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function navigate(string $route, array $params = []): bool
    {
        return self::root()->dispatch(NavigationAction::navigate($route, $params));
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function replace(string $route, array $params = []): void
    {
        if (!self::root()->dispatch(NavigationAction::replace($route, $params))) {
            throw new LogicException("Route {$route} did not handle replacement navigation.");
        }
    }

    public static function back(): bool
    {
        $root = self::root();
        return $root instanceof NavigationBackHandler && $root->goBack();
    }

    public static function root(): NavigationActionHandler
    {
        return self::$root ?? throw new LogicException('No named route stack is active.');
    }
}

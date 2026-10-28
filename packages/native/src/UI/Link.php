<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use BackedEnum;
use Pam\Native\AccessibilityRole;
use Pam\Native\Renderable;
use Pam\Native\Route;
use Pam\Native\Routing\RedirectMode;
use Pam\Native\Routing\TypedRoute;

/**
 * Pressable that navigates to a route (middleware included):
 * `Link::to('chat', ['id' => $id], Text::make('Open'))`, or in a template
 * `<Link route="chat" :params="{ id: $id }">Open</Link>`.
 */
final class Link
{
    private function __construct()
    {
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function to(
        string|BackedEnum|TypedRoute $route,
        array $params = [],
        Renderable|false|null ...$children,
    ): Pressable {
        return self::make($route, $params, RedirectMode::Navigate, ...$children);
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function make(
        string|BackedEnum|TypedRoute $route,
        array $params = [],
        RedirectMode $mode = RedirectMode::Navigate,
        Renderable|false|null ...$children,
    ): Pressable {
        return Pressable::make(...$children)
            ->onPress(static function () use ($route, $params, $mode): void {
                Route::navigate($route, $params, $mode);
            })
            ->accessibilityRole(AccessibilityRole::Link);
    }

    /** @internal Template attribute `mode="replace"`. */
    public static function mode(mixed $value): RedirectMode
    {
        return match ($value) {
            'push' => RedirectMode::Push,
            'replace' => RedirectMode::Replace,
            'reset' => RedirectMode::Reset,
            default => RedirectMode::Navigate,
        };
    }
}

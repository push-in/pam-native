<?php

declare(strict_types=1);

namespace Pam\Native;

use BackedEnum;
use Pam\Native\Routing\Redirect;
use Pam\Native\Routing\TypedRoute;

if (!function_exists('Pam\\Native\\navigate')) {
    /**
     * Navigate by route name, screen class or generated typed route:
     * `navigate('chat', ['id' => $id])`, `navigate(ChatScreen::class, id: $id)`,
     * `navigate(Routes::chat(id: $id))`. Middleware runs first.
     *
     * @param array<string, string|int|float|bool|null> $params
     */
    function navigate(string|BackedEnum|TypedRoute $route, array $params = [], string|int|float|bool|null ...$named): bool
    {
        return Route::navigate($route, [...$params, ...$named]);
    }
}

if (!function_exists('Pam\\Native\\back')) {
    function back(): bool
    {
        return Route::back();
    }
}

if (!function_exists('Pam\\Native\\redirect')) {
    /**
     * For middleware: `return redirect('login');` (`->reset()`, `->replace()`,
     * `->with([...])` adjust it).
     *
     * @param array<string, string|int|float|bool|null> $params
     */
    function redirect(string|BackedEnum|TypedRoute $route, array $params = [], string|int|float|bool|null ...$named): Redirect
    {
        return new Redirect($route, [...$params, ...$named]);
    }
}

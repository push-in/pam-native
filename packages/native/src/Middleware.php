<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;

/**
 * Route middleware (Laravel style). Return `$next($to)` to continue,
 * `redirect('login')` to send the user elsewhere, or false to stay put.
 *
 *     final class Authenticate implements Middleware
 *     {
 *         public function handle(Route $to, Closure $next): mixed
 *         {
 *             return Session::check() ? $next($to) : redirect('login');
 *         }
 *     }
 *
 * Middleware also guards the stack (initial route, deep links, history
 * refresh), so it may run more than once per navigation: keep it a pure check
 * and do side effects in the screen or composable.
 */
interface Middleware
{
    /** @param Closure(Route): mixed $next */
    public function handle(Route $to, Closure $next): mixed;
}

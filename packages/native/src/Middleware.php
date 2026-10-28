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
 * It runs once per navigation (the stack guard and the tab bar reuse the
 * verdict) and also guards tab-bar taps, links, the boot route and history
 * refresh: keep it a check and do side effects in the screen or composable.
 */
interface Middleware
{
    /** @param Closure(Route): mixed $next */
    public function handle(Route $to, Closure $next): mixed;
}

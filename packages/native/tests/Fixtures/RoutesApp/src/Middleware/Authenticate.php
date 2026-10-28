<?php

declare(strict_types=1);

namespace RoutesApp\Middleware;

use Closure;
use Pam\Native\Middleware;
use Pam\Native\Route;

use function Pam\Native\redirect;

final class Authenticate implements Middleware
{
    public static bool $signedIn = false;

    public function handle(Route $to, Closure $next): mixed
    {
        return self::$signedIn ? $next($to) : redirect('login');
    }
}

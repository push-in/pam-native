<?php

declare(strict_types=1);

namespace Pam\Native\Store;

use Closure;

interface StoreMiddleware
{
    /**
     * @param array<string, mixed> $arguments
     * @param Closure(): mixed $next
     */
    public function handle(Store $store, string $action, array $arguments, Closure $next): mixed;
}

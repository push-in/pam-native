<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;

final class NavigationSubscription
{
    private bool $active = true;

    public function __construct(private readonly Closure $unsubscribe)
    {
    }

    public function unsubscribe(): void
    {
        if (!$this->active) return;
        $this->active = false;
        ($this->unsubscribe)();
    }

    public function __destruct()
    {
        $this->unsubscribe();
    }
}

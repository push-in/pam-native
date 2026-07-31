<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

interface NavigationBackHandler
{
    public function canGoBack(): bool;

    public function goBack(): bool;
}

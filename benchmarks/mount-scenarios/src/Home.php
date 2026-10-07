<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\View;

final class Home extends Component
{
    public function render(): View
    {
        return View::make('screens.home');
    }

    public function openProfile(): void
    {
        $this->pushRoute('profile');
    }

    public function openTabs(): void
    {
        $this->pushRoute('tabs');
    }

    public function openReels(): void
    {
        $this->pushRoute('reels');
    }
}

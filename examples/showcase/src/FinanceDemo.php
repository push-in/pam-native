<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\Navigation\Navigator;
use Pam\Native\View;

final class FinanceDemo extends Component
{
    public Navigator $navigator;
    private bool $balanceVisible = true;
    private string $balance = '$12,480.36';
    private string $visibilityAction = 'Hide balance';

    public function render(): View
    {
        return View::make('screens.finance');
    }

    public function toggleBalance(): void
    {
        $this->balanceVisible = !$this->balanceVisible;
        $this->balance = $this->balanceVisible ? '$12,480.36' : '$••••••';
        $this->visibilityAction = $this->balanceVisible ? 'Hide balance' : 'Show balance';
    }

    public function back(): void
    {
        $this->navigator->pop();
    }
}

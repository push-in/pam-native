<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\System\Timers;
use Pam\Native\View;

final class FinanceDemo extends Component
{
    private bool $balanceVisible = true;
    private string $balance = '$12,480.36';
    private string $visibilityAction = 'Hide balance';
    private bool $refreshing = false;

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

    /** Pull-to-refresh: `refreshing` is controlled, false ends the spinner. */
    public function refresh(): void
    {
        $this->refreshing = true;
        Timers::after(900, function (): void {
            $this->refreshing = false;
        });
    }

    public function back(): void
    {
        $this->popRoute();
    }
}

<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\View;

final class FieldDemo extends Component
{
    private SyncState $syncState = SyncState::Ready;
    private string $syncLabel = 'Sync 3 records';
    private string $connectionLabel = 'Offline · changes protected';

    public function render(): View
    {
        return View::make('screens.field');
    }

    public function sync(): void
    {
        $this->syncState = match ($this->syncState) {
            SyncState::Ready => SyncState::Syncing,
            SyncState::Syncing, SyncState::Complete => SyncState::Complete,
        };

        if ($this->syncState === SyncState::Syncing) {
            $this->syncLabel = 'Finish sync';
            $this->connectionLabel = 'Syncing securely…';
            return;
        }

        $this->syncLabel = 'Everything is synced';
        $this->connectionLabel = 'Online · updated just now';
    }

    public function back(): void
    {
        $this->popRoute();
    }
}

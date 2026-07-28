<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\Navigation\Navigator;
use Pam\Native\View;

final class FieldDemo extends Component
{
    public Navigator $navigator;
    private SyncState $syncState = SyncState::Ready;
    private string $syncLabel = 'Sincronizar 3 registros';
    private string $connectionLabel = 'Offline · alterações protegidas';

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
            $this->syncLabel = 'Finalizar sincronização';
            $this->connectionLabel = 'Sincronizando com segurança…';
            return;
        }

        $this->syncLabel = 'Tudo sincronizado';
        $this->connectionLabel = 'Online · atualizado agora';
    }

    public function back(): void
    {
        $this->navigator->pop();
    }
}

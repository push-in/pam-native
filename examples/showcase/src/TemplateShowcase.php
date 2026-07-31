<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\Restorable;
use Pam\Native\View;

final class TemplateShowcase extends Component implements Restorable
{
    private string $name = 'PHP';
    private int $count = 0;
    private bool $enabled = true;
    private bool $modal = false;

    public function render(): View
    {
        return View::make('screens.components');
    }

    public function increment(): void
    {
        $this->count++;
    }

    public function toggle(bool $enabled): void
    {
        $this->enabled = $enabled;
    }

    public function toggleModal(): void
    {
        $this->modal = !$this->modal;
    }

    public function back(): void
    {
        $this->popRoute();
    }

    public function stateKey(): string
    {
        return 'showcase.tags';
    }

    public function restoreState(array $state): void
    {
        $this->name = is_string($state['name'] ?? null) ? $state['name'] : 'PHP';
        $this->count = is_int($state['count'] ?? null) ? $state['count'] : 0;
        $this->enabled = is_bool($state['enabled'] ?? null) ? $state['enabled'] : true;
    }

    public function saveState(): array
    {
        return [
            'name' => $this->name,
            'count' => $this->count,
            'enabled' => $this->enabled,
        ];
    }
}

<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\View;

final class Tabs extends Component
{
    private int $tab = 0;

    public function render(): View
    {
        $rows = [];
        for ($i = 1; $i <= 20; $i++) {
            $rows[] = [
                'key' => $this->tab.'-'.$i,
                'src' => sprintf('asset://assets/photos/p%02d.jpg', (($i + $this->tab * 7) % 30) + 1),
                'title' => ['Mídia', 'Links', 'Documentos'][$this->tab].' item '.$i,
                'subtitle' => 'Compartilhado por Contato '.$i.' · '.(10 + $i).' de out.',
                'meta' => (string) (2 * $i).' KB',
            ];
        }

        return View::make('screens.tabs', ['rows' => $rows, 'tab' => $this->tab]);
    }

    public function first(): void
    {
        $this->tab = 0;
    }

    public function second(): void
    {
        $this->tab = 1;
    }

    public function third(): void
    {
        $this->tab = 2;
    }

    public function back(): void
    {
        $this->popRoute();
    }
}

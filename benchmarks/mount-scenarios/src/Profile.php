<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\View;

final class Profile extends Component
{
    public function render(): View
    {
        $photos = [];
        for ($i = 1; $i <= 30; $i++) {
            $photos[] = ['id' => $i, 'src' => sprintf('asset://assets/photos/p%02d.jpg', $i)];
        }
        $highlights = [];
        for ($i = 1; $i <= 5; $i++) {
            $highlights[] = ['id' => $i, 'src' => sprintf('asset://assets/photos/p%02d.jpg', 30 + ($i % 3)), 'label' => 'Destaque '.$i];
        }

        return View::make('screens.profile', ['photos' => $photos, 'highlights' => $highlights]);
    }

    public function back(): void
    {
        $this->popRoute();
    }
}

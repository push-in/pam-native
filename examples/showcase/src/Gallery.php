<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\Navigation\Navigator;
use Pam\Native\View;

final class Gallery extends Component
{
    public Navigator $navigator;

    public function render(): View
    {
        return View::make('screens.gallery');
    }

    public function openCommerce(): void
    {
        $this->navigator->push('commerce');
    }

    public function openFinance(): void
    {
        $this->navigator->push('finance');
    }

    public function openChat(): void
    {
        $this->navigator->push('chat');
    }

    public function openField(): void
    {
        $this->navigator->push('field');
    }

    public function openLab(): void
    {
        $this->navigator->push('home');
    }
}

<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\Navigation\Navigator;
use Pam\Native\View;

final class CommerceDemo extends Component
{
    public Navigator $navigator;
    private int $cartCount = 0;
    private string $cartLabel = 'Adicionar ao pedido';

    public function render(): View
    {
        return View::make('screens.commerce');
    }

    public function addToCart(): void
    {
        $this->cartCount++;
        $this->cartLabel = "Pedido · {$this->cartCount} item".($this->cartCount === 1 ? '' : 's');
    }

    public function back(): void
    {
        $this->navigator->pop();
    }
}

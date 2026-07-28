<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\Navigation\Navigator;
use Pam\Native\System\Keyboard;
use Pam\Native\View;

final class ChatDemo extends Component
{
    public Navigator $navigator;
    private string $message = '';
    private string $sentMessage = 'Chego em dez minutos. Pode deixar comigo.';
    private string $deliveryLabel = 'Entregue · 09:42';

    public function render(): View
    {
        return View::make('screens.chat');
    }

    public function send(): void
    {
        $message = trim($this->message);
        if ($message === '') {
            return;
        }

        $this->sentMessage = $message;
        $this->message = '';
        $this->deliveryLabel = 'Enviando…';
        Keyboard::dismiss();
    }

    public function back(): void
    {
        $this->navigator->pop();
    }
}

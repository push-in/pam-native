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
    private float $scrollOffset = 100000.0;

    /** @var list<array{id: int, body: string, time: string, outgoing: bool}> */
    private array $messages = [
        [
            'id' => 1,
            'body' => 'I pulled together the campaign shots. Want to see them?',
            'time' => '09:38',
            'outgoing' => false,
        ],
        [
            'id' => 2,
            'body' => 'This direction looks great. The light is perfect.',
            'time' => '09:40 · Read',
            'outgoing' => true,
        ],
        [
            'id' => 3,
            'body' => 'Perfect. I will bring the final files.',
            'time' => '09:41',
            'outgoing' => false,
        ],
        [
            'id' => 4,
            'body' => 'I will be there in ten minutes. Leave it with me.',
            'time' => '09:42 · Read',
            'outgoing' => true,
        ],
    ];

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

        $this->messages[] = [
            'id' => count($this->messages) + 1,
            'body' => $message,
            'time' => date('H:i').' · Sent',
            'outgoing' => true,
        ];
        $this->message = '';
        $this->scrollOffset += 100000.0;
        Keyboard::dismiss();
    }

    public function back(): void
    {
        $this->navigator->pop();
    }
}

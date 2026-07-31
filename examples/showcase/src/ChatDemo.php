<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\View;

final class ChatDemo extends Component
{
    private string $message = '';
    private float $scrollOffset = 100000.0;

    /**
     * @var list<array{
     *     id: int,
     *     body: string,
     *     time: string,
     *     outgoing: bool,
     *     animate: bool
     * }>
     */
    private array $messages = [
        [
            'id' => 1,
            'body' => 'I pulled together the campaign shots. Want to see them?',
            'time' => '09:38',
            'outgoing' => false,
            'animate' => false,
        ],
        [
            'id' => 2,
            'body' => 'This direction looks great. The light is perfect.',
            'time' => '09:40 · Read',
            'outgoing' => true,
            'animate' => false,
        ],
        [
            'id' => 3,
            'body' => 'Perfect. I will bring the final files.',
            'time' => '09:41',
            'outgoing' => false,
            'animate' => false,
        ],
        [
            'id' => 4,
            'body' => 'I will be there in ten minutes. Leave it with me.',
            'time' => '09:42 · Read',
            'outgoing' => true,
            'animate' => false,
        ],
    ];

    /** @var list<array<string, float>> */
    private array $messageEntranceKeyframes = [
        [
            'offset' => 0.0,
            'opacity' => 0.0,
            'translationY' => 10.0,
            'scaleX' => 0.96,
            'scaleY' => 0.96,
        ],
        [
            'offset' => 0.72,
            'opacity' => 1.0,
            'translationY' => -1.0,
            'scaleX' => 1.01,
            'scaleY' => 1.01,
        ],
        [
            'offset' => 1.0,
            'opacity' => 1.0,
            'translationY' => 0.0,
            'scaleX' => 1.0,
            'scaleY' => 1.0,
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
            'animate' => true,
        ];
        $this->message = '';
        $this->scrollOffset += 100000.0;
    }

    public function back(): void
    {
        $this->popRoute();
    }
}

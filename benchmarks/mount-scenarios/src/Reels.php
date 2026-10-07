<?php

declare(strict_types=1);

namespace App;

use Pam\Native\Component;
use Pam\Native\View;

final class Reels extends Component
{
    private int $index = 0;

    public function render(): View
    {
        $i = $this->index;

        return View::make('screens.reels', [
            'reel' => [
                'id' => $i,
                'video' => ['https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4', 'https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4', 'https://storage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4'][$i % 3],
                'poster' => sprintf('asset://assets/photos/p%02d.jpg', ($i % 30) + 1),
                'avatar' => sprintf('asset://assets/photos/p%02d.jpg', (($i + 5) % 30) + 1),
                'author' => 'autor_'.$i,
                'caption' => 'Legenda do reel '.$i.' com algumas palavras para quebrar em duas linhas como no app real #pam #reels',
                'music' => 'Música original · autor_'.$i,
                'likes' => (string) (1000 + $i * 37),
                'comments' => (string) (40 + $i),
            ],
        ]);
    }

    public function next(): void
    {
        $this->index++;
    }

    public function back(): void
    {
        $this->popRoute();
    }
}

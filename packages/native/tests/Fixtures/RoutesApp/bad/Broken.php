<?php

declare(strict_types=1);

namespace RoutesApp\Bad;

use RoutesApp\Screens\FeedScreen;

use function Pam\Native\navigate;

final class Broken
{
    public function go(): void
    {
        navigate('chta', ['id' => '1']);
        navigate('chat', ['unread' => 'three']);
        navigate('chat', ['id' => '1', 'color' => 'red']);
        navigate(FeedScreen::class, tab: 2);
    }
}

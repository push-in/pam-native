<?php

declare(strict_types=1);

namespace RoutesApp\Usage;

use Pam\Native\Route;
use RoutesApp\Screens\ChatScreen;

use function Pam\Native\navigate;
use function Pam\Native\redirect;

final class Navigation
{
    public function open(string $id, array $extra): void
    {
        navigate('chat', ['id' => $id, 'unread' => 2]);
        navigate(ChatScreen::class, id: $id, muted: true);
        navigate('chat', [...$extra]);
        Route::navigate('feed');
        redirect('login');
        $this->navigate('not-a-route');
    }

    private function navigate(string $route): void
    {
    }
}

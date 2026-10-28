<?php

declare(strict_types=1);

namespace RoutesApp\Screens;

final class ChatScreen extends BaseScreen
{
    public static ?self $mounted = null;
    public string $id = '';
    public int $unread = 0;
    public bool $muted = false;

    public function setup(string $id, int $unread = 0, bool $muted = false): void
    {
        $this->id = $id;
        $this->unread = $unread;
        $this->muted = $muted;
        self::$mounted = $this;
    }
}

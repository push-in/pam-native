<?php

declare(strict_types=1);

use Pam\Native\Route;
use RoutesApp\Screens\ChatScreen;
use RoutesApp\Screens\ChatsScreen;
use RoutesApp\Screens\FeedScreen;
use RoutesApp\Screens\LoginScreen;
use RoutesApp\Screens\SettingsScreen;

require_once __DIR__.'/bootstrap.php';

Route::screen('/login', LoginScreen::class)->name('login');

Route::middleware('auth')->group(function (): void {
    Route::tabs(function (): void {
        Route::screen('/chats', ChatsScreen::class)->name('chats')->icon('chatbubbles')->label('Conversas');
        Route::screen('/feed', FeedScreen::class)->name('feed')->icon('home');
    });
    Route::screen('/chat/{id}', ChatScreen::class)->name('chat')->gestures(false)->statusBar('light');
    Route::modal('/settings', SettingsScreen::class)->name('settings')->presentation('sheet')->orientation('portrait');
});

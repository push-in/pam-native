<?php

declare(strict_types=1);

use Pam\Native\Route;
use TestingApp\Screens\ChatScreen;
use TestingApp\Screens\HomeScreen;
use TestingApp\Screens\LoginScreen;

require_once __DIR__.'/bootstrap.php';

Route::screen('/login', LoginScreen::class)->name('login');

Route::middleware('auth')->group(function (): void {
    Route::screen('/', HomeScreen::class)->name('home');
    Route::screen('/chat/{id}', ChatScreen::class)->name('chat');
});

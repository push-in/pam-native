<?php

declare(strict_types=1);

use App\Home;
use App\Profile;
use App\Tabs;
use App\Reels;
use Pam\Native\App;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Routing\Route;

require __DIR__.'/vendor/autoload.php';

App::views(__DIR__.'/resources/native', __DIR__.'/.pam-native/views');
$home = new Home();
$profile = new Profile();
$tabs = new Tabs();
$reels = new Reels();
$navigator = Route::stack(
    name: 'bench',
    initial: 'home',
    routes: static function () use ($home, $profile, $tabs, $reels): void {
        Route::screen('home', $home);
        Route::screen('profile', $profile);
        Route::screen('tabs', $tabs);
        Route::screen('reels', $reels);
    },
    transition: NavigationTransition::PlatformDefault,
    durationMs: 240,
);
App::onBack(static function () use ($navigator): void {
    $navigator->pop();
});
App::run($navigator);

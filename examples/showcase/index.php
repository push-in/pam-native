<?php

declare(strict_types=1);

use App\Showcase;
use App\TemplateShowcase;
use App\Gallery;
use App\CommerceDemo;
use App\FinanceDemo;
use App\ChatDemo;
use App\FieldDemo;
use Pam\Native\App;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Routing\Route;

require __DIR__.'/vendor/autoload.php';

App::views(
    __DIR__.'/resources/native',
    __DIR__.'/.pam-native/views',
);
App::theme(\Pam\Native\Theme::pamLab());
$showcase = new Showcase();
$template = new TemplateShowcase();
$gallery = new Gallery();
$commerce = new CommerceDemo();
$finance = new FinanceDemo();
$chat = new ChatDemo();
$field = new FieldDemo();
$navigator = Route::stack(
    name: 'showcase',
    initial: 'gallery',
    routes: static function () use ($gallery, $commerce, $finance, $chat, $field, $showcase, $template): void {
        Route::screen('gallery', $gallery);
        Route::screen('commerce', $commerce);
        Route::screen('finance', $finance);
        Route::screen('chat', $chat);
        Route::screen('field', $field);
        Route::screen('home', $showcase->home(...));
        Route::screen('details', $showcase->details(...));
        Route::screen('tags', $template);
    },
    transition: NavigationTransition::PlatformDefault,
    durationMs: 240,
);

App::onBack(static function () use ($navigator): void {
    $navigator->pop();
});
App::run($navigator);

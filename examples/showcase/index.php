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
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\NavigationTransition;

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
$navigator = new Navigator(
    initialRoute: 'gallery',
    routes: [
        'gallery' => static fn () => $gallery,
        'commerce' => static fn () => $commerce,
        'finance' => static fn () => $finance,
        'chat' => static fn () => $chat,
        'field' => static fn () => $field,
        'home' => $showcase->home(...),
        'details' => $showcase->details(...),
        'tags' => static fn () => $template,
    ],
    persistenceKey: 'showcase',
    transition: NavigationTransition::PlatformDefault,
    transitionDurationMs: 240,
);
$showcase->navigator = $navigator;
$template->navigator = $navigator;
$gallery->navigator = $navigator;
$commerce->navigator = $navigator;
$finance->navigator = $navigator;
$chat->navigator = $navigator;
$field->navigator = $navigator;

App::onBack(static function () use ($navigator): void {
    $navigator->pop();
});
App::run($navigator);

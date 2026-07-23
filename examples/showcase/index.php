<?php

declare(strict_types=1);

use App\Showcase;
use App\TemplateShowcase;
use Pam\Native\App;
use Pam\Native\Navigation\Navigator;

require __DIR__.'/vendor/autoload.php';

App::views(
    __DIR__.'/resources/native',
    __DIR__.'/.pam-native/views',
);
App::theme(\Pam\Native\Theme::pamLab());
$showcase = new Showcase();
$template = new TemplateShowcase();
$navigator = new Navigator(
    initialRoute: 'home',
    routes: [
        'home' => $showcase->home(...),
        'details' => $showcase->details(...),
        'tags' => static fn () => $template,
    ],
    persistenceKey: 'showcase',
);
$showcase->navigator = $navigator;
$template->navigator = $navigator;

App::onBack(static function () use ($navigator): void {
    $navigator->pop();
});
App::run($navigator);

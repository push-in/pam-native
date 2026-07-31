<?php

declare(strict_types=1);

use Pam\Native\Navigation\Router;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

spl_autoload_register(static function (string $class): void {
    $prefix = 'Pam\\Native\\';
    if (!str_starts_with($class, $prefix)) return;
    $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen($prefix))).'.php';
    if (is_file($path)) require $path;
});

$navigator = Router::stack('home')
    ->route('home', static fn () => Screen::make(Text::make('Home')))
    ->route('detail', static fn () => Screen::make(Text::make('Detail')))
    ->deepLink('/details/{id}', 'detail')
    ->linking(['pam://app'])
    ->build();

$iterations = 1_000;
$started = hrtime(true);
for ($index = 1; $index <= $iterations; $index++) {
    $navigator->push('detail', ['id' => $index]);
    $navigator->render()->toElement();
    $navigator->pop();
    $navigator->render()->toElement();
}
$actionMs = (hrtime(true) - $started) / 1_000_000 / ($iterations * 2);

$started = hrtime(true);
for ($index = 1; $index <= $iterations; $index++) {
    if (!$navigator->open("pam://app/details/{$index}?source=benchmark")) {
        throw new RuntimeException('The benchmark deep link was not handled.');
    }
}
$linkMs = (hrtime(true) - $started) / 1_000_000 / $iterations;

$actionBudget = (float) (getenv('PAM_NAV_ACTION_MS') ?: 0.5);
$linkBudget = (float) (getenv('PAM_NAV_LINK_MS') ?: 0.25);
fwrite(STDOUT, json_encode([
    'iterations' => $iterations,
    'semanticActionMs' => round($actionMs, 4),
    'deepLinkMs' => round($linkMs, 4),
    'budgets' => ['semanticActionMs' => $actionBudget, 'deepLinkMs' => $linkBudget],
], JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES)."\n");

if ($actionMs > $actionBudget || $linkMs > $linkBudget) {
    fwrite(STDERR, "Pam Native navigation performance budget exceeded.\n");
    exit(1);
}

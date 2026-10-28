<?php

declare(strict_types=1);

use Pam\Native\Route;
use Pam\Native\Routing\MiddlewareRegistry;
use Pam\Native\Routing\RouteCompiler;
use Pam\Native\Routing\TypedRoute;

$assert = require __DIR__.'/arch_v2_bootstrap.php';
require_once __DIR__.'/../src/Routing/functions.php';

$fixture = __DIR__.'/Fixtures/RoutesApp';
Route::flush();
$routes = RouteCompiler::load($fixture.'/routes.php');
$manifest = RouteCompiler::manifest($routes);
$byName = array_column($manifest['routes'], null, 'name');
$assert(
    $manifest['version'] === 1 && array_keys($byName) === ['login', 'tabs', 'chats', 'feed', 'chat', 'settings']
        && $byName['chat']['path'] === '/chat/{id}' && $byName['chat']['pathParams'] === ['id']
        && $byName['chat']['middleware'] === ['auth'] && $byName['tabs']['linkable'] === false
        && array_column($byName['chat']['params'], 'type', 'name') === ['id' => 'string', 'unread' => 'int', 'muted' => 'bool'],
    'The manifest must carry names, paths, middleware and the setup() signature.',
);

// Typed navigation code: compiles, and the helpers carry PHP types.
$files = RouteCompiler::generate($manifest, 'ArchV2Generated');
$assert(array_keys($files) === ['RouteName.php', 'Routes.php'], 'The compiler generates RouteName and Routes.');
$assert(
    str_contains($files['Routes.php'], 'public static function chat(string $id, int $unread = 0, bool $muted = false): TypedRoute')
        && str_contains($files['RouteName.php'], "case Chat = 'chat';"),
    'Generated helpers must mirror setup() parameters.',
);
$directory = sys_get_temp_dir().'/pam-arch-v2-routes-'.getmypid();
@mkdir($directory);
foreach ($files as $name => $source) {
    file_put_contents($directory.'/'.$name, $source);
    require $directory.'/'.$name;
}
$target = \ArchV2Generated\Routes::chat('5', unread: 2);
$assert($target instanceof TypedRoute && $target->name === 'chat' && $target->params === ['id' => '5', 'unread' => 2, 'muted' => false], 'Generated helpers build TypedRoutes.');
$typeError = false;
try {
    \ArchV2Generated\Routes::chat('5', unread: 'two');
} catch (TypeError) {
    $typeError = true;
}
$assert($typeError, 'A wrong param type fails in PHP before navigating.');
$assert(\ArchV2Generated\RouteName::from('settings') === \ArchV2Generated\RouteName::Settings, 'RouteName enum lists every route.');

// Literal checks: the fixture app is clean, the broken sources are reported.
$assert(RouteCompiler::check($manifest, $fixture.'/src') === [], 'Valid literal navigation must pass: '.implode(' | ', RouteCompiler::check($manifest, $fixture.'/src')));
$errors = RouteCompiler::check($manifest, $fixture.'/bad');
sort($errors);
$assert($errors === [
    'Broken.php:15: unknown route chta.',
    'Broken.php:16: route chat parameter unread expects int, string given.',
    'Broken.php:16: route chat requires parameter id.',
    'Broken.php:17: route chat has no parameter color.',
    'Broken.php:18: route feed has no parameter tab.',
    'BrokenCard.pam.php:13: <Link> to unknown route settigns.',
    'BrokenCard.pam.php:14: <Link> route chat requires parameter id.',
], 'Wrong route names, params and literal types must fail the compile: '.implode(' | ', $errors));

// The bin end to end (what the CLI runs before a build).
$output = $directory.'/Generated';
$command = sprintf('%s %s %s %s --namespace=ArchV2Bin --manifest=%s 2>&1', escapeshellarg(PHP_BINARY), escapeshellarg(__DIR__.'/../bin/pam-native-routes'), escapeshellarg($fixture.'/routes.php'), escapeshellarg($output), escapeshellarg($directory.'/routes.manifest.json'));
exec($command, $lines, $status);
$written = json_decode((string) @file_get_contents($directory.'/routes.manifest.json'), true);
$assert($status === 0 && is_file($output.'/Routes.php') && ($written['routes'][4]['name'] ?? null) === 'chat', 'pam-native-routes must write the helpers and manifest: '.implode("\n", $lines));
$lines = [];
exec(sprintf('%s %s %s %s --src=%s --check-only 2>&1', escapeshellarg(PHP_BINARY), escapeshellarg(__DIR__.'/../bin/pam-native-routes'), escapeshellarg($fixture.'/routes.php'), escapeshellarg($output), escapeshellarg($fixture.'/bad')), $lines, $status);
$assert($status === 1 && str_contains(implode("\n", $lines), 'unknown route chta'), 'pam-native-routes must exit 1 on route errors.');

// Broken route files fail with clear messages.
Route::flush();
Route::screen('/chat/{chatId}', \RoutesApp\Screens\ChatScreen::class)->name('chat');
$failed = null;
try {
    RouteCompiler::manifest(\Pam\Native\Routing\RouteCollection::current());
} catch (RuntimeException $error) {
    $failed = $error->getMessage();
}
$assert($failed !== null && str_contains($failed, 'path {chatId} is not a parameter'), 'Path params must exist in setup().');

array_map('unlink', glob($directory.'/Generated/*') ?: []);
@rmdir($directory.'/Generated');
array_map('unlink', glob($directory.'/*.*') ?: []);
@rmdir($directory);
Route::flush();
MiddlewareRegistry::reset();
echo "Architecture v2 route compiler tests passed.\n";

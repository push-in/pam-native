<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Composables\SharedSource;
use Pam\Native\Internal\Runtime;
use Pam\Native\Testing;
use Pam\Native\Testing\Mounted;
use TestingApp\Middleware\Authenticate;
use TestingApp\Screens\ChatScreen;
use TestingApp\Screens\HomeScreen;
use TestingApp\Screens\Log;

$assert = require __DIR__.'/arch_v2_bootstrap.php';
require_once __DIR__.'/../src/Routing/functions.php';
require_once __DIR__.'/../src/Testing/functions.php';
require_once __DIR__.'/Fixtures/TestingApp/bootstrap.php';

Testing\reset(flushRoutes: true);
Log::$entries = [];

// mount(): props, route params for setup(), the real lifecycle and focus.
$chat = Testing\mount(ChatScreen::class, ['title' => 'Room'], params: ['id' => '1']);
$assert($chat instanceof Mounted && $chat->instance instanceof ChatScreen && $chat->mounted(), 'Testing\mount() must return a handle.');
$assert(
    Log::$entries === ['chat1:setup', 'chat1:mounted', 'chat1:focus'],
    'mount() must run setup(), onMounted() and, with params, onFocus(): '.json_encode(Log::$entries),
);
$assert(str_starts_with($chat->text(), 'Room 1 draft= scheme=light'), 'mount() must render: '.$chat->text());
$chat->instance->draft->value = 'hello';
$assert($chat->render()->kind === \Pam\Native\NodeKind::Text && str_contains($chat->text(), 'draft=hello'), 'render() must run another pass: '.$chat->text());
$chat->blur();
$chat->focus();
$assert(array_slice(Log::$entries, -2) === ['chat1:blur', 'chat1:focus'], 'focus()/blur() must run the hooks.');

// Two roots at once: unmounting one leaves the other mounted.
$other = Testing\mount(new ChatScreen('Other'));
$assert(in_array('chat0:mounted', Log::$entries, true) && in_array('chat0:focus', Log::$entries, true), 'A root without route params counts as focused.');
$assert(SharedSource::active()['appearance'] === 2, 'Each mounted component holds its composables.');
Log::$entries = [];
$other->unmount();
$assert(Log::$entries === ['chat0:blur', 'chat0:unmounted'] && !$other->mounted() && $chat->mounted(), 'unmount() must release only that root: '.json_encode(Log::$entries));
$assert(SharedSource::active()['appearance'] === 1, 'unmount() must release the composables of that root.');

// environment(): scheme, insets, keyboard and locale reach the mounted component.
Testing\environment(scheme: 'dark', insets: ['top' => 24, 'bottom' => 34], keyboard: 280, locale: 'pt-BR');
$chat->render();
$assert(
    str_contains($chat->text(), 'scheme=dark bottom=34 keyboard=280 locale=pt-BR'),
    'Testing\environment() must drive the composables: '.$chat->text(),
);
Testing\environment(scheme: 'light', keyboard: false);
$chat->render();
$assert(str_contains($chat->text(), 'scheme=light bottom=34 keyboard=0'), 'environment() keeps what it was not given: '.$chat->text());
$bad = false;
try {
    Testing\environment(scheme: 'sepia');
} catch (InvalidArgumentException) {
    $bad = true;
}
$assert($bad, 'environment() must reject unknown schemes.');
Log::$entries = [];
$chat->unmount();
$assert(Log::$entries === ['chat1:blur', 'chat1:unmounted'] && SharedSource::active() === [], 'Unmounting the last root must leave no subscription: '.json_encode(SharedSource::active()));

// routes(): the app's routes, with middleware aliases shared through config/middleware.php.
Authenticate::$signedIn = false;
$app = Testing\routes(__DIR__.'/Fixtures/TestingApp/routes.php');
$assert(App::middleware() === ['auth' => Authenticate::class], 'config/middleware.php must register the aliases (App::middleware() reads them).');
$assert($app->current()?->routeName() === 'login' && $app->text() === 'login', 'Middleware must redirect at boot: '.$app->current()?->routeName());
$app->open('/chat/7');
$assert($app->current()?->routeName() === 'login', 'Middleware must guard opened links.');
Authenticate::$signedIn = true;
Log::$entries = [];
$app->open('/chat/7');
$screen = $app->screen();
$assert(
    $app->current()?->routeName() === 'chat' && $app->current()?->param('id') === '7'
        && $screen instanceof ChatScreen && $screen->id === '7' && $app->path() === '/chat/7'
        && str_starts_with($app->text(), 'Chat 7'),
    'open() must route, run setup() with the params and render the screen: '.$app->text(),
);
$assert(in_array('chat7:setup', Log::$entries, true) && in_array('chat7:focus', Log::$entries, true), 'The routed screen must mount and focus: '.json_encode(Log::$entries));
$app->navigate('home');
$assert($app->current()?->routeName() === 'home' && $app->screen() instanceof HomeScreen, 'navigate() must work through the tester.');

// Unmounting what was never mounted, or twice, is a no-op.
$never = new ChatScreen('Never');
Testing\unmount($never);
$app->unmount();
Testing\unmount($app);
Testing\unmount($chat);

// reset(): runtime and sources are released; routes and middleware aliases are kept and can be mounted again.
Testing\reset();
$assert(
    Runtime::root() === null && SharedSource::active() === [] && App::middleware() === ['auth' => Authenticate::class],
    'Testing\reset() must shut the runtime down and keep routes and aliases.',
);
$again = Testing\routes();
$assert($again->current()?->routeName() === 'home' && $again->text() === 'home', 'Testing\routes() without arguments mounts the routes kept by reset(): '.$again->current()?->routeName());
$again->open('/chat/3');
$assert($again->screen() instanceof ChatScreen && $again->screen()->id === '3', 'Kept routes keep working after reset().');
Testing\reset(flushRoutes: true);
$assert(App::middleware() === [] && \Pam\Native\Routing\RouteCollection::current()->isEmpty(), 'reset(flushRoutes: true) clears routes and aliases.');
// Aliases set explicitly (index.php, or routes(middleware: [...])) win over config/middleware.php.
$explicit = static fn ($to, Closure $next) => $next($to);
App::middleware(['auth' => $explicit]);
$assert(
    \Pam\Native\Routing\MiddlewareRegistry::loadConfig(__DIR__.'/Fixtures/TestingApp/config/middleware.php')
        && App::middleware() === ['auth' => $explicit]
        && !\Pam\Native\Routing\MiddlewareRegistry::loadConfig(__DIR__.'/Fixtures/TestingApp/config/missing.php'),
    'config/middleware.php must only fill aliases that are not registered yet.',
);
\Pam\Native\Routing\MiddlewareRegistry::reset();
$remounted = Testing\mount(new HomeScreen());
$assert($remounted->text() === 'home', 'A component created with new mounts without any registry, also after reset().');
Testing\reset(flushRoutes: true);
Authenticate::$signedIn = false;

echo "ARCH_V2_TESTING_OK mount props params focus multi-root unmount environment routes middleware-config reset\n";

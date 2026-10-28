<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateExpression;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Navigation\NavigationPresentation;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\RouteContext;
use Pam\Native\Renderable;
use Pam\Native\Route;
use Pam\Native\Routing\MiddlewareRegistry;
use Pam\Native\Routing\Redirect;
use Pam\Native\Routing\RedirectMode;
use Pam\Native\Routing\RouteArguments;
use Pam\Native\Routing\RouteCollection;
use Pam\Native\Routing\RouteKind;
use Pam\Native\Routing\RouteNavigator;
use Pam\Native\Routing\TypedRoute;
use Pam\Native\UI\Link;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;
use RoutesApp\Middleware\Authenticate;
use RoutesApp\Screens\ChatScreen;

use function Pam\Native\back;
use function Pam\Native\navigate;
use function Pam\Native\redirect;

$assert = require __DIR__.'/arch_v2_bootstrap.php';
require_once __DIR__.'/../src/Routing/functions.php';
require_once __DIR__.'/Fixtures/RoutesApp/bootstrap.php';

$expectError = static function (Closure $action, string $contains) use ($assert): void {
    try {
        $action();
    } catch (Throwable $error) {
        $assert(str_contains($error->getMessage(), $contains), "Expected \"{$contains}\", got \"{$error->getMessage()}\".");
        return;
    }
    $assert(false, "Expected an error containing \"{$contains}\".");
};
$boot = static function (string $file): RouteNavigator {
    Route::flush();
    MiddlewareRegistry::reset();
    App::middleware(['auth' => Authenticate::class]);
    require $file;
    $navigator = new RouteNavigator(RouteCollection::current());
    RouteCollection::activate($navigator);

    return $navigator;
};

// --- registration: names, kinds, groups, tabs, chained options
$router = $boot(__DIR__.'/Fixtures/RoutesApp/routes.php');
$routes = RouteCollection::current();
$assert(
    array_map(static fn (Route $route): string => $route->routeName(), $routes->all()) === ['login', 'tabs', 'chats', 'feed', 'chat', 'settings'],
    'routes.php registrations must keep order and names.',
);
$assert(
    $routes->get('chats')->kind === RouteKind::Tab && $routes->get('chats')->tabs === 'tabs'
        && $routes->get('tabs')->kind === RouteKind::Tabs && $routes->get('settings')->kind === RouteKind::Modal
        && $routes->get('chat')->middleware() === ['auth'] && $routes->get('login')->middleware() === []
        && $routes->get('chats')->labelText() === 'Conversas' && $routes->get('feed')->labelText() === 'Feed'
        && $routes->get('chats')->iconName() === 'chatbubbles',
    'Groups, tabs, modal and chained name/icon/label must register.',
);
$assert($routes->get(ChatScreen::class)->routeName() === 'chat', 'Routes must be found by screen class.');
$assert(RouteKind::Screen->value === 1 && RouteKind::Tabs->value === 4 && RedirectMode::Reset->value === 4, 'Route enums are sequential from 1.');

// --- middleware redirects at boot and on navigate
Authenticate::$signedIn = false;
$assert($router->root()->currentRoute() === 'login', 'Guarded initial tabs must redirect to login when signed out.');
$assert(navigate('chat', ['id' => '5']) === true && $router->root()->currentRoute() === 'login', 'Middleware redirect must replace the navigation.');
Authenticate::$signedIn = true;
$router = $boot(__DIR__.'/Fixtures/RoutesApp/routes.php');
$assert($router->root()->currentRoute() === 'tabs', 'Signed in, the first tabs group is the initial route.');

// --- typed params into setup(), by name, class and TypedRoute
ChatScreen::$mounted = null;
$assert(navigate(ChatScreen::class, id: '7', unread: '3') === true, 'navigate(Screen::class, named params) must navigate.');
$screen = $router->make($routes->get('chat'), $router->root()->current()->all());
$assert(
    ChatScreen::$mounted !== null && ChatScreen::$mounted->id === '7' && ChatScreen::$mounted->unread === 3 && ChatScreen::$mounted->muted === false,
    'Route params must reach setup() typed (link strings coerced).',
);
$assert($screen instanceof Screen, 'statusBar() must wrap the screen with a StatusBar.');
$assert((new TypedRoute('chat', ['id' => '8']))->navigate() && $router->root()->current()->all() === ['id' => '8'], 'TypedRoute must navigate.');
$expectError(static fn () => navigate('chat'), 'requires parameter id');
$expectError(static fn () => navigate('chat', ['id' => '1', 'color' => 'red']), 'has no parameter color');
$expectError(static fn () => navigate('chat', ['id' => '1', 'unread' => 'many']), 'expects int');
$expectError(static fn () => navigate('nowhere'), 'is not registered');
$assert(back() === true, 'back() must pop.');

// --- tabs
$assert(navigate('feed') === true && Route::current()?->routeName() === 'feed', 'navigate() to a tab selects it.');

// --- links (path + query, custom scheme host as first segment)
$assert($router->open('app://chat/99?unread=2&utm_source=x') && Route::current()?->params() === ['id' => '99', 'unread' => 2], 'Custom-scheme links must route with known query params only.');
$assert($router->open('https://example.com/settings') && $router->root()->currentRoute() === 'settings', 'https links route by path.');
$assert($router->open('https://example.com/nope') === false, 'Unknown links are not handled.');
$assert(Route::path('chat', ['id' => 5, 'unread' => 1]) === '/chat/5?unread=1', 'Route::path() builds paths.');

// --- options
$options = $router->root()->currentOptions();
$assert($options->presentation === NavigationPresentation::FormSheet && $options->orientation === \Pam\Native\Navigation\NavigationOrientation::Portrait, 'presentation()/orientation() strings must map to screen options.');

// --- redirect() and middleware aliases with arguments and closures
$redirect = redirect('login')->with(['reason' => 'expired'])->reset();
$assert($redirect instanceof Redirect && $redirect->params === ['reason' => 'expired'] && $redirect->mode === RedirectMode::Reset, 'redirect() builds a Redirect.');
MiddlewareRegistry::alias(['role' => static fn (Route $to, Closure $next, string ...$roles) => in_array('admin', $roles, true) ? $next($to) : false]);
$probe = (new Route(RouteKind::Screen, '/admin', null))->middleware('role:admin,editor');
$assert(MiddlewareRegistry::run($probe) === true, 'Middleware arguments (role:admin,editor) must reach the handler.');
$assert(MiddlewareRegistry::run((new Route(RouteKind::Screen, '/x', null))->middleware('role:editor')) === false, 'A middleware returning false blocks.');
$expectError(static fn () => MiddlewareRegistry::run((new Route(RouteKind::Screen, '/x', null))->middleware('missing')), 'not registered');

// --- layout, injection by type, alias, unique, keepAlive rule, group options, boundary
final class ArchV2Shell implements Renderable
{
    public function __construct(public readonly Navigator $navigator) {}
    public function toElement(): Element { return $this->navigator->toElement(); }
}
final class ArchV2LegacyChat implements Renderable
{
    public static ?self $last = null;
    public function __construct(public readonly Navigator $navigator, public readonly string $chatId, public readonly ?string $title = null) { self::$last = $this; }
    public function toElement(): Element { return Screen::make(Text::make($this->chatId))->toElement(); }
}
final class ArchV2Home implements Renderable
{
    public function toElement(): Element { return Screen::make()->toElement(); }
}
Route::flush();
$closureContext = null;
Route::errorBoundary(static fn (Throwable $e) => Screen::make(Text::make('fallback')));
Route::layout(ArchV2Shell::class, static function () use (&$closureContext): void {
    Route::screen('/', ArchV2Home::class)->name('home');
    Route::group(['transition' => [NavigationTransition::Fade, 180], 'gestures' => false], static function () use (&$closureContext): void {
        Route::screen('/chat/{chatId}', ArchV2LegacyChat::class)->name('chat')->alias('/group/{chatId}')
            ->unique(static fn (RouteContext $r): string => 'chat:'.$r->chatId)
            ->keepAlive(static fn (RouteContext $r): bool => !$r->has('title'));
        Route::screen('/closure', static function (RouteContext $route, Navigator $navigator) use (&$closureContext): Renderable {
            $closureContext = [$route->name, $navigator];
            return new ArchV2Home();
        })->name('closure');
    });
});
$router = new RouteNavigator(RouteCollection::current());
RouteCollection::activate($router);
$view = $router->view();
$assert($view instanceof ArchV2Shell && $view->navigator === $router->root(), 'Route::layout() must wrap the stack and receive the Navigator by type.');
navigate('chat', chatId: 'a');
$view->toElement();
$assert(ArchV2LegacyChat::$last?->chatId === 'a' && ArchV2LegacyChat::$last->navigator === $router->root(), 'Pre-v2 screens get Navigator injected by type.');
navigate('closure');
$view->toElement();
$assert($closureContext !== null && $closureContext[0] === 'closure' && $closureContext[1] === $router->root(), 'Closures receive RouteContext and Navigator by type.');
navigate('chat', chatId: 'a');
$assert(array_column($router->root()->getState()['routes'] ?? [], 'name') === ['home', 'chat'], '->unique() returns to the same chat instead of pushing another.');
$assert($router->open('https://example.com/group/zz') && Route::current()?->param('chatId') === 'zz' && $router->linkCount() === 4, '->alias() adds a routed path.');
$assert($router->root()->currentOptions()->animation === NavigationTransition::Fade && $router->root()->currentOptions()->gestureEnabled === false, 'Group transition/gestures apply to the group routes.');
$assert(RouteCollection::current()->errorBoundary !== null, 'Route::errorBoundary() sets the default boundary.');
$assert(RouteArguments::signature(ArchV2LegacyChat::class) === [
    ['name' => 'chatId', 'type' => 'string', 'nullable' => false, 'required' => true, 'default' => null, 'enum' => null],
    ['name' => 'title', 'type' => 'string', 'nullable' => true, 'required' => false, 'default' => null, 'enum' => null],
], 'Injected services are not route parameters.');

// --- deep-link query keys reach closure routes (same params as the compiler reads)
Route::flush();
$closureArgs = null;
Route::screen('/', ArchV2Home::class)->name('home');
Route::screen('/search', static function (Navigator $navigator, ?string $initialQuery = null, int $start = 0) use (&$closureArgs): Renderable {
    $closureArgs = [$initialQuery, $start];
    return new ArchV2Home();
})->name('search');
Route::screen('/call/{callId}', static function (RouteContext $route) use (&$closureArgs): Renderable {
    $closureArgs = $route->all();
    return new ArchV2Home();
})->name('call');
$router = new RouteNavigator(RouteCollection::current());
RouteCollection::activate($router);
$assert($router->open('app://search?initialQuery=oi&start=5&utm=x') && $router->root()->current()->all() === ['initialQuery' => 'oi', 'start' => '5'], 'Closure routes keep the query keys their parameters declare: '.json_encode($router->root()->current()->all()));
$router->make(RouteCollection::current()->get('search'), $router->root()->current()->all());
$assert($closureArgs === ['oi', 5], 'Closure params arrive typed from link strings.');
$manifestParams = array_column(\Pam\Native\Routing\RouteCompiler::manifest(RouteCollection::current())['routes'][1]['params'], 'name');
$assert($manifestParams === ['initialQuery', 'start'], 'Runtime and compiler agree on closure params.');
$assert($router->open('https://example.com/call/9?incoming=1&callType=video') && $router->root()->current()->all() === ['callId' => '9', 'incoming' => '1', 'callType' => 'video'], 'A closure taking the RouteContext keeps every query key, as before v2.');
$router->make(RouteCollection::current()->get('call'), $router->root()->current()->all());
$assert($closureArgs === ['callId' => '9', 'incoming' => '1', 'callType' => 'video'], 'The RouteContext closure reads the query params.');
$callManifest = \Pam\Native\Routing\RouteCompiler::manifest(RouteCollection::current())['routes'][2];
$assert($callManifest['open'] === true && $callManifest['params'][0]['name'] === 'callId', 'The compiler marks RouteContext closures as open.');

// --- Link element and template tag ({ key: value } object literal)
Route::flush();
Route::screen('/', ArchV2Home::class)->name('home');
Route::screen('/chat/{chatId}', ArchV2LegacyChat::class)->name('chat');
$router = new RouteNavigator(RouteCollection::current());
RouteCollection::activate($router);
$link = Link::to('chat', ['chatId' => 'x'], Text::make('Open'))->toElement();
$link->events()[EventKind::Press->value]('');
$assert($router->root()->current()->all() === ['chatId' => 'x'], 'Link::to() navigates on press.');
$router->back();
$assert(TemplateExpression::normalizeObjectLiteral('{ chatId: x, title: \'T\', n: 2, nested: { a } }') === "['chatId' => \$x, 'title' => 'T', 'n' => 2, 'nested' => ['a' => \$a]]", 'Object literals compile to arrays.');
$template = TemplateCompiler::compile('<Column><Link route="chat" :params="{ chatId: x }"><Text>Open</Text></Link></Column>');
$element = TemplateRenderer::render($template, null, ['x' => '42']);
$pressable = $element->children()[0];
$assert($pressable instanceof Pressable, '<Link> renders a Pressable.');
$pressable->events()[EventKind::Press->value]('');
$assert($router->root()->current()->all() === ['chatId' => '42'], '<Link route :params> navigates with its params.');

// --- middleware runs once per navigation; tab presses go through it
Route::flush();
MiddlewareRegistry::reset();
$runs = [];
$allowed = true;
MiddlewareRegistry::alias([
    'count' => static function (Route $to, Closure $next) use (&$runs, &$allowed): mixed {
        $runs[] = $to->name();
        return $allowed ? $next($to) : redirect('home');
    },
]);
Route::screen('/', ArchV2Home::class)->name('home');
Route::middleware('count')->group(static function (): void {
    Route::tabs(static function (): void {
        Route::screen('/inbox', ArchV2Home::class)->name('inbox');
        Route::screen('/vault', ArchV2Home::class)->name('vault');
    });
    Route::screen('/chat/{chatId}', ArchV2LegacyChat::class)->name('chat');
});
$router = new RouteNavigator(RouteCollection::current());
RouteCollection::activate($router);
$runs = [];
navigate('chat', chatId: 'a');
$assert($runs === ['chat'], 'Middleware must run once per navigation (the stack guard reuses the verdict): '.json_encode($runs));
$runs = [];
navigate('inbox');
$assert($runs === ['inbox'] && Route::current()?->name() === 'inbox', 'A tab navigation runs the tab middleware once (host and tab press reuse it): '.json_encode($runs));
$tabs = (new ReflectionProperty(RouteNavigator::class, 'tabNavigators'))->getValue($router)['tabs'];
$runs = [];
$allowed = false;
$pressed = $tabs->select('vault');
$assert($pressed === false && $tabs->selectedTab() === 'inbox', 'A tab-bar press on a protected tab must be denied by its middleware.');
$assert($runs[0] === 'vault' && $router->root()->currentRoute() === 'home', 'A tab-bar press the middleware redirects must navigate to the redirect.');
$allowed = true;
$runs = [];
navigate('inbox');
$assert($tabs->select('vault') === true && $tabs->selectedTab() === 'vault' && in_array('vault', $runs, true), 'An allowed tab-bar press selects the tab.');

// --- an app with its own Linking listener gets a development warning
\Pam\Native\Internal\DevWarnings::reset();
$own = \Pam\Native\System\Linking::listen(static function (string $url): void {});
Route::flush();
Route::screen('/chat/{chatId}', ArchV2LegacyChat::class)->name('chat');
(new RouteNavigator(RouteCollection::current()))->listen();
$development = \Pam\Native\BuildConfiguration::mode() === \Pam\Native\BuildMode::Development;
$assert(!$development || str_contains(implode("\n", \Pam\Native\Internal\DevWarnings::messages()), 'Route::linking(false)'), 'A second Linking listener must warn in development.');
Route::flush();
Route::linking(false);
Route::screen('/chat/{chatId}', ArchV2LegacyChat::class)->name('chat');
\Pam\Native\Internal\DevWarnings::reset();
(new RouteNavigator(RouteCollection::current()))->listen();
$assert(\Pam\Native\Internal\DevWarnings::messages() === [], 'Route::linking(false) opts out silently.');
\Pam\Native\System\Linking::unsubscribe($own);

// --- App::run(require 'routes.php') returning 1 or a RouteCollection
Route::flush();
$required = (static fn () => require __DIR__.'/Fixtures/RoutesApp/routes.php')();
$assert($required === 1 && RouteCollection::current()->count() === 6, 'routes.php returns nothing; require yields 1 and the registry holds the routes.');

Route::flush();
MiddlewareRegistry::reset();
echo "Architecture v2 routing tests passed.\n";

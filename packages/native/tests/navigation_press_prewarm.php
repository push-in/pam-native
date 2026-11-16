<?php

declare(strict_types=1);

use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\LanguageVersion;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Navigation\NavigationPresentation;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\PressPrewarm;
use Pam\Native\Navigation\Router;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\PropKey;
use Pam\Native\RendererContract;
use Pam\Native\Route;
use Pam\Native\Routing\RedirectMode;
use Pam\Native\Testing;
use Pam\Native\UI\Link;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;
use TestingApp\Middleware\Authenticate;

use function Pam\Native\navigate;

$assert = require __DIR__.'/arch_v2_bootstrap.php';
require_once __DIR__.'/../src/Routing/functions.php';
require_once __DIR__.'/../src/Testing/functions.php';
require_once __DIR__.'/Fixtures/TestingApp/bootstrap.php';

$previousContract = (new ReflectionProperty(RendererContract::class, 'current'))->getValue();
RendererContract::use(RendererContract::local());
PressPrewarm::reset();

/** Native module requests still waiting for their result (timers armed and not fired). */
$pending = static fn (): array => (new ReflectionProperty(Runtime::class, 'moduleCallbacks'))->getValue();
/** Request id of the last native call armed (a release timer here), or null. */
$lastTimer = static fn (): ?int => array_key_last($pending());
$fire = static function (?int $requestId): void {
    if ($requestId !== null) Runtime::dispatchModuleResult($requestId, ModuleResultStatus::Success->value, '');
};
$event = static fn (\Pam\Native\Element $element, EventKind $kind): Closure => $element->events()[$kind->value]
    ?? throw new RuntimeException("Missing {$kind->name} handler.");
$isPure = static fn (Closure $handler): bool => (new ReflectionMethod(Runtime::class, 'isPureCallback'))->invoke(null, $handler);

// --- Encoding: the intent prop, the press-in/out handlers, older renderers ---
$intent = Navigator::intentKey('chat', ['id' => '7']);
$assert(
    $intent === Navigator::intentKey('chat', ['id' => '7']) && $intent !== Navigator::intentKey('chat', ['id' => '8'])
        && Navigator::intentKey('chat', ['a' => 1, 'b' => 2]) === Navigator::intentKey('chat', ['b' => 2, 'a' => 1])
        && Navigator::intentKey('chat', ['id' => 7]) !== $intent,
    'intentKey() is stable per route and params (any order), distinct for other params or types.',
);
$warmPress = Pressable::make(Text::make('Open'))->prewarm('chat', ['id' => '7']);
$assert(
    ($warmPress->properties()[PropKey::PressNavigationIntent->value] ?? null) === $intent
        && isset($warmPress->events()[EventKind::PressIn->value], $warmPress->events()[EventKind::PressOut->value])
        && $isPure($event($warmPress, EventKind::PressIn)) && $isPure($event($warmPress, EventKind::PressOut)),
    'Pressable::prewarm() sends PressNavigationIntent and pure press-in/out handlers (no whole-tree render).',
);
$typedPress = Pressable::make()->prewarm(new \Pam\Native\Routing\TypedRoute('chat', ['id' => '7']));
$assert(
    ($typedPress->properties()[PropKey::PressNavigationIntent->value] ?? null) === $intent
        && (Pressable::make()->navigatesTo('chat', ['id' => '7'])->properties()[PropKey::PressNavigationIntent->value] ?? null) === $intent
        && !isset(Pressable::make()->navigatesTo('chat', ['id' => '7'])->events()[EventKind::PressIn->value]),
    'A TypedRoute declares the same intent; navigatesTo() declares it without a press-in prewarm.',
);
RendererContract::use(RendererContract::legacy());
$legacyPress = Pressable::make()->prewarm('chat', ['id' => '7']);
$assert(
    !isset($legacyPress->properties()[PropKey::PressNavigationIntent->value])
        && isset($legacyPress->events()[EventKind::PressIn->value]),
    'Renderers without the native launch get no intent prop (it would be refused) but still warm on press-in.',
);
RendererContract::use(RendererContract::local());

$composedLog = [];
$composed = Pressable::make()
    ->onPressIn(static function (\Pam\Native\PressEvent $event) use (&$composedLog): void { $composedLog[] = 'in:'.$event->x; })
    ->prewarm('chat', ['id' => '7'])
    ->onPressOut(static function () use (&$composedLog): void { $composedLog[] = 'out'; });
$assert(
    !$isPure($event($composed, EventKind::PressIn)) && $event($composed, EventKind::PressOut) !== null,
    'An app press-in handler keeps its own purity when composed with the prewarm.',
);

// Templates: <Link ... prewarm> and <Pressable prewarm=... :prewarmParams=...>.
$template = static fn (string $source): \Pam\Native\Element => TemplateRenderer::render(
    TemplateCompiler::compile($source, 'PressPrewarm.pam', LanguageVersion::Language2),
    null,
    [],
);
$linkElement = $template('<Link route="chat" :params="{ id: \'7\' }" prewarm><Text>Open</Text></Link>');
$pressElement = $template('<Pressable prewarm="chat" :prewarmParams="{ id: \'7\' }"><Text>Open</Text></Pressable>');
$offElement = $template('<Pressable :prewarm="null"><Text>Open</Text></Pressable>');
$intentOnly = $template('<Pressable navigatesTo="chat" :navigatesToParams="{ id: \'7\' }"><Text>Open</Text></Pressable>');
$replaceLink = $template('<Link route="chat" :params="{ id: \'7\' }" mode="replace" prewarm><Text>Open</Text></Link>');
$assert(
    ($linkElement->properties()[PropKey::PressNavigationIntent->value] ?? null) === $intent
        && isset($linkElement->events()[EventKind::PressIn->value], $linkElement->events()[EventKind::Press->value])
        && ($pressElement->properties()[PropKey::PressNavigationIntent->value] ?? null) === $intent
        && isset($pressElement->events()[EventKind::PressOut->value])
        && !isset($offElement->properties()[PropKey::PressNavigationIntent->value])
        && !isset($offElement->events()[EventKind::PressIn->value])
        && !isset($replaceLink->properties()[PropKey::PressNavigationIntent->value])
        && ($intentOnly->properties()[PropKey::PressNavigationIntent->value] ?? null) === $intent
        && !isset($intentOnly->events()[EventKind::PressIn->value]),
    'Templates declare the destination with <Link prewarm>, <Pressable prewarm :prewarmParams> or navigatesTo; null disables it, replace does not warm.',
);

// --- Route API: press-in mounts, press reuses, press-out without press releases ---
Testing\reset(flushRoutes: true);
PressPrewarm::reset();
Authenticate::$signedIn = true;
$app = Testing\routes(__DIR__.'/Fixtures/TestingApp/routes.php');
$stack = $app->navigator->root();
$app->render();
$hostElement = static fn (): \Pam\Native\Element => $stack->render()->toElement();
$screenKeys = static fn (): array => array_map(
    static fn (\Pam\Native\Element $screen): ?string => $screen->elementKey(),
    $hostElement()->children(),
);
$top = static fn (): array => $stack->getState()['routes'][count($stack->getState()['routes']) - 1];

$event($linkElement, EventKind::PressIn)('');
$warmHost = $hostElement();
$warmKeys = $screenKeys();
$assert(
    $stack->isPrewarmed('chat', ['id' => '7']) && $stack->currentRoute() === 'home'
        && ($warmHost->properties()[PropKey::NavigationWarmIntent->value] ?? null) === $intent
        && ($warmHost->properties()[PropKey::NavigationWarmTransition->value] ?? null) === NavigationTransition::SlideFromRight->value
        && ($warmHost->properties()[PropKey::NavigationWarmDurationMs->value] ?? null) === 240
        && count($warmKeys) === 2,
    'Press-in mounts the destination hidden and the host advertises its intent and push motion: '.json_encode($warmKeys),
);
$event($linkElement, EventKind::PressOut)('');
$releaseTimer = $lastTimer();
$event($linkElement, EventKind::Press)('');
$pushedKeys = $screenKeys();
$assert(
    $top()['name'] === 'chat' && $top()['params'] === ['id' => '7'] && end($pushedKeys) === $warmKeys[0]
        && !isset($hostElement()->properties()[PropKey::NavigationWarmIntent->value]),
    'The press navigates to the warm screen itself (same element key, not mounted again): '.json_encode($pushedKeys),
);
$fire($releaseTimer);
$assert($top()['name'] === 'chat' && $stack->currentRoute() === 'chat', 'The release after a press that navigated is a no-op.');

navigate('home');
$app->render();
$event($linkElement, EventKind::PressIn)('');
$assert($stack->isPrewarmed('chat', ['id' => '7']), 'A second press-in warms again.');
$releasedWarm = $screenKeys()[0];
$event($linkElement, EventKind::PressOut)('');
$cancelTimer = $lastTimer();
$assert($cancelTimer !== null && $stack->isPrewarmed('chat', ['id' => '7']), 'Press-out keeps the screen for the press that may follow.');
$fire($cancelTimer);
$assert(
    !$stack->isPrewarmed('chat', ['id' => '7']) && $stack->currentRoute() === 'home'
        && !in_array($releasedWarm, $screenKeys(), true),
    'A press-out without a press (finger left, scroll) releases the screen it mounted.',
);

// Params changed between press-in and press: mounted normally, warm one released.
$event($linkElement, EventKind::PressIn)('');
$event($linkElement, EventKind::PressOut)('');
$staleRelease = $lastTimer();
$staleWarm = $screenKeys()[0];
Route::navigate('chat', ['id' => '8']);
$fire($staleRelease);
$changedKeys = $screenKeys();
$assert(
    $top()['params'] === ['id' => '8'] && end($changedKeys) !== $staleWarm
        && !$stack->isPrewarmed('chat', ['id' => '7']) && !in_array($staleWarm, $changedKeys, true),
    'Different params at the press mount a new screen and release the warm one.',
);
navigate('home');
$app->render();

// A navigate() back to an entry already in the stack has nothing to warm.
navigate('chat', ['id' => '9'], mode: 'push');
navigate('home', mode: 'push');
$app->render();
$backPress = Pressable::make()->prewarm('chat', ['id' => '9']);
$event($backPress, EventKind::PressIn)('');
$pushPress = Pressable::make()->prewarm('chat', ['id' => '9'], RedirectMode::Push);
$assert(!$stack->isPrewarmed('chat', ['id' => '9']), 'navigate() to an entry in the stack does not warm.');
$event($pushPress, EventKind::PressIn)('');
$assert($stack->isPrewarmed('chat', ['id' => '9']), 'A push to the same route warms a new screen.');
$stack->cancelPrewarm();
navigate('home', mode: 'reset');
$app->render();

// An idle warmup keeps its lifetime; the press only adds its intent.
$stack->prewarm('chat', ['id' => '7'], null);
$timerBefore = array_keys($pending());
$event($linkElement, EventKind::PressIn)('');
$event($linkElement, EventKind::PressOut)('');
$assert(
    array_keys($pending()) === $timerBefore && $stack->isPrewarmed('chat', ['id' => '7'])
        && ($hostElement()->properties()[PropKey::NavigationWarmIntent->value] ?? null) === $intent,
    'A press on a screen the app holds warm neither arms a release nor releases it.',
);
$stack->cancelPrewarm();

// Middleware: a destination the user may not open is never mounted.
Authenticate::$signedIn = false;
$event($linkElement, EventKind::PressIn)('');
$assert(!$stack->isPrewarmed('chat', ['id' => '7']) && !Route::prewarm('chat', ['id' => '7']), 'Middleware that refuses the route prevents the prewarm.');
Authenticate::$signedIn = true;
$assert(Route::prewarm('chat', ['id' => '7']) && $stack->isPrewarmed('chat', ['id' => '7']), 'Route::prewarm() validates and mounts like the press-in.');
$stack->cancelPrewarm();

// Composed handlers run both.
$composedLog = [];
$event($composed, EventKind::PressIn)(\Pam\Native\Internal\Wire::map(['x' => 3.0]));
$event($composed, EventKind::PressOut)('');
$assert(
    $composedLog === ['in:3', 'out'] && $stack->isPrewarmed('chat', ['id' => '7']),
    'The app press-in/out handlers still run with the prewarm: '.json_encode($composedLog),
);
$fire($lastTimer());
$assert(!$stack->isPrewarmed('chat', ['id' => '7']), 'Composed press-out still releases.');

$app->unmount();
Testing\reset(flushRoutes: true);
Authenticate::$signedIn = false;
PressPrewarm::reset();

// --- Only a plain card push launches natively ---
$launchStack = Router::stack('home')
    ->route('home', static fn () => Screen::make(Text::make('Home')))
    ->route('card', static fn () => Screen::make(Text::make('Card')), new ScreenOptions(animation: NavigationTransition::Fade, animationDurationMs: 180))
    ->route('sheet', static fn () => Screen::make(Text::make('Sheet')), new ScreenOptions(presentation: NavigationPresentation::FormSheet))
    ->route('secret', static fn () => Screen::make(Text::make('Secret')), new ScreenOptions(secure: true))
    ->build();
$launchStack->render();
$launchProps = static function (string $route) use ($launchStack): array {
    $launchStack->prewarm($route);
    $properties = $launchStack->render()->toElement()->properties();
    $launchStack->cancelPrewarm();

    return [
        $properties[PropKey::NavigationWarmIntent->value] ?? null,
        $properties[PropKey::NavigationWarmTransition->value] ?? null,
        $properties[PropKey::NavigationWarmDurationMs->value] ?? null,
    ];
};
$assert(
    $launchProps('card') === [Navigator::intentKey('card'), NavigationTransition::Fade->value, 180]
        && $launchProps('sheet') === [null, null, null]
        && $launchProps('secret') === [null, null, null],
    'The host advertises the route\'s own motion for a card push only (no sheet, no secure screen).',
);
RendererContract::use(RendererContract::legacy());
$launchStack->prewarm('card');
$assert(
    !isset($launchStack->render()->toElement()->properties()[PropKey::NavigationWarmIntent->value]),
    'Older renderers never receive the warm launch props.',
);
$launchStack->cancelPrewarm();

RendererContract::use($previousContract);

echo "NAVIGATION_PRESS_PREWARM_OK intent template press-in press-out reuse params middleware launch-props\n";

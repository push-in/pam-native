<?php

declare(strict_types=1);

use Pam\Native\Component;
use Pam\Native\Navigation\InteractsWithNavigationLifecycle;
use Pam\Native\Navigation\NavigationLifecycleAware;
use Pam\Native\Navigation\RouteContext;
use Pam\Native\Renderable;
use Pam\Native\Route;
use Pam\Native\Testing;
use Pam\Native\UI\Text;

use function Pam\Native\back;
use function Pam\Native\navigate;
use function Pam\Native\onBlur;
use function Pam\Native\onFocus;
use function Pam\Native\onMounted;

$assert = require __DIR__.'/arch_v2_bootstrap.php';
require_once __DIR__.'/../src/Routing/functions.php';
require_once __DIR__.'/../src/Testing/functions.php';

final class FocusOrderLog
{
    /** @var list<string> */
    public static array $entries = [];
}

final class FocusOrderResource
{
    public function __construct(public readonly string $owner) {}
}

/** A screen whose focus handlers use what setup() created. */
abstract class FocusOrderScreen extends Component implements NavigationLifecycleAware
{
    use InteractsWithNavigationLifecycle;

    public ?FocusOrderResource $resource = null;

    abstract protected function label(): string;

    public function setup(): void
    {
        $label = $this->label();
        $this->resource = new FocusOrderResource($label);
        FocusOrderLog::$entries[] = "{$label}:setup";
        onMounted(static fn () => FocusOrderLog::$entries[] = "{$label}:mounted");
        onFocus(fn () => FocusOrderLog::$entries[] = "{$label}:onFocus:".($this->resource?->owner ?? 'null'));
        onBlur(static fn () => FocusOrderLog::$entries[] = "{$label}:onBlur");
    }

    public function navigationFocused(RouteContext $route): void
    {
        FocusOrderLog::$entries[] = "{$this->label()}:focused:".($this->resource?->owner ?? 'null');
    }

    public function navigationBlurred(RouteContext $route): void
    {
        FocusOrderLog::$entries[] = "{$this->label()}:blurred";
    }

    public function render(): Renderable
    {
        return Text::make($this->label());
    }
}

final class FocusOrderHome extends FocusOrderScreen
{
    protected function label(): string { return 'home'; }
}

final class FocusOrderProfile extends FocusOrderScreen
{
    protected function label(): string { return 'profile'; }
}

final class FocusOrderSheet extends FocusOrderScreen
{
    protected function label(): string { return 'sheet'; }
}

final class FocusOrderFeed extends FocusOrderScreen
{
    protected function label(): string { return 'feed'; }
}

final class FocusOrderInbox extends FocusOrderScreen
{
    protected function label(): string { return 'inbox'; }
}

$drain = static function (): array {
    $entries = FocusOrderLog::$entries;
    FocusOrderLog::$entries = [];

    return $entries;
};

Testing\reset(flushRoutes: true);
Route::screen('/', FocusOrderHome::class)->name('home');
Route::screen('/profile', FocusOrderProfile::class)->name('profile')->keepAlive();
Route::modal('/sheet', FocusOrderSheet::class)->name('sheet');
Route::tabs(static function (): void {
    Route::screen('/feed', FocusOrderFeed::class)->name('feed');
    Route::screen('/inbox', FocusOrderInbox::class)->name('inbox');
});
FocusOrderLog::$entries = [];
$app = Testing\routes();

// First screen: setup -> onMounted -> focus (navigationFocused, then onFocus hooks).
$log = $drain();
$assert(
    $log === ['home:setup', 'home:mounted', 'home:focused:home', 'home:onFocus:home'],
    'The initial screen focuses after its first mount: '.json_encode($log),
);

// Route::navigate to a new screen: the outgoing one blurs, the new one focuses only once mounted.
$app->navigate('profile');
$log = $drain();
$assert(
    $log === ['home:blurred', 'home:onBlur', 'profile:setup', 'profile:mounted', 'profile:focused:profile', 'profile:onFocus:profile'],
    'Route::navigate focuses after setup() and onMounted(): '.json_encode($log),
);

// Back refocuses the retained screen without running setup() again.
$app->back();
$log = $drain();
$assert(
    array_values(array_filter($log, static fn (string $entry): bool => str_starts_with($entry, 'home:'))) === ['home:focused:home', 'home:onFocus:home']
    && in_array('profile:blurred', $log, true)
    && array_search('profile:blurred', $log, true) < array_search('home:focused:home', $log, true),
    'Back blurs the popped screen, then refocuses the retained one: '.json_encode($log),
);

// A keep-alive screen opened again reuses its instance: focus without setup().
$app->navigate('profile');
$log = $drain();
$assert(
    !in_array('profile:setup', $log, true) && array_slice($log, -2) === ['profile:focused:profile', 'profile:onFocus:profile'],
    'A kept-alive screen refocuses without another setup(): '.json_encode($log),
);

// A modal mounts first, then focuses.
$app->navigate('sheet');
$log = $drain();
$assert(
    array_slice($log, -4) === ['sheet:setup', 'sheet:mounted', 'sheet:focused:sheet', 'sheet:onFocus:sheet']
    && in_array('profile:blurred', $log, true),
    'A modal focuses after its first mount: '.json_encode($log),
);
back();
$app->render();
$drain();

// Tabs: the selected tab's onFocus() follows its mount; switching tabs keeps the order.
navigate('feed');
$app->render();
$log = $drain();
$assert(
    array_slice($log, -3) === ['feed:setup', 'feed:mounted', 'feed:onFocus:feed'],
    'A tab screen focuses after its first mount: '.json_encode($log),
);
navigate('inbox');
$app->render();
$log = $drain();
$assert(
    array_slice($log, -3) === ['inbox:setup', 'inbox:mounted', 'inbox:onFocus:inbox']
    && in_array('feed:onBlur', $log, true),
    'Switching tabs blurs the old tab and focuses the new one after its mount: '.json_encode($log),
);

$app->unmount();
Testing\reset(flushRoutes: true);

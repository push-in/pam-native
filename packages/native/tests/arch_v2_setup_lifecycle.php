<?php

declare(strict_types=1);

use Pam\Native\Appearance;
use Pam\Native\Component;
use Pam\Native\Composables\SharedSource;
use Pam\Native\Composables\Sources;
use Pam\Native\Internal\ComponentLifecycle;
use Pam\Native\Navigation\RouteContext;
use Pam\Native\Renderable;
use Pam\Native\Routing\ScreenFactory;
use Pam\Native\Signals\Signal;
use Pam\Native\UI\Text;

use function Pam\Native\onBlur;
use function Pam\Native\onFocus;
use function Pam\Native\onMounted;
use function Pam\Native\onUnmounted;
use function Pam\Native\signal;
use function Pam\Native\useColorScheme;
use function Pam\Native\useKeyboard;
use function Pam\Native\watch;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

enum ArchV2Tab: string
{
    case Chats = 'chats';
    case Feed = 'feed';
}

final class ArchV2SetupLog
{
    /** @var list<string> */
    public static array $log = [];
}

final class ArchV2ChatScreen extends Component
{
    public ?Signal $draft = null;
    public string $id = '';
    public int $page = 0;
    public ?ArchV2Tab $tab = null;
    public ?Component $child = null;

    public function setup(string $id, int $page = 1, ?ArchV2Tab $tab = null): void
    {
        $this->id = $id;
        $this->page = $page;
        $this->tab = $tab;
        $this->draft = signal('');
        useColorScheme();
        useKeyboard();
        watch($this->draft, static function (string $next): void {
            ArchV2SetupLog::$log[] = "draft:{$next}";
        });
        onMounted(static fn () => ArchV2SetupLog::$log[] = 'mounted');
        onUnmounted(static fn () => ArchV2SetupLog::$log[] = 'unmounted');
        onFocus(static fn () => ArchV2SetupLog::$log[] = 'focus');
        onBlur(static fn () => ArchV2SetupLog::$log[] = 'blur');
        ArchV2SetupLog::$log[] = "setup:{$id}:{$page}";
    }

    public function mount(): void
    {
        ArchV2SetupLog::$log[] = 'mount';
    }

    public function render(): Renderable
    {
        return $this->child ?? Text::make("chat {$this->id}");
    }
}

final class ArchV2ChildPanel extends Component
{
    public function setup(): void
    {
        onFocus(static fn () => ArchV2SetupLog::$log[] = 'child-focus');
        onBlur(static fn () => ArchV2SetupLog::$log[] = 'child-blur');
    }

    public function render(): Renderable
    {
        return Text::make('child');
    }
}

final class ArchV2RetainedScreen extends Component
{
    public int $setups = 0;
    public int $events = 0;

    public function __construct(private readonly \Pam\Native\Events\EventBus $bus)
    {
    }

    // Work only: no watchers, composables or hooks.
    public function setup(): void
    {
        $this->setups++;
        $this->own($this->bus->on('ping', fn () => ++$this->events));
        $this->timers()->every(1_000, static fn () => null);
    }

    public function timerCount(): int
    {
        return $this->timers()->count();
    }

    public function render(): Renderable
    {
        return Text::make('retained');
    }
}

final class ArchV2LegacyScreen extends Component
{
    public int $mounted = 0;

    public function mount(): void
    {
        $this->mounted++;
    }

    public function render(): Renderable
    {
        return Text::make('legacy');
    }
}

$mount = static function (Component $component): void {
    ComponentLifecycle::beginRender();
    $component->toElement();
    ComponentLifecycle::finishRender();
    ComponentLifecycle::commit();
};
$unmountAll = static function (): void {
    ComponentLifecycle::beginRender();
    ComponentLifecycle::finishRender();
    ComponentLifecycle::commit();
};
$appearanceListeners = static function (): int {
    $property = new ReflectionProperty(Appearance::class, 'listeners');

    return count($property->getValue());
};

Sources::reset();
$baseline = $appearanceListeners();

// Route parameters reach setup() by name, converted to the declared types.
$screen = ScreenFactory::make(ArchV2ChatScreen::class, new RouteContext('chat', ['id' => 'abc', 'page' => '3', 'tab' => 'feed']));
$assert($screen instanceof ArchV2ChatScreen, 'ScreenFactory must build a setup() screen.');
ArchV2SetupLog::$log = [];
$mount($screen);
$assert($screen->id === 'abc' && $screen->page === 3 && $screen->tab === ArchV2Tab::Feed, 'setup() must receive typed route parameters.');
$assert(ArchV2SetupLog::$log === ['setup:abc:3', 'mount', 'mounted'], 'setup() runs before mount(), onMounted() after the commit: '.json_encode(ArchV2SetupLog::$log));
$assert(SharedSource::active() === ['appearance' => 1, 'keyboard' => 1] && $appearanceListeners() === $baseline + 1, 'Composables in setup() must hold one shared subscription while mounted.');

// A routed screen follows navigation focus, and so do its children.
$screen->__pamNavigationFocus(true);
$screen->draft->value = 'hi';
$screen->__pamNavigationFocus(false);
$assert(array_slice(ArchV2SetupLog::$log, 3) === ['focus', 'draft:hi', 'blur'], 'onFocus/onBlur must follow navigation: '.json_encode(ArchV2SetupLog::$log));

// Unmount releases everything setup() created: watchers, composables, hooks.
$draft = $screen->draft;
$unmountAll();
$draft->value = 'after';
$assert(end(ArchV2SetupLog::$log) === 'unmounted', 'onUnmounted() must run at unmount.');
$assert(!in_array('draft:after', ArchV2SetupLog::$log, true), 'Watchers created in setup() must stop at unmount.');
$assert(SharedSource::active() === [] && $appearanceListeners() === $baseline, 'Unmount must release every composable subscription (no leaks).');

// Remounting the same instance runs setup() again and re-subscribes.
ArchV2SetupLog::$log = [];
$mount($screen);
$assert(ArchV2SetupLog::$log === ['setup:abc:3', 'mount', 'mounted'] && SharedSource::active() === ['appearance' => 1, 'keyboard' => 1], 'A remount must run setup() again: '.json_encode(ArchV2SetupLog::$log));
$screen->__pamNavigationFocus(true);
$unmountAll();
$assert(array_slice(ArchV2SetupLog::$log, -2) === ['blur', 'unmounted'] && SharedSource::active() === [], 'Unmounting a focused screen must blur it, then release it.');

// Children of a routed screen follow its focus; outside a route a component is focused while mounted.
$parent = ScreenFactory::make(ArchV2ChatScreen::class, new RouteContext('chat', ['id' => 'p']));
$parent->child = new ArchV2ChildPanel();
ArchV2SetupLog::$log = [];
$mount($parent);
$parent->__pamNavigationFocus(true);
$parent->__pamNavigationFocus(false);
$assert(
    array_values(array_filter(ArchV2SetupLog::$log, static fn (string $entry): bool => str_starts_with($entry, 'child'))) === ['child-focus', 'child-blur'],
    'A child component must follow the focus of its screen: '.json_encode(ArchV2SetupLog::$log),
);
$unmountAll();
$standalone = new ArchV2ChildPanel();
ArchV2SetupLog::$log = [];
$mount($standalone);
$unmountAll();
$assert(ArchV2SetupLog::$log === ['child-focus', 'child-blur'], 'Outside a route, focus follows mount and unmount.');

// Clear errors: lifecycle hooks outside setup(), missing or mistyped route parameters.
$errors = [];
foreach ([
    static fn () => onMounted(static fn () => null),
    static fn () => $mount(ScreenFactory::make(ArchV2ChatScreen::class, new RouteContext('chat', ['id' => 'x', 'page' => 'two']))),
] as $attempt) {
    try {
        $attempt();
    } catch (LogicException $error) {
        $errors[] = $error->getMessage();
    }
}
$unmountAll();
$assert(
    count($errors) === 2 && str_contains($errors[0], 'onMounted() must be called inside') && str_contains($errors[1], '$page expects int, "two"'),
    'Lifecycle hooks and setup() parameters must fail with clear messages: '.json_encode($errors),
);
$missing = null;
try {
    $component = new ArchV2ChatScreen();
    $mount($component);
} catch (LogicException $error) {
    $missing = $error->getMessage();
}
$unmountAll();
$assert($missing !== null && str_contains($missing, 'requires the route parameter $id'), 'A required setup() parameter without a route value must be a clear error.');

// A retained screen (keepAlive) whose setup() only does work runs setup() on every
// remount, and its own()/timer subscriptions never pile up.
$bus = new \Pam\Native\Events\EventBus();
$retained = new ArchV2RetainedScreen($bus);
$mount($retained);
$unmountAll();
$assert($bus->listeners('ping') === 0 && $retained->timerCount() === 0, 'Unmount must release own() subscriptions and timers created in setup().');
$mount($retained);
$bus->emit('ping');
$assert(
    $retained->setups === 2 && $bus->listeners('ping') === 1 && $retained->timerCount() === 1 && $retained->events === 1,
    'A remounted retained screen must run setup() again without accumulating subscriptions.',
);
$unmountAll();
$assert($bus->listeners('ping') === 0 && $retained->timerCount() === 0, 'The second unmount must release them again.');

// Backward compatibility: mount() without setup() keeps working.
$legacy = new ArchV2LegacyScreen();
$mount($legacy);
$assert($legacy->mounted === 1 && $legacy->__pamScope() === null, 'Components without setup() keep the mount() lifecycle.');
$unmountAll();
ComponentLifecycle::shutdown();
Sources::reset();

echo "ARCH_V2_SETUP_OK params lifecycle focus remount no-leak\n";

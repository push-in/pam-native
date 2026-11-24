<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Component;
use Pam\Native\Disposable;
use Pam\Native\Element;
use Pam\Native\Events\Subscription;
use Pam\Native\Internal\Runtime;
use Pam\Native\ModuleResultStatus;
use Pam\Native\System\ComponentTimer;
use Pam\Native\UI\Column;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

// Disposable handles (timers, subscriptions, players) enter the state
// snapshot by presence only: their own state and swapping one handle for
// another never render; null <-> handle and ordinary fields still do.

Runtime::shutdown();

/** Stand-in for a media player handle whose progress ticks natively. */
final class DisposableSnapshotPlayer implements Disposable
{
    public int $positionMillis = 0;
    private bool $stopped = false;

    public function tick(): void
    {
        $this->positionMillis += 250;
    }

    public function dispose(): void
    {
        $this->stopped = true;
    }

    public function disposed(): bool
    {
        return $this->stopped;
    }
}

/** Same state without Disposable: a plain object, expanded and compared by value. */
final class DisposableSnapshotPlainProgress
{
    public int $positionMillis = 0;
}

final class DisposableSnapshotScreen extends Component
{
    public static int $renders = 0;

    /** Not read by render(). */
    private ?ComponentTimer $tick = null;

    /** Not read by render(). */
    private ?DisposableSnapshotPlayer $player = null;

    /** Untyped: the value decides, as for dynamic properties. */
    private $subscription = null;

    /** Static: a fire counter on the instance would itself be state. */
    public static int $fired = 0;

    /** Read by render(). */
    private string $title = 'title-one';

    /** Read by render() (in-place mutations of plain objects keep rendering). */
    private DisposableSnapshotPlainProgress $plain;

    public function __construct()
    {
        $this->plain = new DisposableSnapshotPlainProgress();
    }

    public function mount(): void
    {
        $this->arm();
        $this->player = new DisposableSnapshotPlayer();
        $this->subscription = Subscription::from(static function (): void {});
    }

    /** The timer callback re-arms by assigning a fresh handle to the field. */
    public function arm(): void
    {
        $this->tick = $this->timers()->timeout(1_000, function (): void {
            self::$fired++;
            $this->arm();
        });
    }

    public function progress(): void
    {
        $this->player?->tick();
    }

    public function swapPlayer(): void
    {
        $this->player = new DisposableSnapshotPlayer();
    }

    public function swapSubscription(): void
    {
        $this->subscription = Subscription::from(static function (): void {});
    }

    public function clearTimer(): void
    {
        $this->tick?->cancel();
        $this->tick = null;
    }

    public function retitle(string $title): void
    {
        $this->title = $title;
    }

    public function plainTick(): void
    {
        $this->plain->positionMillis += 250;
    }

    public function render(): Element
    {
        self::$renders++;

        return Screen::make(Column::make(
            Text::make($this->title),
            Text::make("plain-{$this->plain->positionMillis}"),
        ))->toElement();
    }
}

$disposableCallback = static function (Closure $callback): void {
    $request = Runtime::call('test.disposable', 'noop', '', static function () use ($callback): void {
        $callback();
    });
    Runtime::dispatchModuleResult($request, ModuleResultStatus::Success->value, '');
};
$disposableArmedTimer = static function (): int {
    $call = TestDiagnostics::$moduleCall;
    if ($call === null || $call['module'] !== 'timers') {
        throw new RuntimeException('No native timer was armed.');
    }

    return $call['requestId'];
};

$disposableScreen = new DisposableSnapshotScreen();
App::run($disposableScreen);
$disposableTimer = $disposableArmedTimer();
$disposableRenders = DisposableSnapshotScreen::$renders;
$assert($disposableRenders >= 1, 'The disposable snapshot screen must render at boot.');

$frameBefore = Runtime::lastFrame();
Runtime::dispatchModuleResult($disposableTimer, ModuleResultStatus::Success->value, '');
Runtime::dispatchModuleResult($disposableArmedTimer(), ModuleResultStatus::Success->value, '');
$assert(
    DisposableSnapshotScreen::$fired === 2
        && DisposableSnapshotScreen::$renders === $disposableRenders
        && Runtime::lastFrame() === $frameBefore,
    'A timer callback that re-assigns the field to a new ComponentTimer must neither render nor commit.',
);

$disposableCallback(static function () use ($disposableScreen): void {
    $disposableScreen->progress();
    $disposableScreen->progress();
});
$assert(
    DisposableSnapshotScreen::$renders === $disposableRenders,
    'Progress ticking inside a Disposable player held in a field must not render.',
);

$disposableCallback(static function () use ($disposableScreen): void {
    $disposableScreen->swapPlayer();
    $disposableScreen->swapSubscription();
});
$assert(
    DisposableSnapshotScreen::$renders === $disposableRenders,
    'Swapping a handle for another of the same class (typed or untyped field) must not render.',
);

$assert(
    $disposableScreen->__pamPlainState() === false,
    'A plain object read by the template must still disable exact snapshots.',
);

$disposableCallback(static function () use ($disposableScreen): void {
    $disposableScreen->plainTick();
});
$assert(
    DisposableSnapshotScreen::$renders === $disposableRenders + 1
        && str_contains((string) Runtime::lastFrame(), 'plain-250'),
    'An in-place change of a plain (non-Disposable) object must keep rendering.',
);

$disposableCallback(static function () use ($disposableScreen): void {
    $disposableScreen->retitle('title-two');
});
$assert(
    DisposableSnapshotScreen::$renders === $disposableRenders + 2
        && str_contains((string) Runtime::lastFrame(), 'title-two'),
    'A plain field read by the template must keep rendering.',
);

$disposableCallback(static function () use ($disposableScreen): void {
    $disposableScreen->clearTimer();
});
$assert(
    DisposableSnapshotScreen::$renders === $disposableRenders + 3,
    'Setting a handle field to null must still render (templates may test `!== null`).',
);

$disposableCallback(static function () use ($disposableScreen): void {
    $disposableScreen->arm();
});
$assert(
    DisposableSnapshotScreen::$renders === $disposableRenders + 4,
    'Setting a null handle field to a handle must still render.',
);
Runtime::shutdown();

/** Only scalars and handles: pure-handler inference keeps an exact snapshot. */
final class DisposableSnapshotPlainScreen extends Component
{
    private ?DisposableSnapshotPlayer $player = null;
    private int $count = 0;

    public function mount(): void
    {
        $this->player = new DisposableSnapshotPlayer();
    }

    public function render(): Element
    {
        return Screen::make(Text::make("count-{$this->count}"))->toElement();
    }
}

$disposablePlain = new DisposableSnapshotPlainScreen();
App::run($disposablePlain);
$assert(
    $disposablePlain->__pamPlainState(),
    'Disposable handles in plain fields must not disable exact snapshots (pure handler inference).',
);
Runtime::shutdown();

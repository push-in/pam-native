<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Attributes\State as StateAttribute;
use Pam\Native\Attributes\Transient;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Internal\Runtime;
use Pam\Native\ModuleResultStatus;
use Pam\Native\UI\Column;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

// #[Transient]: fields written after render (hooks, timers, callbacks) that
// render() never reads stay out of the state snapshot and cost no render.

Runtime::shutdown();

trait TransientTestSubscription
{
    #[Transient]
    private ?stdClass $subscription = null;

    #[Transient]
    protected int $resumes = 0;

    public function resumed(): void
    {
        $this->resumes++;
        $this->subscription = new stdClass();
        $this->subscription->id = $this->resumes;
    }
}

final class TransientTestScreen extends Component
{
    use TransientTestSubscription;

    public static int $renders = 0;

    /** Read by render(): any write still renders. */
    private string $title = 'title-one';

    /** Bookkeeping render() never reads, written after every render. */
    #[Transient]
    private int $renderedCount = 0;

    #[Transient]
    private array $cache = [];

    #[Transient]
    public ?float $lastSeenAt = null;

    /** Plain private field render() never reads (no attribute). */
    private int $untracked = 0;

    #[StateAttribute]
    private int $count = 0;

    public function rendered(): void
    {
        $this->renderedCount++;
    }

    public function touchCache(): void
    {
        $this->cache['k'.count($this->cache)] = microtime(true);
        $this->lastSeenAt = microtime(true);
    }

    public function touchUntracked(): void
    {
        $this->untracked++;
    }

    public function retitle(string $title): void
    {
        $this->title = $title;
    }

    public function increment(): void
    {
        $this->count++;
    }

    public function renderedCount(): int
    {
        return $this->renderedCount;
    }

    public function resumes(): int
    {
        return $this->resumes;
    }

    public function render(): Element
    {
        self::$renders++;

        return Screen::make(Column::make(
            Text::make($this->title),
            Text::make("count-{$this->count}"),
        ))->toElement();
    }
}

/** Same post-render hook without the attribute: today's semantics. */
final class TransientTestControl extends Component
{
    public static int $renders = 0;
    private int $renderedCount = 0;

    public function rendered(): void
    {
        $this->renderedCount++;
    }

    public function render(): Element
    {
        self::$renders++;

        return Screen::make(Text::make('control'))->toElement();
    }
}

$transientResult = static function (Closure $callback): void {
    $request = Runtime::call('test.transient', 'noop', '', static function () use ($callback): void {
        $callback();
    });
    Runtime::dispatchModuleResult($request, ModuleResultStatus::Success->value, '');
};

$transientControl = new TransientTestControl();
App::run($transientControl);
$transientResult(static function (): void {});
$transientResult(static function (): void {});
$assert(
    TransientTestControl::$renders === 3,
    'Without #[Transient], a private field written by rendered() must keep rendering the component on every pass.',
);
Runtime::shutdown();

$transientScreen = new TransientTestScreen();
App::run($transientScreen);
$transientRenders = TransientTestScreen::$renders;
$assert($transientRenders === 1 && $transientScreen->resumes() === 1, 'The transient test screen must render and resume once at boot.');

$transientResult(static function (): void {});
$transientResult(static function (): void {});
$assert(
    TransientTestScreen::$renders === $transientRenders && $transientScreen->renderedCount() === 3,
    'A #[Transient] field written by rendered() (and a trait #[Transient] subscription written by resumed()) must not re-render.',
);

$frameBefore = Runtime::lastFrame();
$transientResult(static function () use ($transientScreen): void {
    $transientScreen->touchCache();
});
$assert(
    TransientTestScreen::$renders === $transientRenders && Runtime::lastFrame() === $frameBefore,
    'Writing #[Transient] private and public fields from a callback must neither render nor commit.',
);

$transientResult(static function () use ($transientScreen): void {
    $transientScreen->touchUntracked();
});
$assert(
    TransientTestScreen::$renders === $transientRenders + 1,
    'A private field without #[Transient] must keep rendering when written (unchanged default semantics).',
);

$transientResult(static function () use ($transientScreen): void {
    $transientScreen->retitle('title-two');
});
$assert(
    TransientTestScreen::$renders === $transientRenders + 2
        && str_contains((string) Runtime::lastFrame(), 'title-two'),
    'A private field read by render() must keep re-rendering the component.',
);

$transientResult(static function () use ($transientScreen): void {
    $transientScreen->increment();
});
$assert(
    TransientTestScreen::$renders === $transientRenders + 3
        && str_contains((string) Runtime::lastFrame(), 'count-1'),
    'A #[State] field must keep re-rendering the component.',
);

$transientResult(static function () use ($transientScreen): void {
    $transientScreen->touchCache();
    $transientScreen->retitle('title-three');
});
$assert(
    TransientTestScreen::$renders === $transientRenders + 4
        && str_contains((string) Runtime::lastFrame(), 'title-three'),
    'A transient write next to a real state change must not hide that change.',
);

$assert(
    $transientScreen->__pamPlainState(),
    'Objects held only by #[Transient] fields must not disable exact snapshots (pure handler inference).',
);
Runtime::shutdown();

<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Attributes\PureHandler;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\MediaClockFormat;
use Pam\Native\PropKey;
use Pam\Native\UI\FlatList;
use Pam\Native\UI\MediaPlayer;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Scroll;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;
use Pam\Native\UI\VirtualizedList;

// 1.41.0 protocol additions are append-only.
$assert(
    EventKind::RouteCovered->value === 78 && EventKind::RouteFocused->value === 79
        && PropKey::MediaKeepAliveWhenCovered->value === 530
        && PropKey::OnRouteCovered->value === 531
        && PropKey::OnRouteFocused->value === 532
        && PropKey::ScrollAnimated->value === 533
        && PropKey::MediaClock->value === 534
        && PropKey::MediaClockFormat->value === 535
        && PropKey::MediaProgress->value === 536
        && array_map(static fn (MediaClockFormat $f): int => $f->value, MediaClockFormat::cases()) === [1, 2, 3, 4, 5],
    'Route coverage, animated scroll and media clock protocol ids must be appended in order.',
);

// Route focus events and the covered-route opt-out.
$routeEvents = [];
$routeView = View::make(Text::make('Feed'))
    ->onRouteCovered(static function () use (&$routeEvents): void { $routeEvents[] = 'covered'; })
    ->onRouteFocused(static function () use (&$routeEvents): void { $routeEvents[] = 'focused'; })
    ->toElement();
$routeView->events()[EventKind::RouteCovered->value]('');
$routeView->events()[EventKind::RouteFocused->value]('');
$player = MediaPlayer::make('https://cdn.example/reel.mp4')->keepAliveWhenCovered()->toElement();
$assert(
    ($routeView->properties()[PropKey::OnRouteCovered->value] ?? null) === true
        && ($routeView->properties()[PropKey::OnRouteFocused->value] ?? null) === true
        && $routeEvents === ['covered', 'focused']
        && ($player->properties()[PropKey::MediaKeepAliveWhenCovered->value] ?? null) === true
        && !isset(MediaPlayer::make('a.mp4')->toElement()->properties()[PropKey::MediaKeepAliveWhenCovered->value]),
    'onRouteCovered/onRouteFocused must register native route events; release on cover is the default.',
);

// Native-bound clock and progress.
$clock = Text::make('0:00')->mediaClock('reel', MediaClockFormat::Remaining)->toElement();
$bar = View::make()->mediaProgress('reel')->toElement();
$assert(
    $clock->properties()[PropKey::MediaClock->value] === 'reel'
        && $clock->properties()[PropKey::MediaClockFormat->value] === MediaClockFormat::Remaining->value
        && $bar->properties()[PropKey::MediaProgress->value] === 'reel',
    'mediaClock/mediaProgress must bind to the player nativeRef without a PHP tick.',
);
$rejectedRef = false;
try {
    Text::make('')->mediaClock('bad ref');
} catch (InvalidArgumentException) {
    $rejectedRef = true;
}
$assert($rejectedRef, 'mediaClock must validate the nativeRef like nativeRef() does.');

// Animated scroll requests on every scrolling element.
foreach ([
    Scroll::make(Text::make('a'))->scrollRequest(2, targetOffset: 0.0, animated: true),
    VirtualizedList::make(Text::make('row'))->scrollRequest(3, targetOffset: 0.0, animated: true),
    FlatList::make(['a'])->scrollRequest(4, targetOffset: 0.0, animated: true),
] as $scroller) {
    $properties = $scroller->toElement()->properties();
    $assert(
        $properties[PropKey::ScrollAnimated->value] === true && $properties[PropKey::ScrollTargetOffset->value] === 0.0,
        'scrollRequest(animated: true) must send ScrollAnimated with the target.',
    );
}
$assert(
    Scroll::make(Text::make('a'))->scrollRequest(1, targetOffset: 0.0)->toElement()
        ->properties()[PropKey::ScrollAnimated->value] === false,
    'scrollRequest must default to an exact (non-animated) jump.',
);

$coverageScope = new class {
    public array $log = [];

    public function covered(): void
    {
        $this->log[] = 'covered';
    }

    public function focused(): void
    {
        $this->log[] = 'focused';
    }
};
$coverageTemplate = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Column on:routeCovered="covered" on:routeFocused="focused">'
        .'<Video source="https://cdn.example/a.mp4" nativeRef="reel" keepAliveWhenCovered="true" />'
        .'<Text mediaClock="reel" mediaClockFormat="countdown">0</Text>'
        .'<View mediaProgress="reel" />'
        .'<Scroll scrollRequest="2" scrollTargetOffset="0" scrollAnimated="true"><Text>x</Text></Scroll>'
        .'</Column>',
        'route-coverage.pam.php',
    ),
    $coverageScope,
    [],
);
[$templateMedia, $templateClock, $templateBar, $templateScroll] = $coverageTemplate->children();
$coverageTemplate->events()[EventKind::RouteCovered->value]('');
$coverageTemplate->events()[EventKind::RouteFocused->value]('');
$assert(
    $coverageScope->log === ['covered', 'focused']
        && $templateMedia->properties()[PropKey::MediaKeepAliveWhenCovered->value] === true
        && $templateClock->properties()[PropKey::MediaClock->value] === 'reel'
        && $templateClock->properties()[PropKey::MediaClockFormat->value] === MediaClockFormat::CountdownSeconds->value
        && $templateBar->properties()[PropKey::MediaProgress->value] === 'reel'
        && $templateScroll->properties()[PropKey::ScrollAnimated->value] === true,
    'Templates must expose on:routeCovered/on:routeFocused, keepAliveWhenCovered, mediaClock, mediaProgress and scrollAnimated.',
);

// Pure handlers: an event that changes nothing must not render.
final class PureEventProbe extends Component
{
    public static int $renders = 0;
    public static int $ticks = 0;
    public string $label = 'idle';

    #[PureHandler]
    public function tick(): void
    {
        self::$ticks++;
    }

    #[PureHandler]
    public function tickAndChange(): void
    {
        self::$ticks++;
        $this->label = 'changed-'.self::$ticks;
    }

    public function impure(): void
    {
        self::$ticks++;
    }

    public function render(): Element
    {
        self::$renders++;

        return TemplateRenderer::render(
            TemplateCompiler::compile(
                '<Column>'
                .'<Pressable on:press="tick" testID="pure"><Text>{{ $label }}</Text></Pressable>'
                .'<Pressable on:press="tickAndChange" testID="pure-change"><Text>b</Text></Pressable>'
                .'<Pressable on:press="impure" testID="impure"><Text>c</Text></Pressable>'
                .'<Pressable on:press="'.'tick'.'" testID="closure"><Text>d</Text></Pressable>'
                .'</Column>',
                'pure-event-probe.pam.php',
            ),
            $this,
            [],
        );
    }
}

Runtime::shutdown();
$pureProbe = new PureEventProbe();
App::run($pureProbe);
$callbacks = static fn (): array => (new ReflectionProperty(Runtime::class, 'eventCallbacks'))->getValue();
$pressNode = static function (string $method) use ($callbacks): int {
    foreach ($callbacks() as $key => $callback) {
        [$node, $kind] = array_map('intval', explode(':', (string) $key));
        if ($kind !== EventKind::Press->value) {
            continue;
        }
        $used = (new ReflectionFunction($callback))->getClosureUsedVariables();
        $method_ = $used['method'] ?? null;
        if ($method_ instanceof ReflectionMethod && $method_->name === $method) {
            return $node;
        }
    }
    throw new RuntimeException("No press handler for {$method}.");
};
$press = \Pam\Native\Internal\Wire::map(['x' => 1.0, 'y' => 1.0, 'pageX' => 1.0, 'pageY' => 1.0, 'timestamp' => 1, 'pointerId' => 0]);
$frame = Runtime::lastFrame();
$renders = PureEventProbe::$renders;
for ($i = 0; $i < 8; $i++) {
    Runtime::dispatchEvent($pressNode('tick'), EventKind::Press->value, $press);
}
$assert(
    PureEventProbe::$ticks === 8 && PureEventProbe::$renders === $renders && Runtime::lastFrame() === $frame,
    'A #[PureHandler] that changes no state must not render or commit (timer-like events stay cheap).',
);
Runtime::dispatchEvent($pressNode('tickAndChange'), EventKind::Press->value, $press);
$assert(
    PureEventProbe::$renders === $renders + 1 && str_contains((string) Runtime::lastFrame(), 'changed-9'),
    'A #[PureHandler] that really changes its component state must still render it.',
);
$renders = PureEventProbe::$renders;
Runtime::dispatchEvent($pressNode('impure'), EventKind::Press->value, $press);
$assert(
    PureEventProbe::$renders === $renders + 1,
    'Ordinary template handlers keep marking their component dirty.',
);
$pureClosure = #[PureHandler] static function (): void {};
$plainClosure = static function (): void {};
$isPure = new ReflectionMethod(Runtime::class, 'isPureCallback');
$assert(
    $isPure->invoke(null, $pureClosure) === true && $isPure->invoke(null, $plainClosure) === false
        && $isPure->invoke(null, Pressable::make(Text::make('x'))->onPress($pureClosure)->toElement()->events()[EventKind::Press->value]) === true,
    'Element closures carrying #[PureHandler] (directly or behind payload wrappers) must be pure.',
);
Runtime::shutdown();

echo "ROUTE_COVERAGE_OK events=78,79 props=530-536 pure-skip=8 renders\n";

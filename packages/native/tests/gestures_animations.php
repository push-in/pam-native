<?php

declare(strict_types=1);

use Pam\Native\Animation\Animation;
use Pam\Native\Animation\AnimationPreset;
use Pam\Native\Animation\Drag;
use Pam\Native\Animation\Easing;
use Pam\Native\Animation\Spring;
use Pam\Native\Animation\TapEffect;
use Pam\Native\Animation\Transition;
use Pam\Native\EventKind;
use Pam\Native\GestureEvent;
use Pam\Native\GestureSettleEvent;
use Pam\Native\GestureState;
use Pam\Native\GestureType;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateExpression;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\Wire;
use Pam\Native\PressEvent;
use Pam\Native\PropKey;
use Pam\Native\ScrollPhaseEvent;
use Pam\Native\TextEllipsizeMode;
use Pam\Native\TextLayoutEvent;
use Pam\Native\UI\GestureDetector;
use Pam\Native\UI\Pressable;
use Pam\Native\UI\Swipeable;
use Pam\Native\UI\Text;
use Pam\Native\UI\View;

/** @var Closure(bool, string): void $assert */

// --- Animation programs -----------------------------------------------------
$pop = Animation::spring(['scale' => 1.2], stiffness: 400, damping: 8)
    ->then(Animation::spring(['scale' => 1]));
$assert(
    str_starts_with($pop->encode(), 'pam-motion 1 id=')
        && str_ends_with($pop->encode(), "iterations=1\n0 scale spring(1.2,400,8,1,0,0)\n1 scale spring(1,100,10,1,0,0)"),
    'Animation::then() must emit one phase per barrier with Reanimated spring defaults.',
);
$assert(
    $pop->id() === Animation::spring(['scale' => 1.2], stiffness: 400, damping: 8)
        ->then(Animation::spring(['scale' => 1]))->id()
        && $pop->id() !== $pop->key(2)->id(),
    'Identical animations must share an identity so re-renders never replay them; key() must change it.',
);
$perProperty = Animation::timing(['translateX' => 24], 300, Easing::EaseOut)
    ->with(Animation::timing(['opacity' => 0], 120, 'cubic-bezier(0.4, 0, 0.2, 1)', delay: 40));
$assert(
    str_contains($perProperty->encode(), "0 translateX timing(24,300,ease-out,0)\n0 opacity timing(0,120,bezier:0.4:0:0.2:1,40)"),
    'Parallel tracks must keep per-property durations, easings and delays.',
);
$sequence = Animation::sequence(
    Animation::set(['opacity' => 0]),
    Animation::timing(['opacity' => 1], 60, Easing::Linear),
    Animation::wait(360),
    Animation::timing(['opacity' => 0], 260, Easing::EaseInQuad),
)->repeat();
$assert(
    str_contains($sequence->encode(), 'iterations=-1')
        && str_contains($sequence->encode(), '0 opacity set(0) timing(1,60,linear,0) wait(360) timing(0,260,ease-in-quad,0)'),
    'Animation::sequence() must chain per-property steps like withSequence and repeat like withRepeat.',
);
$assert(
    str_contains(Animation::timing(['x' => '-100%'], 200)->delay(150)->encode(), '0 translateX wait(150) timing(-100%,200,ease-in-out,0)'),
    'delay() must prefix the first phase and percentages must survive encoding.',
);
$invalidAnimatedProperty = false;
try {
    Animation::timing(['width' => 10]);
} catch (InvalidArgumentException) {
    $invalidAnimatedProperty = true;
}
$assert($invalidAnimatedProperty, 'Only UI-thread composited properties can be animated.');
$heart = AnimationPreset::heartBurst()->encode();
$assert(
    str_contains($heart, '0 scale set(0.5) spring(1.1,300,7,0.5,0,0) timing(1,120,ease-in-out-quad,0)')
        && str_contains($heart, '0 opacity set(0) timing(1,60,linear,0) wait(360) timing(0,260,ease-in-quad,0)')
        && str_contains($heart, '0 translateY set(0) wait(420) timing(-36,260,ease-in-quad,0)'),
    'heartBurst() must reproduce the ReelPage scale/opacity/float timeline.',
);
$assert(
    str_contains(AnimationPreset::shimmer()->encode(), '0 translateX set(-100%) timing(100%,1200,linear,0)')
        && str_contains(AnimationPreset::marquee(80)->encode(), 'wait(1200) timing(-80,2667,linear,0) wait(1200) timing(0,260,ease-out-quad,0)'),
    'Shimmer and marquee presets must loop natively.',
);
$springDuration = (new Spring())->durationMs(0.0, 1.0);
$assert(
    $springDuration > 600 && $springDuration < 2_500
        && (new Spring(1000, 200, 1))->durationMs(0.0, 1.0) < $springDuration,
    'Spring settling time must follow the shared closed-form oscillator.',
);

// --- CSS transitions ----------------------------------------------------------
$assert(
    Transition::apply('', 'transition', 'transform 300ms spring(1 260 18), opacity 120ms ease-out 40ms')
        === "@property transform,opacity\n@duration 300,120\n@timing spring:260:18:1,ease-out\n@delay 0,40",
    'Element transitions must compile per-property lists with spring().',
);
$transitioned = View::make()->transition('opacity 200ms ease-in');
$assert(
    $transitioned->properties()[PropKey::TransitionSpec->value] === "@property opacity\n@duration 200\n@timing ease-in\n@delay 0"
        && $transitioned->properties()[PropKey::AnimateChanges->value] === true,
    'Element::transition() must enable implicit animation with its spec.',
);

// --- Press, double tap and tap effects ---------------------------------------
$pressPayload = Wire::map(['x' => 12.5, 'y' => 30.0, 'pageX' => 112.5, 'pageY' => 430.0, 'timestamp' => 9, 'pointerId' => 0]);
$received = [];
$pressable = Pressable::make(Text::make('Like'))
    ->onPress(static function (PressEvent $event) use (&$received): void { $received['press'] = $event; })
    ->onLongPress(static function (mixed $payload = null) use (&$received): void { $received['long'] = $payload; })
    ->onDoubleTap(static function (PressEvent $event) use (&$received): void { $received['double'] = $event; })
    ->doubleTapDelay(260)
    ->tapEffect(TapEffect::make('heart', tilt: 30));
$pressable->events()[EventKind::Press->value]($pressPayload);
$pressable->events()[EventKind::LongPress->value]($pressPayload);
$pressable->events()[EventKind::DoubleTap->value]($pressPayload);
$assert(
    $received['press'] instanceof PressEvent && $received['press']->x === 12.5 && $received['press']->pageY === 430.0
        && $received['long'] === ''
        && $received['double'] instanceof PressEvent && $received['double']->y === 30.0,
    'Typed press handlers must receive coordinates; untyped ones keep the legacy empty payload.',
);
$assert(
    PressEvent::fromPayload('')->x === 0.0,
    'Hosts that send empty press payloads must still decode.',
);
$assert(
    $pressable->properties()[PropKey::OnDoubleTap->value] === true
        && $pressable->properties()[PropKey::PressDoubleTapDelayMs->value] === 260
        && str_starts_with($pressable->properties()[PropKey::PressTapEffect->value], "ref=heart;tilt=30\npam-motion 1 "),
    'Double tap, its delay and the native tap effect must be encoded on the Pressable.',
);

// --- Drag and Swipeable ---------------------------------------------------------
$storyDrag = Drag::vertical()
    ->bounds(min: 0)
    ->snapPoints(0, '100%')
    ->threshold(120, velocity: 900)
    ->settle(new Spring(230, 22, 0.72))
    ->settleAt(1, Easing::EaseOut, 190)
    ->drive('', 'scale', [0, '50%'], [1, 0.955])
    ->drive('', 'borderRadius', [0, 120], [0, 18]);
$assert(
    $storyDrag->encode() === implode("\n", [
        'axis=y', 'min=0', 'snaps=0,100%', 'settle=spring:230:22:0.72', 'settle.1=timing:190:ease-out',
        'threshold=120', 'velocity=900', 'drive=|scale|0,50%|1,0.955', 'drive=|borderRadius|0,120|0,18',
    ]),
    'Story drag-to-dismiss must encode bounds, snaps, thresholds, settles and drivers.',
);
$detector = GestureDetector::make(GestureType::Pan, View::make())
    ->drag($storyDrag)
    ->snapTo(0, 3)
    ->onSettle(static function (GestureSettleEvent $event) use (&$received): void { $received['settle'] = $event; });
$detector->events()[EventKind::GestureSettle->value](Wire::map(['snapIndex' => 1, 'position' => 640.0]));
$assert(
    $detector->properties()[PropKey::GestureDragSnapIndex->value] === 3 * 64
        && $received['settle']->snapIndex === 1 && $received['settle']->isOpen(),
    'Drags must expose programmatic snaps and settle events.',
);
$assert(
    Drag::snapRequestValue('2@5') === 5 * 64 + 2 && Drag::snapRequestValue(1) === 1,
    'Template snap requests must accept index@request.',
);
$ended = GestureEvent::fromPayload(Wire::map([
    'type' => GestureType::Pan->value,
    'state' => GestureState::Ended->value,
    'snapIndex' => 1,
    'thresholdReached' => true,
]));
$assert(
    $ended->snapIndex === 1 && $ended->thresholdReached
        && GestureEvent::fromPayload(Wire::map(['type' => 1, 'state' => 3]))->snapIndex === -1,
    'Gesture end events must report the release snap and threshold.',
);
$row = Swipeable::make(Text::make('Conversation'))
    ->rightActions(Text::make('Archive'), 160)
    ->leftActions(Text::make('Pin'), 80)
    ->group('inbox')
    ->closeRequest(4)
    ->toElement();
$rowDrag = $row->properties()[PropKey::GestureDrag->value];
$rowLayers = $row->children()[0]->children();
$assert(
    str_contains($rowDrag, 'snaps=-160,0,80') && str_contains($rowDrag, 'group=inbox')
        && str_contains($rowDrag, 'target=pam-swipeable-content') && str_contains($rowDrag, 'min=-160')
        && $row->properties()[PropKey::GestureDragSnapIndex->value] === 4 * 64 + 1
        && $row->properties()[PropKey::GestureComposition->value] === 3
        && count($rowLayers) === 3
        && $rowLayers[2]->properties()[PropKey::NativeRef->value] === Swipeable::CONTENT_REF,
    'Swipeable must reveal both action panels, close programmatically and keep one row open per group.',
);

// --- Scroll phases and text layout ------------------------------------------------
$scrollPayload = Wire::map(['x' => 720.0, 'y' => 0.0, 'velocityX' => 1800.0, 'velocityY' => 0.0, 'page' => 2]);
$momentum = ScrollPhaseEvent::fromPayload($scrollPayload);
$assert($momentum->page === 2 && $momentum->velocityX === 1800.0, 'Scroll phase events must decode the page index.');
$layout = TextLayoutEvent::fromPayload(Wire::map([
    'lines' => 4, 'visibleLines' => 2, 'truncated' => true, 'width' => 300.0, 'height' => 80.0,
    'lineWidths' => '[290.5,288,300,120.25]',
]));
$assert(
    $layout->exceeds(2) && $layout->truncated && $layout->lineWidths === [290.5, 288.0, 300.0, 120.25],
    'Text layout events must report wrapped lines, truncation and line widths.',
);

// --- Templates -----------------------------------------------------------------------
$templateScope = new class {
    public ?PressEvent $liked = null;
    public ?TextLayoutEvent $layout = null;
    public ?ScrollPhaseEvent $page = null;
    public string $legacy = 'unset';
    public Animation $bounce;

    public function __construct()
    {
        $this->bounce = AnimationPreset::likeBounce()->key(1);
    }

    public function like(PressEvent $event): void
    {
        $this->liked = $event;
    }

    public function open(string $payload = 'none'): void
    {
        $this->legacy = $payload;
    }

    public function measured(TextLayoutEvent $event): void
    {
        $this->layout = $event;
    }

    public function paged(ScrollPhaseEvent $event): void
    {
        $this->page = $event;
    }
};
$template = TemplateRenderer::render(
    TemplateCompiler::compile(
        '<Column>'
        .'<Pressable on:doubleTap="like" on:press="open" doubleTapDelay="240" nativeRef="surface"><Text>Reel</Text></Pressable>'
        .'<View nativeRef="heart" :animation="$bounce"><Text>♥</Text></View>'
        .'<Text numberOfLines="2" on:textLayout="measured" ellipsizeMode="marquee">Caption</Text>'
        .'<Scroll horizontal="true" pagingEnabled="true" on:momentumScrollEnd="paged"><Text>Page</Text></Scroll>'
        .'<Swipeable rightWidth="96" group="inbox"><Text>Delete</Text><Text>Row</Text></Swipeable>'
        .'</Column>',
        'gestures-animations.pam.php',
    ),
    $templateScope,
    [],
);
[$surface, $heartView, $caption, $pager, $swipeRow] = $template->children();
$surface->events()[EventKind::DoubleTap->value]($pressPayload);
$surface->events()[EventKind::Press->value]($pressPayload);
$caption->events()[EventKind::TextLayout->value](Wire::map(['lines' => 3, 'visibleLines' => 2, 'truncated' => true]));
$pager->events()[EventKind::MomentumScrollEnd->value]($scrollPayload);
$assert(
    $templateScope->liked instanceof PressEvent && $templateScope->liked->x === 12.5
        && $templateScope->legacy === ''
        && $surface->properties()[PropKey::PressDoubleTapDelayMs->value] === 240
        && $surface->properties()[PropKey::NativeRef->value] === 'surface',
    'Template double taps must hydrate PressEvent while untyped press handlers keep their payload.',
);
$assert(
    $heartView->properties()[PropKey::AnimationProgram->value] === $templateScope->bounce->encode()
        && $heartView->properties()[PropKey::NativeRef->value] === 'heart',
    'Template :animation must attach the native animation program.',
);
$assert(
    $templateScope->layout instanceof TextLayoutEvent && $templateScope->layout->exceeds(2)
        && $caption->properties()[PropKey::TextEllipsizeMode->value] === TextEllipsizeMode::Marquee->value,
    'Template on:textLayout must hydrate TextLayoutEvent and ellipsizeMode must accept marquee.',
);
$assert(
    $templateScope->page instanceof ScrollPhaseEvent && $templateScope->page->page === 2
        && $pager->properties()[PropKey::ScrollPagingEnabled->value] === true,
    'Template on:momentumScrollEnd must hydrate the page index.',
);
$assert(
    str_contains($swipeRow->properties()[PropKey::GestureDrag->value], 'snaps=-96,0'),
    'The Swipeable template tag must compile its action panels.',
);
TemplateExpression::evaluate('like($event)', $templateScope, ['event' => Wire::map(['x' => 3.0])]);
$assert(
    $templateScope->liked->x === 3.0,
    'Explicit $event arguments must honor typed PressEvent parameters.',
);

$transitionCss = \Pam\Native\Internal\ScopedStyleCompiler::compile(
    '.bubble { transition: transform 280ms spring(1 260 18), opacity 120ms ease-out 40ms; }',
    'TransitionCss.pam.php',
);
$transitionTemplate = TemplateCompiler::compile('<Column><View class="bubble" /></Column>');
$transitionStyled = new \Pam\Native\Internal\CompiledTemplateNode(
    kind: $transitionTemplate->kind,
    name: $transitionTemplate->name,
    attributes: [
        ...$transitionTemplate->attributes,
        '__pamStyles' => json_encode($transitionCss, JSON_THROW_ON_ERROR),
    ],
    source: $transitionTemplate->source,
    line: $transitionTemplate->line,
    column: $transitionTemplate->column,
    value: $transitionTemplate->value,
);
$transitionStyled->children = $transitionTemplate->children;
$bubble = TemplateRenderer::render($transitionStyled, null, [])->children()[0];
$assert(
    ($bubble->properties()[PropKey::TransitionSpec->value] ?? null)
        === "@property transform,opacity\n@duration 280,120\n@timing spring:260:18:1,ease-out\n@delay 0,40"
        && $bubble->properties()[PropKey::AnimateChanges->value] === true,
    'Scoped CSS transitions must reach the native TransitionSpec property.',
);

$easedKeyframe = new \Pam\Native\AnimationKeyframe(0.5, scaleX: 1.1, easing: Easing::EaseOutQuad->value);
$assert(
    str_contains(json_encode($easedKeyframe, JSON_THROW_ON_ERROR), '"easing":"ease-out-quad"'),
    'Keyframes must carry their per-segment easing to the native runtime.',
);
$invalidKeyframeEasing = false;
try {
    new \Pam\Native\AnimationKeyframe(0.5, easing: 'cubic-bezier(0.4, 0, 0.2, 1)');
} catch (InvalidArgumentException) {
    $invalidKeyframeEasing = true;
}
$assert($invalidKeyframeEasing, 'Keyframe easings must use native tokens (Easing::bezier() for curves).');

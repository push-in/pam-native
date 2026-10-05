# Animations

PAM Native animates on the platform UI thread. PHP describes the motion once;
frames never round-trip through PHP. Three layers are available:

1. **CSS transitions** for implicit property changes.
2. **`Animation` programs** (Reanimated `withTiming`/`withSpring`/
   `withSequence`/`withDelay`/`withRepeat`) attached to any element.
3. **Gesture-driven motion** (`Drag`, `TapEffect`) described in
   [Gestures](gestures.md).

Animatable properties are composited only: `opacity`, `translateX`,
`translateY` (dp or `%` of the element's own size), `scale`, `scaleX`,
`scaleY`, `rotate` (degrees) and `borderRadius` (clips to a rounded outline).

## CSS transitions

Per-property durations, delays and timing functions are honoured, and
`spring(mass stiffness damping)` is available as a timing function (WebKit
order; all arguments optional, defaults `1 100 10`):

```css
.bubble {
    transition: transform 280ms spring(1 260 18), opacity 120ms ease-out 40ms;
}
```

Supported properties: `all`, `opacity`, `transform`, `translate`, `scale`,
`rotate`, `border-radius`. Longhands (`transition-property`, `-duration`,
`-timing-function`, `-delay`) override the shorthand lists exactly like CSS.
Timing functions: `linear`, `ease`, `ease-in`, `ease-out`, `ease-in-out`,
`cubic-bezier()`, `steps()` (linear), `spring()`. Fluent equivalent:
`$element->transition('opacity 200ms ease-in')`.

## Animation programs

```php
use Pam\Native\Animation\Animation;
use Pam\Native\Animation\Easing;

// Like-button bounce, replayed whenever the like count changes.
$this->bounce = Animation::timing(['scale' => 0.82], 70, Easing::EaseOutQuad)
    ->then(Animation::spring(['scale' => 1], stiffness: 420, damping: 9, mass: 0.6))
    ->key($this->likes);
```

```xml
<View :animation="$bounce"><HeartIcon /></View>
<Animated :animation="$bounce"><HeartIcon /></Animated>
```

| Builder | Reanimated |
| --- | --- |
| `Animation::timing(['opacity' => 1], 200, Easing::EaseOut, delay: 40)` | `withDelay(40, withTiming(1, {duration: 200, easing}))` |
| `Animation::spring(['scale' => 1.1], stiffness: 300, damping: 7, mass: 0.5)` | `withSpring(1.1, {...})` |
| `Animation::set(['scale' => 0.5])` | `withTiming(0.5, {duration: 0})` |
| `Animation::sequence($a, $b, Animation::wait(360), $c)` | `withSequence(...)` per property |
| `$a->with($b)` | animations on several shared values at once |
| `$a->then($b)` | start `$b` after every track of `$a` settled |
| `->delay($ms)`, `->repeat(-1)` | `withDelay`, `withRepeat(-1)` |

Different properties can run different durations in parallel:

```php
Animation::timing(['translateX' => 0], 300, Easing::EaseOut)
    ->with(Animation::timing(['opacity' => 1], 120));
```

A program replays only when its identity changes: the encoded program plus
`key()`. Re-rendering an unchanged animation never restarts it; change the key
(a counter, an id) to replay. `replayKey="$n"` (`Element::replayKey()`)
replays the attached program or keyframes without changing them.
`on:animationComplete` fires once a finite program ends.

Reduced-motion settings jump to the final values.

## Presets

`Pam\Native\Animation\AnimationPreset` reproduces the Zé Chat motions:

| Preset | Use |
| --- | --- |
| `heartBurst()` | Reels double-tap heart: spring pop (0.5 → 1.1 → 1), hold, float up and fade. Pair with `TapEffect`. |
| `likeBounce()` | Like button squash and overshoot. |
| `backToTop(bool $visible)` | Floating back-to-top button entrance/exit (spring slide + fade). |
| `shimmer(int $ms = 1200)` | Skeleton highlight sweeping its own width forever. |
| `pulse()` | Opacity pulse (recording indicator, placeholders). |
| `marquee(float $distance, float $speed = 30)` | Ticker for an overflowing track; for single-line text prefer `ellipsizeMode="marquee"`. |
| `fadeInUp()` | Live chat row entrance. |

## Keyframes

`@keyframes` + `<Animated animation="name">` keep working. In PHP,
`new AnimationKeyframe(0.4, scaleX: 1.1, easing: 'ease-out-quad')` sets the
curve used from that keyframe to the next, so one timeline can mix curves.
`replayKey` restarts keyframes.

## Modal presentation

`<Modal animationType="slide-fade">` fades the backdrop while the content
slides fully from below on its own curve, and reverses both on dismiss. The
remaining types (`none`, `slide`, `fade`) are unchanged. For an interactive
sheet, wrap the sheet in a vertical `Drag` with `snapPoints(0, '100%')`, give
a transparent modal its own backdrop view with `nativeRef="backdrop"`, and
drive it with `->drive('backdrop', 'opacity', [0, '100%'], [1, 0])`.

## Platform status

Android and iOS (1.8.0) implement all of the above on the UI thread. iOS
drives programs, transitions and drag settles from a `CADisplayLink` with the
same spring solver and easing curves as Android, maps per-keyframe `easing` to
Core Animation timing functions and runs `slide-fade` modals with
`UIViewPropertyAnimator`. The iOS implementation has not been validated on a
device yet (no Xcode in the release pipeline).

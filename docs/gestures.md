# Gestures

`GestureDetector` recognizes tap, pan, pinch, rotation, swipe and long-press
with platform recognizers. Movement remains on the UI thread. PHP receives
semantic begin, update, end and cancellation events; update delivery is
coalesced to the display cadence.

```php
use Pam\Native\GestureDirection;
use Pam\Native\GestureEvent;
use Pam\Native\GestureType;
use Pam\Native\UI\GestureDetector;

$drag = GestureDetector::make(
    GestureType::Pan,
    $card,
)
    ->direction(GestureDirection::Horizontal)
    ->minimumDistance(12)
    ->onUpdate(function (GestureEvent $event): void {
        $this->dragX = $event->translationX;
    })
    ->onEnd(function (GestureEvent $event): void {
        $this->commitDrag($event->translationX, $event->velocityX);
    });
```

Templates use the same native contract:

```xml
<GestureDetector
    gestureType="pan"
    gestureDirection="horizontal"
    gestureMinDistance="12"
    on:gestureUpdate="drag"
    on:gestureEnd="drop"
>
    <Card />
</GestureDetector>
```

Handlers that also need item context may pass `$event` explicitly. PAM decodes
the wire payload according to the method's `GestureEvent` type instead of
exposing the encoded transport string:

```xml
<GestureDetector on:gestureEnd="moveLayer($layer['id'], $event)">
    <Card />
</GestureDetector>
```

```php
public function moveLayer(string $layerId, GestureEvent $event): void
{
    $this->commitLayer($layerId, $event->translationX, $event->translationY);
}
```

## Native transforms

For direct-manipulation surfaces such as image galleries, enable
`gestureNativeTransform="true"`. Pan, pinch and rotation are then applied to
the detector's single child on the native UI thread at the display refresh
rate. `on:gestureBegin`, `on:gestureEnd` and `on:gestureCancel` remain
available for semantic state updates; omit `on:gestureUpdate` to keep PHP out
of the frame loop.

```pam
<GestureDetector
    gestureType="pinch"
    gestureNativeTransform="true"
    gestureNativeMinScale="1"
    gestureNativeMaxScale="4"
    :gestureNativeResetKey="$mediaRevision"
    on:gestureEnd="commitZoom"
>
    <Image :source="$url" resizeMode="contain" />
</GestureDetector>
```

On Android, pinch and rotation detectors claim the active multi-pointer touch
stream as soon as the second pointer lands. This prevents an ancestor
`ScrollView` or pager from stealing the gesture while still allowing ordinary
single-pointer paging before zoom starts.

Increment `gestureNativeResetKey` when the displayed item changes or when the
transform should return to its identity value.

Gesture types, states, directions and composition modes are integer-backed,
sequential enums. Pointer counts are bounded to `1...10`, distances are in
logical points/dp and durations are milliseconds.

Composition defaults to `exclusive`. `simultaneous` permits recognition
alongside scroll and child recognizers. `race` lets the first recognizer that
begins own the interaction. Critical actions must still expose a visible,
non-gesture alternative for accessibility.

Once movement recognizes a gesture, competing `Pressable` press and long-press
semantics on that same node are cancelled. This prevents a pan or swipe from
also triggering a tap or contextual menu when the pointer is released.

## Press, long press and double tap

`Pressable` mirrors React Native: `on:pressIn`, `on:pressOut`, `on:press`,
`on:longPress` (after `delayLongPress`) and `on:pressMove`. Handlers typed
`PressEvent $event` receive the touch location: `x`/`y` (RN
`locationX`/`locationY`, relative to the pressable) and `pageX`/`pageY`
(screen). Untyped `on:press`/`on:longPress` handlers keep the historical empty
payload.

Hold-to-record (camera shutter) needs no extra API: start on `on:longPress`,
stop on `on:pressOut`.

```xml
<Pressable
    delayLongPress="260"
    on:pressIn="armCapture"
    on:longPress="startRecording"
    on:press="takePhoto"
    on:pressOut="stopRecordingIfHeld"
>
```

`on:doubleTap` recognizes a second tap within `doubleTapDelay` (default
250 ms) near the first one. When it is bound, the single `on:press` is
deferred by that delay and cancelled by a double tap (the ReelPage pattern),
entirely on the UI thread. `tapEffect` plays a native animation centred on the
second tap before PHP is even notified:

```php
use Pam\Native\Animation\AnimationPreset;
use Pam\Native\Animation\TapEffect;

public TapEffect $heart; // TapEffect::make('heart', AnimationPreset::heartBurst(), tilt: 15)

public function like(PressEvent $event): void { /* $event->x, $event->y */ }
```

```xml
<Pressable on:press="togglePause" on:doubleTap="like" :tapEffect="$heart">
    <Video ... />
    <View nativeRef="heart" style="position:absolute;width:96px;height:96px">
        <Icon name="heart" />
    </View>
</Pressable>
```

The `nativeRef` anchor is translated so its centre sits on the tap (and
rotated by a random angle up to `tilt`); the animation runs on its first child.

## Native drags (pan + spring/decay release)

`Drag` turns a pan detector into a UI-thread drag: the target follows the
finger on one axis inside optional bounds (with rubber banding), driver
properties are interpolated from the translation every frame, and on release
the target settles onto a snap point with a spring or a timing curve. PHP
receives only `on:gestureBegin`, `on:gestureEnd` (with `snapIndex` and
`thresholdReached`) and `on:gestureSettle` (after the settle animation).

Release rule: crossing `threshold` dp from the starting snap, or flinging
faster than `velocity` dp/s in the drag direction, moves to the adjacent snap
in that direction; otherwise the drag springs back.

Story drag-to-dismiss (RN `StoriesScreen`):

```php
$this->dismiss = Drag::vertical()
    ->bounds(min: 0)
    ->snapPoints(0, '100%')
    ->threshold(120, velocity: 900)
    ->settle(new Spring(stiffness: 230, damping: 22, mass: 0.72))
    ->settleAt(1, Easing::EaseOut, 190)
    ->drive('', 'scale', [0, '50%'], [1, 0.955])
    ->drive('', 'borderRadius', [0, 120], [0, 18]);
```

```xml
<GestureDetector gestureType="pan" gestureDirection="vertical" gestureMinDistance="12"
    :drag="$dismiss" on:gestureBegin="pause" on:gestureSettle="settled">
    <StoryPage />
</GestureDetector>
```

```php
public function settled(GestureSettleEvent $event): void
{
    $event->snapIndex === 1 ? $this->close() : $this->resume();
}
```

Swipe-to-reply (RN `MessageRow`): a single snap at 0 always springs back, the
end event reports whether the threshold was crossed, and `haptic()` ticks when
it is crossed:

```php
Drag::horizontal()->bounds(min: 0, max: 72)->target('bubble')
    ->snapPoints(0)->threshold(46)->haptic()
    ->settle(new Spring(260, 18))
    ->drive('replyIcon', 'opacity', [0, 46], [0, 1])
    ->drive('replyIcon', 'scale', [0, 46], [0.86, 1]);
```

```php
public function swiped(GestureEvent $event): void
{
    if ($event->thresholdReached) $this->reply($this->messageId);
}
```

Drivers accept `opacity`, `translateX`, `translateY`, `scale`, `scaleX`,
`scaleY`, `rotate` and `borderRadius`; inputs are dp of drag translation or a
percentage of the target extent. Programmatic snaps use `dragSnap="index@request"`
(or `GestureDetector::snapTo($index, $request)`); bump `request` to issue the
same snap again. Drags in the same `group()` keep only one member away from
zero.

Pinch/pan/rotation image zoom keeps using `gestureNativeTransform`.

## Swipeable rows

`Swipeable` is the react-native-gesture-handler `Swipeable`: horizontal drag
reveals left and/or right action panels behind the row, the row settles open
or closed with a spring, one row per `group` stays open, and `closeRequest`
closes it programmatically.

```xml
<Swipeable rightWidth="160" leftWidth="80" group="inbox" :closeRequest="$closeRows"
    on:gestureSettle="rowSettled">
    <PinAction />
    <Row class="actions"><ArchiveAction /><DeleteAction /></Row>
    <ConversationRow :conversation="$conversation" />
</Swipeable>
```

Children are `[left panel]`, `[right panel]`, `content`, in that order (panels
only when their width is set). The content must paint an opaque background.
`GestureSettleEvent::isOpen()` and `position` (>0 left open, <0 right open)
describe the resting state. In PHP: `Swipeable::make($row)->rightActions(...)
->leftActions(...)->group('inbox')->onOpen(fn (string $side) => ...)`.

## Scroll lifecycle and paging

`ScrollView`/lists support RN `pagingEnabled`, `snapToInterval` and
`decelerationRate` natively (lists page one item at a time). The lifecycle is
reported without per-frame traffic:

| Event | Payload (`ScrollPhaseEvent`) |
| --- | --- |
| `on:scrollBeginDrag` | offset |
| `on:scrollEndDrag` | offset, release `velocityX`/`velocityY` (dp/s) |
| `on:momentumScrollEnd` | offset and `page` once fling/paging settled |

```xml
<Scroll horizontal="true" pagingEnabled="true" decelerationRate="0.99"
    on:momentumScrollEnd="authorChanged">
```

```php
public function authorChanged(ScrollPhaseEvent $event): void
{
    $this->activeAuthor = $event->page;
}
```

## Text layout

`on:textLayout` reports the wrapped line count at the rendered width (even
when `numberOfLines` truncates), the visible line count, whether the text is
truncated and each line's width — the RN "… mais" check without a hidden
measuring copy:

```php
public function captionMeasured(TextLayoutEvent $event): void
{
    $this->truncatable = $event->exceeds(2);
}
```

`ellipsizeMode="marquee"` turns a single-line text into a native ticker (audio
names in Reels).

## Platform status

Android and iOS (1.8.0) implement every primitive on this page. On iOS,
presses carry coordinates from the touch that ended the press (VoiceOver
activations report the centre), `delayLongPress`/`unstable_pressDelay`/
`delayPressOut` drive the recognizers, axis-locked pans (drags, horizontal or
vertical `GestureDetector`s) only begin on their axis so rows keep scrolling
their list, and `ellipsizeMode="marquee"` scrolls single-line labels.
`pressRetentionOffset` keeps UIKit's fixed touch-tracking slop on iOS. The iOS
implementation has not been validated on a device yet.

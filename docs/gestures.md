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

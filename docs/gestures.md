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

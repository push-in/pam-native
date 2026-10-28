# Keyboard visibility and height

```php
use Pam\Native\System\Keyboard;

$visible = Keyboard::isVisible();
$subscription = Keyboard::onVisibilityChange(function (bool $visible): void {
    // Update layout only when visibility changes, not for each IME frame.
});
Keyboard::offVisibilityChange($subscription);
Keyboard::dismiss();
```

The native host tracks keyboard visibility separately from input focus and
keyboard avoidance. Hiding the keyboard with Android Back leaves focus intact.
Interactive bottom sheets still report a visible keyboard while they move
above it. Use KeyboardAvoidingView or keyboard-aware scroll behavior for
continuous geometry; this API only reports the binary visible/hidden state.

## Keyboard height (`Keyboard::height()`, `onChange()`)

React Native `Keyboard.metrics()` and the `keyboardWillShow` /
`keyboardWillChangeFrame` / `keyboardWillHide` events:

```php
use Pam\Native\KeyboardMetrics;
use Pam\Native\System\Keyboard;

$height = Keyboard::height();          // settled height in points, 0 while hidden
$last = Keyboard::lastHeight();        // last keyboard shown (0 before the first one)
$metrics = Keyboard::metrics();        // visible, height, durationMs
$subscription = Keyboard::onChange(function (KeyboardMetrics $keyboard): void {
    $this->panelHeight = $keyboard->visible ? $keyboard->height : $this->panelHeight;
});
Keyboard::offChange($subscription);
```

- The height is the part of the window the keyboard covers, measured from the
  window bottom (it includes the navigation bar / home indicator area under
  the keyboard), like React Native `endCoordinates.height`.
- One notification per settled change: show, hide and a new height
  (suggestion strip, emoji keyboard, rotation). Animation frames never reach
  PHP. iOS publishes the `keyboardWillChangeFrame` end frame and its
  duration; Android publishes the end-state IME insets of the root window or
  of the Modal/BottomSheet window that has the keyboard, and coalesces
  height-only changes for 80 ms. `durationMs` is 0 when the host does not
  know the animation length (Android).
- A panel that replaces the keyboard (emoji, GIF or attachment picker) opens
  at `Keyboard::lastHeight()` so the composer stays where it was.
- `onVisibilityChange()` keeps its binary contract: a height change alone
  does not call it.
- Use KeyboardAvoidingView or `ScrollView keyboardInset` for frame-accurate
  motion; this API is for sizing and decisions.

Read `isVisible()` when activating a component, then subscribe. Unsubscribe
when its route loses focus, the app backgrounds or the component unmounts.
Runtime shutdown clears all listeners. Resume and hot reload reconcile the
host state. A component-bound listener invalidates that component and its
ancestors; callbacks without a component owner invalidate the tree.

Android 26–29 shares a window-scoped legacy IME measurement with keyboard
avoidance; plain inputs and dialogs are observed even without a
KeyboardAvoidingView. Observation closes while inactive. Android 30+ reads
native IME insets. Window focus transfers discard stale root/dialog surfaces.
The active lifecycle event reaches PHP before the resumed visibility snapshot.

iOS confines notifications, lifecycle reconciliation and initial publication to
the main thread. Resume reads the window keyboard layout guide, including
floating keyboards, and excludes the hidden guide at the home-indicator inset.
The guide is available on the SDK's iOS 15 minimum deployment target.

## Keyboard dock

A panel with a field (a text, caption or sticker form at the bottom of a
full-screen editor) docks on the keyboard with a padding
`KeyboardAvoidingView`:

```html
<KeyboardAvoidingView behavior="padding" keyboardVerticalOffset="8" flexGrow="1">
    <View flexGrow="1" />            <!-- shrinks while the keyboard is open -->
    <Column class="panel">…</Column> <!-- rests on the safe area, then 8 dp above the keyboard -->
</KeyboardAvoidingView>
```

The overlap is computed from the view's own laid-out frame, so its resting
bottom never needs measuring: inside a `SafeAreaView` the panel rests above
the navigation bar or home indicator, and a dock whose frame ends below the
window (inside scrolled content) still lands `keyboardVerticalOffset` above
the keyboard. The layout follows every keyboard frame change and every
relayout of the view. Inside a modal the engine applies it on Android and iOS.

# Keyboard visibility

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

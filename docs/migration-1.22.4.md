# Migration to 1.22.4

This Android patch preserves the existing PHP API, numeric protocol identifiers
and dependency requirements. Rebuild the Android host after updating the SDK.

`ScrollView keyboardInset="true"` now follows native keyboard animations and
focus changes without reducing the authored viewport height. Its existing
`keyboardVerticalOffset` property adds clearance above the keyboard, in addition
to the default 24 dp focused-field margin:

```html
<ScrollView keyboardInset="true" keyboardVerticalOffset="156">
    <!-- Form content and its authored bottom spacing. -->
</ScrollView>
```

Use one keyboard-aware container for the form. A padding/resizing
`KeyboardAvoidingView` around this scroll would apply keyboard avoidance twice.
The content must retain enough bottom extent for the requested scroll clearance.
Keyboard dismissal and unchanged insets preserve the user's manual scroll
position. Removing `keyboardInset` releases the inset and animation observers.

On Android 26–29, the Activity can keep `adjustNothing`: a zero-width,
non-focusable native measurement window observes the real keyboard without
resizing the application. Attached Scroll and `KeyboardAvoidingView` consumers
share that observer within their root window. Disabling, detaching or removing
the consumers releases their subscriptions; the last one closes the observer.
Dialogs retain their own window-inset handling. No application workaround or
manifest change is required.

iOS behavior is unchanged.

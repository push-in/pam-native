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

iOS behavior is unchanged.

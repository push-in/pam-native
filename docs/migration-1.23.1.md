# Migrating to Pam Native 1.23.1

## iOS gesture recognizer subclass import

Rebuild the iOS host after updating. 1.23.1 only adds
`import UIKit.UIGestureRecognizerSubclass` to `PamHeldPanGestureRecognizer`,
so its `touchesBegan(_:with:)`, `touchesMoved(_:with:)` and `reset()`
overrides resolve against UIKit and the iOS renderer compiles. Android, the
PHP SDK, the CLI and the protocol are unchanged; no application change is
needed.

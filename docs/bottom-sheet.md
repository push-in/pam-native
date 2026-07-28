# Bottom Sheet

`BottomSheet` is a native modal surface with deterministic snap points on
Android and iOS. Snap points are fractions of the available screen height and
are encoded in the binary protocol without strings or platform-specific units.

```php
use Pam\Native\BottomSheetEvent;
use Pam\Native\BottomSheetKeyboardBehavior;
use Pam\Native\UI\BottomSheet;
use Pam\Native\UI\Text;

$sheet = BottomSheet::make(
    Text::make('Filters'),
    snapPoints: [0.35, 0.7, 1.0],
    index: 1,
)
    ->dismissible()
    ->backdropDismiss()
    ->handleVisible()
    ->dragEnabled()
    ->cornerRadius(24)
    ->keyboardBehavior(BottomSheetKeyboardBehavior::Interactive)
    ->onChange(static function (BottomSheetEvent $event): void {
        // Persist $event->index when the user changes detents.
    })
    ->onDismiss(static function (): void {
        // Synchronize application state after a swipe dismissal.
    });
```

Declarative templates can use the same native component:

```html
<BottomSheet
    :snapPoints="[0.35, 0.7, 1.0]"
    index="1"
    keyboardBehavior="interactive"
    on:sheetChange="onSheetChange"
    on:sheetDismiss="onSheetDismiss"
>
    <Text>Filters</Text>
</BottomSheet>
```

Keyboard behaviors are `Interactive` (1), `Extend` (2), and `FillParent` (3).
Use `BottomSheetKeyboardBehavior` in application code rather than magic
numbers.

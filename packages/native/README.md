# pam/native

Pam Native renders real Android views from persistent PHP. PHP owns application
state and events, Rust performs retained layout and incremental diffing, and
Kotlin mounts bounded mutation batches on the Android UI thread.

```bash
pam init hello-native --template mobile
cd hello-native
pam composer install
pam mobile dev .
```

A screen can be a compact `.pam` template backed by a normal PHP class:

```php
final class Home extends \Pam\Native\Component
{
    private int $count = 0;

    public function render(): \Pam\Native\View
    {
        return \Pam\Native\View::make('screens.home');
    }

    public function increment(): void
    {
        $this->count++;
    }
}
```

```xml
<Screen>
    <SafeAreaView class="flex-1 surface">
        <Column class="flex-1 p-6 gap-4">
            <Text class="text-primary" fontSize="28">Hello, PHP</Text>
            <Button class="accent" height="52" on:press="increment">
                Count: {{ $count }}
            </Button>
        </Column>
    </SafeAreaView>
</Screen>
```

Use `pam mobile make:screen`, `make:component`, and `make:native-view` for
non-destructive scaffolding. Templates support props, slots, events, model
binding, conditional blocks, loops, utility classes and user-defined theme
tokens. The fluent PHP element API and custom Kotlin views remain available as
escape hatches, so the template/class convention is optional.

Safe-area and keyboard avoidance remain native:

```xml
<SafeAreaView edges="top,bottom" mode="margin">
    <KeyboardAvoidingView
        behavior="padding"
        keyboardVerticalOffset="24"
        enabled="true"
    >
        <Input placeholder="Message" />
    </KeyboardAvoidingView>
</SafeAreaView>
```

Android applies system-bar and IME insets directly on the UI thread. The same
options are available through `SafeAreaView::edges()->mode()` and
`KeyboardAvoidingView::verticalOffset()->avoidingEnabled()`.

Pull-to-refresh is configured with `RefreshControl::colors()`,
`progressBackgroundColor()`, `progressViewOffset()`, `enabled()` and `size()`.
The Android gesture and indicator run locally; only `onRefresh` crosses into
PHP.

`Text` exposes `selectable()`, `selectionColor()`, `ellipsize()`,
`allowFontScaling()`, `maxFontSizeMultiplier()`, `adjustsFontSizeToFit()`,
`breakStrategy()`, `hyphenation()` and `dataDetector()`. Android performs
selection, fitting, line breaking and link detection inside `TextView`.

`StatusBar::animated()` and `StatusBar::translucent()` complement color, icon
appearance and visibility. Multiple mounted bars merge in order and restore
the previous native window state when removed. Android 15+ follows mandatory
edge-to-edge semantics.

Run `pam mobile benchmark .` on a physical device for release-like AndroidX
Macrobenchmarks, and `pam mobile profile .` to generate the Baseline Profile
independently. Protocol v1 compatibility and limits are documented in
`PROTOCOL.md`.

## License

Source-available under the [Business Source License 1.1](LICENSE). Android
applications built with PAM may be commercial or proprietary; offering PAM as a
competing framework, runtime, developer platform, or UI engine requires a
commercial license.

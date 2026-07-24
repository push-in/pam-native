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

Images use one cancelable loader for `Image` and `ImageBackground`. Remote
originals are coalesced and cached on disk, decoded bitmaps are cached in RAM
by measured-size bucket, and Android downsamples before allocating pixels:

```php
Image::make($url)
    ->defaultSource('asset://avatar-placeholder.png')
    ->fit(ImageFit::Cover)
    ->resizeMethod(ImageResizeMethod::Auto)
    ->resizeMultiplier(2)
    ->cache(ImageCachePolicy::ForceCache)
    ->fadeDuration(180)
    ->onProgress($updateProgress)
    ->onLoad($rememberNaturalSize)
    ->onError($showFallback);
```

HTTPS, debug HTTP, `asset:`, `file:`, `content:`, `android.resource:` and
bounded image `data:` URIs are supported. Redirects cannot downgrade HTTPS;
responses, headers, redirects, input bytes and decoded pixels are bounded.
`srcSet`, request headers, loading indicators, repeat mode and typed
load-start/progress/load/error/load-end callbacks share the same path.
Callbacks are opt-in, and download progress is coalesced to one event per
display frame before crossing into PHP.

`StatusBar::animated()` and `StatusBar::translucent()` complement color, icon
appearance and visibility. Multiple mounted bars merge in order and restore
the previous native window state when removed. Android 15+ follows mandatory
edge-to-edge semantics.

Both scroll directions use the same core host:

```php
Scroll::make($content)
    ->horizontal()
    ->contentOffset(x: 120)
    ->pagingEnabled()
    ->snapToInterval(320)
    ->nestedScrollEnabled()
    ->overScrollMode(ScrollOverScrollMode::Never)
    ->keyboardDismissMode(ScrollKeyboardDismissMode::OnDrag)
    ->onScroll($rememberOffset);
```

Android owns drag, fling, snapping, fading edges, scrollbars and IME dismissal.
When `onScroll` is present PAM sends only the active-axis offset, coalesced once
per display frame. `ActivityIndicator` exposes `animating()`,
`hidesWhenStopped()`, `size()` and `color()`; `Toggle` exposes native off/on
track and thumb colors.

Virtualized lists are real AndroidX `RecyclerView` hosts:

```php
FlatList::make($packages)
    ->rowHeight(52)
    ->prefetch(8)
    ->columns(2)
    ->initialScrollIndex(20)
    ->showsIndicator(false)
    ->onEndReached($loadMore);

SectionList::make($groups)
    ->rowHeight(48)
    ->inverted()
    ->onScroll($rememberOffset);
```

`horizontal()`, `columns()`, `inverted()`, `initialScrollIndex()`,
`removeClippedSubviews()`, `scrollEnabled()` and `showsIndicator()` map directly
to the native host. Packed scalar and section payloads remain outside PHP while
scrolling; Android binds only visible/prefetched rows and limits `onScroll` to
one event per VSYNC. Rich heterogeneous rows can use keyed PAM composition or a
specialized native plugin.

Run `pam mobile benchmark .` on a physical device for release-like AndroidX
Macrobenchmarks, and `pam mobile profile .` to generate the Baseline Profile
independently. Protocol v1 compatibility and limits are documented in
`PROTOCOL.md`.

## License

Source-available under the [Business Source License 1.1](LICENSE). Android
applications built with PAM may be commercial or proprietary; offering PAM as a
competing framework, runtime, developer platform, or UI engine requires a
commercial license.

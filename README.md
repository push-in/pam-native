# Pam Native

Pam Native keeps PHP 8.4 alive inside the Android process and renders real
Android Views. Rust owns reconciliation and layout; Kotlin only applies the
resulting native mutations.

## One render tree, several authoring styles

Every public style resolves to the same `Renderable -> Element -> PNT1/PNP1`
path. Choosing tags does not add a second renderer or a web view.

Typed PHP:

```php
App::run(fn () => Screen::make(
    Column::make(
        Text::make('Checkout')->style(new Style(fontSize: 28)),
        Button::make('Pay')->onPress($pay),
    )->style(new Style(flexGrow: 1, padding: 24, gap: 16)),
));
```

Class component with tags and utility classes:

```php
final class Checkout extends Component
{
    private string $email = '';
    private bool $loading = false;

    public function render(): View
    {
        return View::make('screens.checkout');
    }

    public function pay(): void
    {
        // Business logic remains ordinary PHP.
    }
}

App::views(__DIR__.'/resources/native', __DIR__.'/.pam-native/views');
App::theme(Theme::pamLab());
App::run(new Checkout());
```

```xml
<Screen>
    <SafeAreaView class="flex-1 bg-white">
        <Column class="flex-1 p-6 gap-4">
            <Text height="56" fontSize="28">Checkout</Text>
            <Input
                model="email"
                keyboardType="email"
                sync="debounced"
                placeholder="E-mail"
            />
            <Button loading="$loading" on:press="pay">Pay</Button>
        </Column>
    </SafeAreaView>
</Screen>
```

Templates are parsed once, validated, kept in memory, and optionally stored as
PHP array cache files. Expressions only read component/data paths; templates do
not use `eval`. `If` and `Each` provide conditional and repeated content.
`TemplateRegistry::component()` adds reusable PHP components and
`TemplateRegistry::style()` adds project-specific classes.

`FunctionalComponent::make()` supports standalone functions. A project can mix
functional trees, class components, tags, custom template factories, and direct
`Element` construction in the same screen.

Non-destructive generators cover the common Laravel-style workflow:

```bash
pam mobile make:screen Orders .
pam mobile make:component MetricCard .
pam mobile make:native-view CameraPreview .
pam mobile profile .
```

The Composer package ships a JSON schema for `pam-native.json` and HTML custom
data for `.pam` tag/attribute completion. New mobile templates wire both into
the project automatically.

## Core component surface

The Android renderer includes the React Native core families without copying
React's JavaScript runtime:

| Family | Pam Native |
| --- | --- |
| Layout | `Screen`, `View`, `Column`, `Row`, `SafeAreaView`, `Spacer` |
| Content | `Text`, `Image`, `ImageBackground` |
| Input | `Input`/`TextInput`, `Button`, `Pressable`, `Toggle`/`Switch` |
| Scrolling | `ScrollView`, `FlatList`, `VirtualizedList`, `SectionList`, `RefreshControl` |
| Presentation | `Modal`, `ActivityIndicator`, `StatusBar`, `KeyboardAvoidingView` |
| Android | `DrawerLayoutAndroid`, `TouchableNativeFeedback`, `InputAccessoryView` |
| Compatibility | `TouchableOpacity`, `TouchableHighlight`, `TouchableWithoutFeedback` |

`Modal` content is hosted by an Android `Dialog`, with dialog, full-screen and
sheet presentations. A native-event handler receives a bounded dismissal map
when the user presses Back; controlled modals remain mounted until PHP confirms
the state change, and focus returns to the previously focused view after close.
Scalar `hitSlop` expands touch and TalkBack delegate regions without changing
visual layout; sibling delegates share one parent dispatcher and are removed
when their view moves or unmounts.

System APIs include alert, toast, sharing, URL linking, vibration, device
dimensions/appearance/app state, keyboard dismissal, permission checks and
permission requests. Their operation IDs are sequential integers across PHP,
JNI, and Kotlin.

## Thread ownership

The UI thread is reserved for work that must manipulate Android host views.
Moving PHP, binary parsing, network work, image decoding, or global layout onto
that thread would make the app slower, not faster.

| Work | Owner |
| --- | --- |
| PHP state and rendering | persistent PHP worker |
| validation, diff, layout | Rust worker |
| PNB1 decoding and packed-list indexing | native callback worker |
| create/update/move Android views | UI thread, once per VSYNC |
| press feedback, gestures, scrolling, focus, input state | UI thread |
| opacity/translation/scale/rotation animations | Android `ViewPropertyAnimator` |
| HTTP, storage, image fetch/decode | bounded background executors |

Inputs keep their text and cursor natively. Change delivery defaults to a
48 ms debounce and can be configured as native, debounced, immediate, blur, or
submit synchronization. Lists recycle native rows and retain packed UTF-8
payloads, decoding only visible strings. Layout-only `View`, `Column`, and
`Row` nodes are flattened automatically and promoted if they gain paint,
event, transform, or accessibility properties.

## Native extension views

Apps are not limited to the built-in component set. Declare a generated view
factory in `pam-native.json`:

```json
{
    "views": [
        {
            "name": "maps.route",
            "class": "app.maps.RouteMapFactory"
        }
    ]
}
```

The Kotlin class implements `NativeViewFactory`; PHP sends a typed scalar map
and receives binary events:

```php
CustomView::make('maps.route', [
    'latitude' => -23.5505,
    'longitude' => -46.6333,
])->onNativeEvent($handleMapEvent);
```

`pam mobile codegen` generates the registry. This keeps unusual, high-cost
widgets completely native while preserving the same tree identity, layout, and
event protocol.

## Optimized commit path

```text
PHP Element tree
  │  first render: PNT1 full tree
  │  later renders: PNP1 incremental patch
  ▼
Rust engine
  │  property-only patch: mutate retained tree, skip cloning and usually layout
  │  structural patch: validate transactionally, then diff and layout
  ▼
PNB1 mutation batch owned by Rust
  │  JNI NewDirectByteBuffer (no jbyteArray copy)
  ▼
native callback worker
  │  decode mutations, index packed lists, retain long-lived binary values
  ▼
Kotlin frame queue
  │  coalesce ready mutations on the next VSYNC
  ▼
Android Views
```

The initial frame remains a complete tree so the engine can bootstrap from one
self-contained message. Later frames use numeric operations:

| PNP1 operation | Value |
| --- | ---: |
| Create subtree | 1 |
| Remove node | 2 |
| Update property | 3 |
| Move node | 4 |
| Set root | 5 |

Node IDs are stable 64-bit hashes of keyed PHP element identities. Reused
immutable subtrees are cached with `WeakMap`; returning the exact same tree
produces no native commit.

Paint-only property changes emit a mutation without recalculating layout.
Dimensions, flex, spacing, min/max constraints, margins, and alignment
invalidate layout. The renderer maintains incremental child indexes and applies
only layout IDs emitted by Rust; it does not rescan the full tree for every
node.

## Native boundary

- Rust transfers each `PNB1` output buffer to C++.
- JNI exposes it to Kotlin as a direct, read-only `ByteBuffer`.
- Kotlin accepts the buffer through an ownership handshake, queues it for the
  next display frame, and releases it after the renderer applies the batch.
- Shutdown drains queued and accepted buffers before destroying the renderer.
- Debug logs include `buffers`; it must return to `0` after every completed
  frame.
- PNB1 decoding reads directly from the Rust-owned buffer. Binary properties
  that must outlive the frame receive one bounded off-UI-thread copy before the
  Rust buffer is released.

Built-in HTTP and storage calls use sequential integer operation IDs across
PHP, C++, JNI, and Kotlin. The string-based custom module entry point remains as
a compatibility path.

## Composer Plugin SDK

Pam Native packages can ship PHP providers, tag/component libraries, themes,
native modules, native views, Android resources, manifests, Maven
dependencies, AARs, and JNI libraries in one Composer install:

```bash
composer require vendor/maps-plugin
pam mobile plugin:doctor .
pam mobile plugin:list .
pam mobile build .
```

The CLI securely discovers `extra.pam-native.plugin`, validates protocol and
SDK compatibility, rejects binding conflicts and unsafe paths, generates
isolated Android library projects, and writes
`.pam-native/plugins.lock.json`. Android plugins compile against the stable
`:plugin-api` module; production startup performs no native plugin scanning.

PHP providers are auto-registered before the first render. Public
`NativeModules::call()`/`callRaw()` APIs expose custom modules through the same
bounded binary wire path used by PAM itself. Native view lifecycle methods run
on the UI thread; slow module work remains off it.

See the [Plugin SDK guide](docs/plugins.md) and the
[complete reference plugin](examples/community-plugin).

## Benchmarks

Run the isolated encoders and engine:

```bash
php pam-native/packages/native/benchmarks/encoder.php
cargo run --release --manifest-path pam-native/Cargo.toml \
  -p pam-native-engine --example benchmark
```

Build and test the Android showcase:

```bash
cargo run --locked -- mobile build \
  pam-native/examples/showcase --abi arm64-v8a
adb install -r \
  pam-native/examples/showcase/.pam-native/android/app/build/outputs/apk/debug/app-debug.apk
adb logcat -s PamNativePerf:D AndroidRuntime:E '*:S'
```

Run release-like AndroidX Macrobenchmarks on a physical device:

```bash
pam mobile benchmark pam-native/examples/showcase
pam mobile profile pam-native/examples/showcase
python3 pam-native/benchmarks/mobile/compare.py \
  --pam results/pam \
  --react-native results/react-native \
  --output results/comparison.md
```

On a Galaxy S23 Ultra debug build, a counter update used a 35-byte input patch
and took about 0.90 ms to apply. Opening the 10,000-row details example sent its
large data once as a structural patch; returning home stayed incremental.
Throughout the flow the engine recorded one full commit, subsequent patch
commits, and zero outstanding native buffers after each frame.

These numbers measure Pam Native's own paths. A claim such as “100x faster”
relative to Nitro Modules or another framework is only valid after running the
same payload, device, release build, warm-up, and benchmark harness. The
architecture removes bridge serialization and UI-thread work where possible;
it does not manufacture a fixed multiplier for every workload.

## License

PAM Native is part of PAM and is source-available under the
[Business Source License 1.1](LICENSE). Applications built with PAM may be
commercial or proprietary; competing PAM platforms require a commercial
license. See the [licensing guide](LICENSING.md).

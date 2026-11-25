# Pam Native Plugin SDK

Pam Native plugins are ordinary Composer packages. One package can publish:

- PHP service providers, tags, components, themes, and class tokens;
- Android native modules callable from persistent PHP;
- Android native view factories rendered inside the Pam tree;
- Android resources, assets, manifests, JNI libraries, Maven libraries, and
  local AARs;
- iOS native modules/views, Swift Package products, system frameworks,
  resources, usage descriptions, entitlements, and signed extension plans.

Discovery and autolinking happen at build time. Production apps do not scan
classpaths, load DEX files, or parse plugin JSON during a frame.

## Core and plugins

The core keeps only the base every app needs: runtime and protocol,
renderer and layout, primitives, navigation, state and reactivity, basic
HTTP, gestures, keyboard, accessibility and storage, plus the generic hooks
plugins build on. A feature that not every app uses ships as a
`pam-native-*` plugin.

Generic hooks for plugin modules (Android `:plugin-api`, iOS `PamNative`):

- `WatchChannel`: bounded single-reader channel for `watch`/`next`/`stop`
  observations (location fixes, sensor readings, recorder levels).
- Android `ActivityResultHost`: the PAM activity implements it; a module
  created with that activity as its context starts a system screen
  (`launchForResult`, `launchIntentSenderForResult`) and receives the result.
- Android `dev.pam.nativeapp.text.PamTextProcessors`: one processor rewrites
  the spans of every `Text` before it is measured and drawn (the same
  `CharSequence` for both). A plugin installs it, e.g. EmojiCompat spans so
  emoji newer than the system font render on Android 7–12 instead of tofu,
  and calls `PamTextProcessors.changed()` when its results change (the emoji
  font finished loading): the host measures every text again and rebuilds the
  views once, like a font-scale change. The processor runs on the layout
  thread and must keep the characters (spans only); a failure or a changed
  length falls back to the plain text. The core ships no emoji font.
- Picture-in-Picture: `"android": { "pictureInPicture": true }` in the app's
  `pam-native.json` declares `android:supportsPictureInPicture` on the PAM
  activity. While the activity is in PiP, the host does not report it
  inactive: video keeps playing, PHP stays `Active` and the window resize
  reaches PHP as a `dimensions` change (lay out a PiP-sized screen there);
  closing the PiP window stops the activity as usual. Entering PiP is the
  feature's job (the calls plugin for a video call): the activity is an
  androidx `ComponentActivity`, so a module created with it calls
  `setPictureInPictureParams()` (`setAutoEnterEnabled(true)` on Android 12+)
  or `enterPictureInPictureMode()`, and listens with
  `addOnUserLeaveHintListener` (Android 8–11 manual entry) and
  `addOnPictureInPictureModeChangedListener` (to tell PHP to hide controls);
  it removes its listeners when the module closes. iOS PiP for calls is the
  calls plugin's (`AVPictureInPictureController`).

Moved out of the core in 1.46. The deprecated aliases under the old class
names are removed: import the plugin classes (the new names below). Only the
Drawer (`Navigation\Drawer*`, `UI\DrawerLayoutAndroid`,
`Routing\DrawerRegistrar`), Toast (`System\Toast*`) and `UI\WebView` aliases
remain, because `Route::drawer()` and the `<WebView>` template tag go through
them and throw `PluginException` naming the package when it is not installed.
The same applies to the notifications, media, auth and share-extension
classes listed in the 1.46.0 CHANGELOG.

| Old core class | Plugin | New class |
| --- | --- | --- |
| `System\Location`, `LocationError`, `LocationPosition`, `LocationServicesResult` | `pushinbr/pam-native-location` | `Pam\Native\Location\*` |
| `System\Contacts`, `Contact`, `System\Sms` | `pushinbr/pam-native-contacts` | `Pam\Native\Contacts\*` |
| `System\Sensors`, `SensorReading`, `SensorType` | `pushinbr/pam-native-sensors` | `Pam\Native\Sensors\*` |
| `System\AudioRecorder`, `AudioRecording`, `AudioRecordingProgress` | `pushinbr/pam-native-audio` | `Pam\Native\Audio\*` |
| `System\ImageEditor`, `ImageCropRatio`, `ImageFilterType`, `ImageTextLayerStyle` | media editor plugin | `Pam\Native\MediaEditor\*` |

The native module names (`location`, `contacts`, `sms`, `sensors`,
`audio-recorder`, `image-editor`) and their wire payloads did not change.

## Install and inspect

```bash
composer require vendor/maps-plugin
pam mobile plugin:doctor
pam mobile plugin:list
pam mobile codegen
pam mobile ios:prepare
```

`prepare`, `build`, `run`, and `dev` also run codegen automatically. The
resolved graph is written to `.pam-native/plugins.lock.json`, including package
versions and descriptor SHA-256 hashes. Commit the Composer lockfile; the PAM
lock is generated build evidence.

The complete working package is in
[`examples/community-plugin`](../examples/community-plugin).

## Composer contract

The package advertises one descriptor:

```json
{
    "name": "vendor/maps-plugin",
    "type": "pam-native-plugin",
    "autoload": {
        "psr-4": {
            "Vendor\\Maps\\": "src/"
        }
    },
    "extra": {
        "pam-native": {
            "plugin": "pam-native.plugin.json"
        }
    }
}
```

`pam-native.plugin.json` is strict and versioned:

```json
{
    "$schema": "vendor/pushinbr/pam-native/resources/pam-native.plugin.schema.json",
    "version": 1,
    "protocol": 1,
    "pamNative": {
        "minimum": "0.2.0",
        "maximumExclusive": "0.3.0"
    },
    "php": {
        "provider": "Vendor\\Maps\\MapsPluginProvider"
    },
    "android": {
        "namespace": "com.vendor.pam.maps",
        "minSdk": 26,
        "permissions": [
            "android.permission.ACCESS_FINE_LOCATION"
        ],
        "repositories": [
            "https://maven.vendor.example/releases"
        ],
        "dependencies": [
            "com.vendor:maps-android:4.2.0"
        ],
        "localAars": [
            "android/libs/closed-source-renderer.aar"
        ],
        "sourceDirs": [
            "android/src/main/kotlin"
        ],
        "resourceDirs": [
            "android/src/main/res"
        ],
        "assetDirs": [
            "android/src/main/assets"
        ],
        "jniLibDirs": [
            "android/src/main/jniLibs"
        ],
        "manifest": "android/src/main/AndroidManifest.xml",
        "consumerRules": "android/consumer-rules.pro"
    },
    "ios": {
        "minimumVersion": "15.0",
        "sourceDirs": ["ios/Sources"],
        "resourceDirs": ["ios/Resources"],
        "swiftPackages": [
            {
                "url": "https://github.com/vendor/mobile-sdk.git",
                "requirement": {"kind": 2, "value": "4.2.0"},
                "products": ["VendorMobile"]
            }
        ],
        "frameworks": ["AuthenticationServices"],
        "usageDescriptions": {
            "NSFaceIDUsageDescription": "Authenticate your account."
        },
        "entitlements": "ios/App.entitlements",
        "extensions": [
            {
                "kind": 1,
                "name": "MapsShareExtension",
                "bundleSuffix": "maps-share",
                "sourceDirs": ["ios/ShareExtension"],
                "infoPlist": "ios/ShareExtension/Info.plist"
            }
        ]
    },
    "idl": "pam-native.idl.json",
    "modules": [
        {
            "name": "maps.geocoder",
            "class": "com.vendor.pam.maps.GeocoderModule",
            "iosClass": "GeocoderModule"
        }
    ],
    "views": [
        {
            "name": "maps.route",
            "class": "com.vendor.pam.maps.RouteMapFactory"
        }
    ]
}
```

All paths are package-relative. PAM canonicalizes them and rejects traversal,
symlink escapes, oversized descriptors, duplicate binding names, incompatible
SDK/protocol ranges, non-HTTPS repositories, invalid Maven coordinates, and a
plugin `minSdk` above the app `minSdk`. A plugin `minSdk` may be 24 to 36 and
defaults to 24; an app that targets Android 7.x (`android.minSdk` 24) can only
use plugins that declare 24 or omit it, see [Android 7.x](android-min-sdk.md).

Maven View libraries and precompiled Compose/View AARs work through
`dependencies` or `localAars`. The plugin module participates in normal Android
manifest merging, resource processing, R8 consumer rules, and JNI packaging.

`pam mobile ios:prepare` creates `.pam-native/ios/plugins.json` plus the local
`.pam-native/ios/PamNativePlugins` Swift Package. Requirement kinds are exact
`1`, from/up-to-next-major `2`, branch `3`, revision `4`, and
up-to-next-minor `5`. Extension kinds are share `1`, widget `2`, notification
service `3`, App Intents `4`, and Live Activity `5`. Values are append-only.

The generated package exposes one registry for runtime injection:

```swift
import PamNative
import PamNativePlugins

let runtime = PamRuntime(
    hostView: hostView,
    nativeModules: PamNativePluginRegistry.modules(),
    nativeViews: PamNativePluginRegistry.views(),
    reportError: reportError
)
```

Swift Package Manager compiles modules, views and resources. App extensions
still require signed Xcode application targets; their canonical source paths,
plist, entitlements, kind and bundle suffix are emitted in `plugins.json` for
the host target generator.

### Share sheets and per-application configuration

A plugin that receives content from other applications' share sheets declares
`share` instead of hardcoding intent filters or an activation rule. Set
`pamNative.minimum` (and the Composer requirement) to 1.16.0 or later; no
capability needs to be declared. Each
application then chooses the accepted types under `plugins.<configKey>` in its
`pam-native.json`:

```json
{
    "share": {
        "configKey": "shareExtension",
        "accept": ["*/*"],
        "multiple": true
    }
}
```

```json
{
    "plugins": {
        "shareExtension": {
            "accept": ["text/plain", "image/*", "video/*"],
            "multiple": ["image/*", "video/*"]
        }
    }
}
```

`accept` lists 1 to 16 lowercase MIME types and defaults to the plugin's
value. `multiple` is `true` (every accepted type may arrive several at once),
`false`, or the accepted types that may. During prepare Pam Native:

- adds one Android `SEND` intent filter per type to the launcher activity,
  with `SEND_MULTIPLE` only for the types in `multiple`, merged with
  `android.shareTargets`;
- replaces the `NSExtensionActivationRule` of the plugin's Share Extensions
  (kind `1`). `*/*` enables text, web URLs, files, images and movies;
  `text/plain` enables text and web URLs (Android delivers links as
  `text/plain`); `image/*` and `video/*` enable images and movies. Specific
  types such as `application/pdf` produce a `SUBQUERY` predicate over their
  type identifiers. Counts are 1 for single items and 8 (text, URLs, movies)
  or 32 (files, images) otherwise.

A `plugins` key that no installed plugin declares, two plugins sharing a
`configKey`, an invalid MIME type or a `multiple` type missing from `accept`
fails prepare. Keep validating received content in PHP: share sheets are an
untrusted input.

## Component libraries

A package can ship single-file `.pam` components. Declare their directory
(package-relative) in the descriptor:

```json
{
    "php": {
        "provider": "Vendor\\Ui\\UiPluginProvider",
        "components": "components"
    }
}
```

Every component in it is registered at boot under its `#[Tag('…')]`, before
the providers run, and the build's component prebuild packs them with the
application's own, so a release build compiles none on the device. The
package's `src/app.css` is prepended to each of its components, the same way
an application's `src/app.css` is. `pushinbr/pam-native-ui` is built this way.

## PHP provider

Providers are registered in package-name order before the first render.
`register()` defines components; `boot()` starts work that depends on every
plugin being registered.

```php
<?php

declare(strict_types=1);

namespace Vendor\Maps;

use Pam\Native\Plugin\PluginProvider;
use Pam\Native\TemplateRegistry;
use Pam\Native\UI\CustomView;

final class MapsPluginProvider implements PluginProvider
{
    public function register(): void
    {
        TemplateRegistry::component(
            'RouteMap',
            static fn (array $props, array $_children, ?object $_scope): CustomView => CustomView::make(
                'maps.route',
                [
                    'routeId' => (string) ($props['routeId'] ?? ''),
                    'interactive' => (bool) ($props['interactive'] ?? true),
                ],
            ),
        );
        TemplateRegistry::eventAdapter(
            'RouteMap',
            static fn (
                \Pam\Native\EventKind $_kind,
                \Closure $handler,
                array $_props,
            ): \Closure => $handler,
        );
    }

    public function boot(): void
    {
    }
}
```

The application can then use a package as a tag library:

```xml
<Screen>
    <RouteMap
        routeId="$routeId"
        interactive="true"
        class="flex-1 rounded-xl"
    />
</Screen>
```

Providers are optional. A package can be PHP-only, Android-only, or combine
both sides. Event adapters run only for their registered tag and can translate
one bounded native payload into the package's documented PHP callback value.
Ancestor handlers are also carried as declarative composition context, which
lets compound component libraries bind item/trigger behavior before rendering.

## Native module

Plugin module constructors receive the Android `Context`. Implementations
compile against the stable `:plugin-api` library:

```kotlin
class GeocoderModule(
    private val context: Context,
) : NativeModule {
    override fun invoke(
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        when (method) {
            "lookup" -> executor.execute {
                val input = WireMap.decode(payload)
                val result = lookup(input)
                completion.complete(
                    ModuleResultStatus.SUCCESS,
                    WireMap.encode(result),
                )
            }
            else -> completion.complete(
                ModuleResultStatus.FAILURE,
                "Unknown method $method".toByteArray(),
            )
        }
    }
}
```

PHP calls it without JSON:

```php
use Pam\Native\Modules\NativeModuleResult;
use Pam\Native\Modules\NativeModules;

NativeModules::call(
    'maps.geocoder',
    'lookup',
    ['query' => 'Av. Paulista'],
    function (NativeModuleResult $result): void {
        if ($result->succeeded()) {
            $coordinates = $result->values();
        }
    },
);
```

`call()` carries scalar maps through the bounded binary wire format.
`callRaw()` is available for a package-owned binary protocol.

A module that keeps runtime state (sockets, calls, event waiters, in-flight
work) implements `SurfaceRetirableModule`: when Android recreates
`PamActivity`, the old registry calls `retireSurface()` instead of `close()`.
See [android-activity-lifecycle.md](android-activity-lifecycle.md) for the
configuration changes that no longer recreate the Activity and those that
still do.

### Hot reload and pending prompts

`pam dev` restarts the PHP request when files change; a callback waiting for
a native prompt (a dialog, a biometric sheet, a picker) goes with it, and
completions for the old request are dropped. A module holding such a prompt
implements `dev.pam.nativeapp.modules.ReloadAwareModule` (PAM Native 1.53.7+)
and closes it in `prepareReload()`. Plugins that support older SDKs can
register a `dev.pam.nativeapp.HostEventListener` with
`HostEvents.register(...)` and do the same in its `prepareReload()`.

## Native view

View factory constructors also receive `Context`:

```kotlin
class RouteMapFactory(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : NativeViewFactory {
    override fun create(
        context: Context,
        emit: (ByteArray) -> Unit,
    ): View = VendorMapView(context).apply {
        onRouteTap { routeId ->
            emit(WireMap.encode(mapOf("routeId" to WireValue.Text(routeId))))
        }
    }

    override fun update(
        view: View,
        properties: Map<String, WireValue>,
    ) {
        val map = view as VendorMapView
        map.routeId = (properties["routeId"] as? WireValue.Text)?.value.orEmpty()
    }

    override fun release(view: View) {
        (view as VendorMapView).close()
    }
}
```

PAM guarantees `create`, `update`, and `release` run on Android's UI thread.
Network, disk, large decoding, and other blocking work must leave that thread.
Native gesture recognition, focus, scrolling, pressed state, and view-local
animations should remain on it.

## Background data-only push

Android plugins can react to FCM data-only delivery while PHP is suspended.
Declare a package-private receiver in the plugin manifest:

```xml
<receiver
    android:name=".CallPushReceiver"
    android:enabled="true"
    android:exported="false">
    <intent-filter>
        <action android:name="dev.pam.nativeapp.action.PUSH_RECEIVED" />
    </intent-filter>
</receiver>
```

Read its bounded extras through the stable `BackgroundPush` constants from the
plugin API:

```kotlin
class CallPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BackgroundPush.ACTION_RECEIVED) return
        val data = JSONObject(
            intent.getStringExtra(BackgroundPush.EXTRA_DATA_JSON) ?: "{}",
        )
        // Schedule the call notification or other bounded native work.
    }
}
```

The broadcast is explicitly restricted to the current application package.
Receivers must remain `exported="false"` and should finish synchronously or
delegate longer work to WorkManager. PAM persists the same event for normal
PHP `PushNotifications` delivery, so the native hook complements rather than
replaces application routing.

### FCM registration token refresh

The optional Firebase service also forwards `onNewToken` through
`BackgroundPush.ACTION_TOKEN_REFRESHED`
(`dev.pam.nativeapp.action.PUSH_TOKEN_REFRESHED`). Add that action to a plugin's
manifest receiver with `android:exported="false"`, and read the opaque token from
`BackgroundPush.EXTRA_TOKEN` (`dev.pam.nativeapp.push.TOKEN`). The receiver does
not depend on PHP or dynamic plugin registration, so it can accept a refresh
before the plugin has been loaded by the runtime.

`BackgroundPush.tokenRefreshedIntent(context, token)` returns a package-restricted
intent, or `null` for a blank token or one longer than 8,192 characters. Accepted
tokens are preserved exactly, never truncated. The SDK does not log the token,
store account credentials, contact application endpoints or poll for changes.
The receiving plugin owns durable storage, authenticated registration and any
bounded retry work; it must not log token contents or perform network requests
inside `onReceive`. This event does not imply that a user is signed in. Declare
the manifest receiver rather than relying only on a receiver registered after
the UI starts.

## Compatibility policy

- Descriptor version, wire protocol, statuses, and operation kinds are
  sequential integers beginning at `1`.
- A package declares a closed SDK interval with `minimum` and
  `maximumExclusive`.
- Binding names are globally unique across the app and installed packages.
- Plugin API changes require a protocol or compatibility-range change.
- Packages should test against the lowest and newest SDK versions they claim.

PAM deliberately does not execute arbitrary package-provided Gradle scripts.
The declared integration surface covers source, View/Compose AARs, Maven
dependencies, resources, assets, manifests, JNI, and R8 while keeping builds
auditable.


### Core extraction, wave two (1.46)

| Capability | Package | Canonical PHP namespace |
| --- | --- | --- |
| Notifications | pushinbr/pam-native-notifications | Pam\Native\Notifications (domain builders under Contracts) |
| Media library/capture/view capture | pushinbr/pam-native-media | Pam\Native\Media |
| Incoming shares | pushinbr/pam-native-share-extension | Pam\Native\ShareExtension |
| Biometrics compatibility | pushinbr/pam-native-auth | Pam\Native\Auth\LegacyBiometrics |
| WebView | pushinbr/pam-native-webview | Pam\Native\WebView |

The core ships aliases for one minor version. Removing a plugin makes its old
PHP facade fail with the exact Composer package to install. Existing numeric
protocol keys and native module names remain reserved. WebView's reserved node
uses the registered `webview` factory and the generic `NativePropertyView`
bridge; core layout still handles its ordinary Element properties.

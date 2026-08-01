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

## Install and inspect

```bash
composer require vendor/maps-plugin
pam mobile plugin:doctor .
pam mobile plugin:list .
pam mobile codegen .
pam mobile ios:prepare .
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
plugin `minSdk` above the app `minSdk`.

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

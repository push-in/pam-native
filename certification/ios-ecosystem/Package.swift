// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "PamNativePlugins",
    platforms: [.iOS("18.0")],
    products: [.library(name: "PamNativePlugins", targets: ["PamNativePlugins"])],
    dependencies: [
        .package(path: "../../ios"),
        .package(url: "https://github.com/firebase/firebase-ios-sdk.git", exact: "12.17.0"),
        .package(url: "https://github.com/stripe/stripe-ios.git", exact: "26.0.0"),
    ],
    targets: [
        .target(
            name: "PamPlugin0PushinbrPamNativeAuth",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin0PushinbrPamNativeAuth",
            linkerSettings: [.linkedFramework("Security")],
        ),
        .target(
            name: "PamPlugin1PushinbrPamNativeBackgroundTransfer",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin1PushinbrPamNativeBackgroundTransfer",
        ),
        .target(
            name: "PamPlugin2PushinbrPamNativeBluetooth",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin2PushinbrPamNativeBluetooth",
            linkerSettings: [.linkedFramework("CoreBluetooth")],
        ),
        .target(
            name: "PamPlugin3PushinbrPamNativeFeatureFlags",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin3PushinbrPamNativeFeatureFlags",
            linkerSettings: [.linkedFramework("CryptoKit")],
        ),
        .target(
            name: "PamPlugin4PushinbrPamNativeFirebase",
            dependencies: [.product(name: "PamNative", package: "ios"), .product(name: "FirebaseAnalytics", package: "firebase-ios-sdk"), .product(name: "FirebaseCore", package: "firebase-ios-sdk"), .product(name: "FirebaseCrashlytics", package: "firebase-ios-sdk"), .product(name: "FirebaseInstallations", package: "firebase-ios-sdk"), .product(name: "FirebaseMessaging", package: "firebase-ios-sdk"), .product(name: "FirebaseRemoteConfig", package: "firebase-ios-sdk")],
            path: "Sources/PamPlugin4PushinbrPamNativeFirebase",
        ),
        .target(
            name: "PamPlugin5PushinbrPamNativeHealth",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin5PushinbrPamNativeHealth",
            linkerSettings: [.linkedFramework("HealthKit")],
        ),
        .target(
            name: "PamPlugin6PushinbrPamNativeIntents",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin6PushinbrPamNativeIntents",
            linkerSettings: [.linkedFramework("AppIntents")],
        ),
        .target(
            name: "PamPlugin7PushinbrPamNativeLiveActivities",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin7PushinbrPamNativeLiveActivities",
            linkerSettings: [.linkedFramework("ActivityKit"), .linkedFramework("WidgetKit"), .linkedFramework("SwiftUI")],
        ),
        .target(
            name: "PamPlugin8PushinbrPamNativeMaps",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin8PushinbrPamNativeMaps",
            linkerSettings: [.linkedFramework("MapKit"), .linkedFramework("CoreLocation")],
        ),
        .target(
            name: "PamPlugin9PushinbrPamNativeMedia",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin9PushinbrPamNativeMedia",
            linkerSettings: [.linkedFramework("AVFoundation"), .linkedFramework("ImageIO"), .linkedFramework("UniformTypeIdentifiers")],
        ),
        .target(
            name: "PamPlugin10PushinbrPamNativeMediaEditor",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin10PushinbrPamNativeMediaEditor",
            linkerSettings: [.linkedFramework("AVFoundation"), .linkedFramework("CoreImage"), .linkedFramework("CoreMedia"), .linkedFramework("CoreVideo")],
        ),
        .target(
            name: "PamPlugin11PushinbrPamNativeNfc",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin11PushinbrPamNativeNfc",
            linkerSettings: [.linkedFramework("CoreNFC")],
        ),
        .target(
            name: "PamPlugin12PushinbrPamNativePayments",
            dependencies: [.product(name: "PamNative", package: "ios"), .product(name: "StripePaymentSheet", package: "stripe-ios")],
            path: "Sources/PamPlugin12PushinbrPamNativePayments",
        ),
        .target(
            name: "PamPlugin13PushinbrPamNativeRealtime",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin13PushinbrPamNativeRealtime",
        ),
        .target(
            name: "PamPlugin14PushinbrPamNativeScanner",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin14PushinbrPamNativeScanner",
            linkerSettings: [.linkedFramework("AVFoundation"), .linkedFramework("Vision")],
        ),
        .target(
            name: "PamPlugin15PushinbrPamNativeShareExtension",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin15PushinbrPamNativeShareExtension",
            linkerSettings: [.linkedFramework("UniformTypeIdentifiers")],
        ),
        .target(
            name: "PamPlugin16PushinbrPamNativeSubscriptions",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin16PushinbrPamNativeSubscriptions",
            linkerSettings: [.linkedFramework("StoreKit")],
        ),
        .target(
            name: "PamPlugin17PushinbrPamNativeVideo",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin17PushinbrPamNativeVideo",
            linkerSettings: [.linkedFramework("AVFoundation"), .linkedFramework("AVKit")],
        ),
        .target(
            name: "PamPlugin18PushinbrPamNativeWidgets",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin18PushinbrPamNativeWidgets",
            linkerSettings: [.linkedFramework("WidgetKit"), .linkedFramework("SwiftUI")],
        ),
        .target(
            name: "PamPlugin19PushinbrPamNativeCamera",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin19PushinbrPamNativeCamera",
            linkerSettings: [.linkedFramework("AVFoundation"), .linkedFramework("CoreImage"), .linkedFramework("CoreMedia"), .linkedFramework("CoreVideo"), .linkedFramework("Metal"), .linkedFramework("Vision")],
        ),
        .target(
            name: "PamPlugin20PushinbrPamNativeCanvas",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin20PushinbrPamNativeCanvas",
            linkerSettings: [.linkedFramework("CoreGraphics"), .linkedFramework("UIKit")],
        ),
        .target(
            name: "PamPlugin21PushinbrPamNativeGpu",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin21PushinbrPamNativeGpu",
            linkerSettings: [.linkedFramework("Metal"), .linkedFramework("MetalKit"), .linkedFramework("QuartzCore")],
        ),
        .target(
            name: "PamPlugin22PushinbrPamNative3d",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin22PushinbrPamNative3d",
            linkerSettings: [.linkedFramework("RealityKit"), .linkedFramework("Metal"), .linkedFramework("QuartzCore")],
        ),
        .target(
            name: "PamExtensionIntents",
            path: "Sources/PamExtensionIntents",
            linkerSettings: [.linkedFramework("AppIntents")],
        ),
        .target(
            name: "PamExtensionLiveActivities",
            path: "Sources/PamExtensionLiveActivities",
            linkerSettings: [.linkedFramework("ActivityKit"), .linkedFramework("WidgetKit"), .linkedFramework("SwiftUI")],
        ),
        .target(
            name: "PamExtensionShare",
            path: "Sources/PamExtensionShare",
            linkerSettings: [.linkedFramework("Social"), .linkedFramework("UniformTypeIdentifiers")],
        ),
        .target(
            name: "PamExtensionWidgets",
            path: "Sources/PamExtensionWidgets",
            linkerSettings: [.linkedFramework("WidgetKit"), .linkedFramework("SwiftUI")],
        ),
        .target(
            name: "PamNativePlugins",
            dependencies: ["PamPlugin0PushinbrPamNativeAuth", "PamPlugin1PushinbrPamNativeBackgroundTransfer", "PamPlugin2PushinbrPamNativeBluetooth", "PamPlugin3PushinbrPamNativeFeatureFlags", "PamPlugin4PushinbrPamNativeFirebase", "PamPlugin5PushinbrPamNativeHealth", "PamPlugin6PushinbrPamNativeIntents", "PamPlugin7PushinbrPamNativeLiveActivities", "PamPlugin8PushinbrPamNativeMaps", "PamPlugin9PushinbrPamNativeMedia", "PamPlugin10PushinbrPamNativeMediaEditor", "PamPlugin11PushinbrPamNativeNfc", "PamPlugin12PushinbrPamNativePayments", "PamPlugin13PushinbrPamNativeRealtime", "PamPlugin14PushinbrPamNativeScanner", "PamPlugin15PushinbrPamNativeShareExtension", "PamPlugin16PushinbrPamNativeSubscriptions", "PamPlugin17PushinbrPamNativeVideo", "PamPlugin18PushinbrPamNativeWidgets", "PamPlugin19PushinbrPamNativeCamera", "PamPlugin20PushinbrPamNativeCanvas", "PamPlugin21PushinbrPamNativeGpu", "PamPlugin22PushinbrPamNative3d", "PamExtensionIntents", "PamExtensionLiveActivities", "PamExtensionShare", "PamExtensionWidgets"],
            path: "Sources/PamNativePlugins"
        ),
    ]
)

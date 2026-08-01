// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "PamNativePlugins",
    platforms: [.iOS(.v16_2)],
    products: [.library(name: "PamNativePlugins", targets: ["PamNativePlugins"])],
    dependencies: [
        .package(path: "../../ios"),
        .package(url: "https://github.com/firebase/firebase-ios-sdk.git", exact: "12.17.0"),
        .package(url: "https://github.com/stripe/stripe-ios.git", exact: "26.4.1"),
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
            name: "PamPlugin10PushinbrPamNativeNfc",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin10PushinbrPamNativeNfc",
            linkerSettings: [.linkedFramework("CoreNFC")],
        ),
        .target(
            name: "PamPlugin11PushinbrPamNativePayments",
            dependencies: [.product(name: "PamNative", package: "ios"), .product(name: "StripePaymentSheet", package: "stripe-ios")],
            path: "Sources/PamPlugin11PushinbrPamNativePayments",
        ),
        .target(
            name: "PamPlugin12PushinbrPamNativeRealtime",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin12PushinbrPamNativeRealtime",
        ),
        .target(
            name: "PamPlugin13PushinbrPamNativeScanner",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin13PushinbrPamNativeScanner",
            linkerSettings: [.linkedFramework("AVFoundation"), .linkedFramework("Vision")],
        ),
        .target(
            name: "PamPlugin14PushinbrPamNativeShareExtension",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin14PushinbrPamNativeShareExtension",
            linkerSettings: [.linkedFramework("UniformTypeIdentifiers")],
        ),
        .target(
            name: "PamPlugin15PushinbrPamNativeSubscriptions",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin15PushinbrPamNativeSubscriptions",
            linkerSettings: [.linkedFramework("StoreKit")],
        ),
        .target(
            name: "PamPlugin16PushinbrPamNativeVideo",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin16PushinbrPamNativeVideo",
            linkerSettings: [.linkedFramework("AVFoundation"), .linkedFramework("AVKit")],
        ),
        .target(
            name: "PamPlugin17PushinbrPamNativeWidgets",
            dependencies: [.product(name: "PamNative", package: "ios")],
            path: "Sources/PamPlugin17PushinbrPamNativeWidgets",
            linkerSettings: [.linkedFramework("WidgetKit"), .linkedFramework("SwiftUI")],
        ),
        .target(
            name: "PamNativePlugins",
            dependencies: ["PamPlugin0PushinbrPamNativeAuth", "PamPlugin1PushinbrPamNativeBackgroundTransfer", "PamPlugin2PushinbrPamNativeBluetooth", "PamPlugin3PushinbrPamNativeFeatureFlags", "PamPlugin4PushinbrPamNativeFirebase", "PamPlugin5PushinbrPamNativeHealth", "PamPlugin6PushinbrPamNativeIntents", "PamPlugin7PushinbrPamNativeLiveActivities", "PamPlugin8PushinbrPamNativeMaps", "PamPlugin9PushinbrPamNativeMedia", "PamPlugin10PushinbrPamNativeNfc", "PamPlugin11PushinbrPamNativePayments", "PamPlugin12PushinbrPamNativeRealtime", "PamPlugin13PushinbrPamNativeScanner", "PamPlugin14PushinbrPamNativeShareExtension", "PamPlugin15PushinbrPamNativeSubscriptions", "PamPlugin16PushinbrPamNativeVideo", "PamPlugin17PushinbrPamNativeWidgets"],
            path: "Sources/PamNativePlugins"
        ),
    ]
)

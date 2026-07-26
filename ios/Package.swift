// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "PamNative",
    platforms: [
        .iOS(.v15),
    ],
    products: [
        .library(name: "PamNative", targets: ["PamNative"]),
    ],
    targets: [
        .target(
            name: "PamNative",
            path: "Sources/PamNative",
            exclude: ["Bridge"]
        ),
        .target(
            name: "PamNativeTestRuntimeShims",
            path: "Sources/PamNativeTestRuntimeShims"
        ),
        .testTarget(
            name: "PamNativeTests",
            dependencies: ["PamNative", "PamNativeTestRuntimeShims"],
            path: "Tests/PamNativeTests"
        ),
    ],
    swiftLanguageVersions: [.v5]
)

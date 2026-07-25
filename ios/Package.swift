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
    ],
    swiftLanguageVersions: [.v5]
)

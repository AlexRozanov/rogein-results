// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SfrKit",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "SfrKit", targets: ["SfrKit"]),
    ],
    targets: [
        .target(name: "SfrKit"),
        .testTarget(
            name: "SfrKitTests",
            dependencies: ["SfrKit"],
            resources: [.copy("Resources")]
        ),
    ]
)

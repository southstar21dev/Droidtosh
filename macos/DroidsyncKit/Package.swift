// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "DroidsyncKit",
    platforms: [.macOS(.v15)],
    products: [
        .library(name: "DroidsyncKit", targets: ["DroidsyncKit"]),
        .library(name: "DroidsyncProtocol", targets: ["DroidsyncProtocol"]),
        .library(name: "DroidsyncTransport", targets: ["DroidsyncTransport"]),
        .library(name: "DroidsyncNotifications", targets: ["DroidsyncNotifications"]),
        .library(name: "DroidsyncCalls", targets: ["DroidsyncCalls"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-protobuf.git", from: "1.29.0"),
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.65.0"),
        .package(url: "https://github.com/apple/swift-nio-ssl.git", from: "2.27.0"),
    ],
    targets: [
        .target(
            name: "DroidsyncProtocol",
            dependencies: [.product(name: "SwiftProtobuf", package: "swift-protobuf")],
            path: "Sources/DroidsyncProtocol",
            exclude: ["Protos"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .target(
            name: "DroidsyncTransport",
            dependencies: [
                "DroidsyncProtocol",
                .product(name: "NIOCore", package: "swift-nio"),
                .product(name: "NIOPosix", package: "swift-nio"),
                .product(name: "NIOSSL", package: "swift-nio-ssl"),
            ],
            path: "Sources/DroidsyncTransport",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .target(
            name: "DroidsyncNotifications",
            dependencies: ["DroidsyncProtocol"],
            path: "Sources/DroidsyncNotifications",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .target(
            name: "DroidsyncCalls",
            dependencies: ["DroidsyncProtocol"],
            path: "Sources/DroidsyncCalls",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .target(
            name: "DroidsyncKit",
            dependencies: ["DroidsyncProtocol", "DroidsyncTransport", "DroidsyncNotifications", "DroidsyncCalls"],
            path: "Sources/DroidsyncKit",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // Headless Mac peer for the end-to-end harness in e2e/; test tooling, not used by the app.
        .executableTarget(
            name: "droidsync-e2e-mac",
            dependencies: ["DroidsyncProtocol", "DroidsyncTransport", "DroidsyncNotifications"],
            path: "Sources/droidsync-e2e-mac",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "DroidsyncProtocolTests",
            dependencies: ["DroidsyncProtocol"],
            path: "Tests/DroidsyncProtocolTests",
            resources: [.copy("Fixtures")],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "DroidsyncTransportTests",
            dependencies: ["DroidsyncTransport", "DroidsyncProtocol"],
            path: "Tests/DroidsyncTransportTests",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "DroidsyncNotificationsTests",
            dependencies: ["DroidsyncNotifications", "DroidsyncProtocol"],
            path: "Tests/DroidsyncNotificationsTests",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "DroidsyncCallsTests",
            dependencies: ["DroidsyncCalls", "DroidsyncProtocol"],
            path: "Tests/DroidsyncCallsTests",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)

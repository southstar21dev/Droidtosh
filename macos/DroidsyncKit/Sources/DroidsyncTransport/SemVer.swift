import Foundation

/// A semantic version (semver.org, 2.0.0), ordered by its precedence rules: major, minor and patch
/// compare numerically; a pre-release (`1.0.0-beta.2`) comes before its release; pre-release
/// identifiers compare one by one, numbers numerically and below text; and build metadata
/// (`+268.c5b81ac`) is ignored.
public struct SemVer: Comparable, Hashable, Sendable, CustomStringConvertible {
    public let major: Int
    public let minor: Int
    public let patch: Int
    public let preRelease: [String]

    public init(major: Int, minor: Int, patch: Int, preRelease: [String] = []) {
        self.major = major
        self.minor = minor
        self.patch = patch
        self.preRelease = preRelease
    }

    /// Parses `X.Y.Z[-pre][+build]`, with an optional leading `v`; nil when it isn't one.
    public init?(_ text: String) {
        let pattern = #"^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"#
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: trimmed, range: NSRange(trimmed.startIndex..., in: trimmed)) else { return nil }
        func group(_ index: Int) -> String? {
            Range(match.range(at: index), in: trimmed).map { String(trimmed[$0]) }
        }
        guard let major = group(1).flatMap(Int.init), let minor = group(2).flatMap(Int.init),
              let patch = group(3).flatMap(Int.init) else { return nil }
        let identifiers = group(4).map { $0.split(separator: ".").map(String.init) } ?? []
        // A numeric identifier must not have a leading zero.
        guard !identifiers.contains(where: { $0.count > 1 && $0.hasPrefix("0") && $0.allSatisfy(\.isNumber) }) else { return nil }
        self.init(major: major, minor: minor, patch: patch, preRelease: identifiers)
    }

    public var isPreRelease: Bool { !preRelease.isEmpty }

    public var description: String {
        "\(major).\(minor).\(patch)" + (preRelease.isEmpty ? "" : "-" + preRelease.joined(separator: "."))
    }

    public static func < (lhs: SemVer, rhs: SemVer) -> Bool {
        if (lhs.major, lhs.minor, lhs.patch) != (rhs.major, rhs.minor, rhs.patch) {
            return (lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
        }
        switch (lhs.preRelease.isEmpty, rhs.preRelease.isEmpty) {
        case (true, _): return false
        case (false, true): return true
        case (false, false): break
        }
        for (a, b) in zip(lhs.preRelease, rhs.preRelease) where a != b {
            switch (Int(a), Int(b)) {
            case let (x?, y?): return x < y
            case (_?, nil): return true
            case (nil, _?): return false
            case (nil, nil): return a < b
            }
        }
        return lhs.preRelease.count < rhs.preRelease.count
    }

    /// Equality follows precedence, so build metadata doesn't make two versions differ.
    public static func == (lhs: SemVer, rhs: SemVer) -> Bool {
        (lhs.major, lhs.minor, lhs.patch) == (rhs.major, rhs.minor, rhs.patch) && lhs.preRelease == rhs.preRelease
    }
}

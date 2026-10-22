import Foundation
import DroidsyncKit

enum BuildConfig {
    /// The semantic version (docs/RELEASING.md), e.g. `0.2.0-beta.1`, or `0.1.0+268.c5b81ac`
    /// for a local build. `CFBundleShortVersionString` only holds its `X.Y.Z` part, as Apple requires.
    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "DroidtoshVersion") as? String
            ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
            ?? "0.0.0"
    }

    /// `version` parsed; a build without a semantic version counts as 0.0.0, so any release is newer.
    static var semVer: SemVer { SemVer(version) ?? SemVer(major: 0, minor: 0, patch: 0) }

    /// The build number derived from the version by scripts/version.sh (docs/RELEASING.md);
    /// it rises with every version, and the updater only installs a higher one.
    static var build: Int {
        Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
    }

    /// The public GitHub repo (`owner/name`) whose releases the updater reads.
    static var updateRepo: String? {
        (Bundle.main.object(forInfoDictionaryKey: "DroidtoshUpdateRepo") as? String).flatMap { $0.isEmpty ? nil : $0 }
    }
}

import CryptoKit
import Foundation
import Security

/// Over-the-air updates from GitHub releases (docs/RELEASING.md).
public enum AppUpdates {
    public static let manifestName = "droidtosh-update.json"

    /// One entry of `GET /repos/<repo>/releases`.
    public struct Release: Decodable, Equatable, Sendable {
        public let tagName: String
        public let draft: Bool
        public let prerelease: Bool
        public let assets: [Asset]

        public struct Asset: Decodable, Equatable, Sendable {
            public let name: String
            public let browserDownloadURL: URL

            enum CodingKeys: String, CodingKey {
                case name
                case browserDownloadURL = "browser_download_url"
            }
        }

        enum CodingKeys: String, CodingKey {
            case tagName = "tag_name"
            case draft, prerelease, assets
        }

        public init(tagName: String, draft: Bool, prerelease: Bool, assets: [Asset]) {
            self.tagName = tagName
            self.draft = draft
            self.prerelease = prerelease
            self.assets = assets
        }

        func asset(named name: String) -> Asset? { assets.first { $0.name == name } }
    }

    /// `droidtosh-update.json`, attached to every release by CI.
    public struct Manifest: Decodable, Equatable, Sendable {
        public let version: String
        public let build: Int
        public let commit: String?
        public let android: Artifact?
        public let mac: Artifact?

        public struct Artifact: Decodable, Equatable, Sendable {
            public let file: String
            public let sha256: String
        }
    }

    /// A newer Mac build to offer.
    public struct Offer: Equatable, Sendable {
        public let tag: String
        public let version: SemVer
        public let build: Int
        public let prerelease: Bool
        public let download: URL
        public let sha256: String
    }

    public static func decodeReleases(_ data: Data) throws -> [Release] {
        try JSONDecoder().decode([Release].self, from: data)
    }

    public static func decodeRelease(_ data: Data) throws -> Release {
        try JSONDecoder().decode(Release.self, from: data)
    }

    /// A non-2xx answer from GitHub.
    public struct HTTPError: Error, Equatable, Sendable {
        public let status: Int
        public init(status: Int) { self.status = status }
    }

    /// The releases to choose from: the recent list plus the latest stable release, which GitHub
    /// returns however many betas followed it. The list alone can lose a stable release behind 20
    /// betas, and "latest" alone can be an older line's hotfix that was published last. No stable
    /// release yet (GitHub answers 404 for "latest") is not an error.
    public static func releases(repo: String, fetch: (URL) async throws -> Data) async throws -> [Release] {
        guard let listURL = releasesURL(repo: repo), let latestURL = latestReleaseURL(repo: repo) else { return [] }
        var latest: Release?
        do {
            latest = try decodeRelease(try await fetch(latestURL))
        } catch let error as HTTPError where error.status == 404 {
            latest = nil
        }
        var seen = Set<String>()
        return ([latest].compactMap { $0 } + (try decodeReleases(try await fetch(listURL))))
            .filter { seen.insert($0.tagName).inserted }
    }

    public static func decodeManifest(_ data: Data) throws -> Manifest {
        try JSONDecoder().decode(Manifest.self, from: data)
    }

    /// The release list URL for `repo` (`owner/name`).
    public static func releasesURL(repo: String) -> URL? {
        URL(string: "https://api.github.com/repos/\(repo)/releases?per_page=20")
    }

    /// The newest release that is neither a draft nor a pre-release.
    public static func latestReleaseURL(repo: String) -> URL? {
        URL(string: "https://api.github.com/repos/\(repo)/releases/latest")
    }

    /// Picks the update to offer: drafts are skipped, pre-releases too unless `includeBeta`, and
    /// releases without a manifest (CI may still be building them). Of the rest, the highest
    /// semantic version wins if it is above `installed` and carries a Mac artifact. A manifest
    /// that can't be fetched or read, or whose version isn't semver, only rules out its own release.
    public static func select(
        from releases: [Release],
        includeBeta: Bool,
        installed: SemVer,
        fetchManifest: (URL) async throws -> Manifest
    ) async -> Offer? {
        var best: Offer?
        for release in releases where !release.draft && (includeBeta || !release.prerelease) {
            guard let manifestAsset = release.asset(named: manifestName),
                  let manifest = try? await fetchManifest(manifestAsset.browserDownloadURL),
                  let version = SemVer(manifest.version),
                  version > installed,
                  best.map({ version > $0.version }) ?? true,
                  let mac = manifest.mac,
                  let download = release.asset(named: mac.file)?.browserDownloadURL else { continue }
            best = Offer(
                tag: release.tagName,
                version: version,
                build: manifest.build,
                prerelease: release.prerelease,
                download: download,
                sha256: mac.sha256.lowercased()
            )
        }
        return best
    }

    public static func sha256Hex(ofFileAt url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    public enum VerificationError: Error, Equatable, CustomStringConvertible {
        case checksumMismatch
        case notABundle
        case wrongBundleIdentifier(String?)
        case notNewer(Int)
        case signature(OSStatus)

        public var description: String {
            switch self {
            case .checksumMismatch: return "the download is damaged (checksum mismatch)"
            case .notABundle: return "the download doesn't contain the app"
            case .wrongBundleIdentifier(let id): return "the download is a different app (\(id ?? "unknown"))"
            case .notNewer(let build): return "the download is build \(build), not newer than this one"
            case .signature(let status): return "the download isn't signed by Droidtosh (\(status))"
            }
        }
    }

    /// Checks an unpacked update before it replaces the running app: same bundle identifier, a
    /// higher build, and a valid code signature that satisfies `requirement`, normally the running
    /// app's designated requirement, which pins the signing certificate.
    public static func verifyBundle(
        at url: URL,
        bundleIdentifier: String,
        installedBuild: Int,
        requirement: SecRequirement
    ) throws {
        guard let bundle = Bundle(url: url), let info = bundle.infoDictionary else {
            throw VerificationError.notABundle
        }
        let identifier = info["CFBundleIdentifier"] as? String
        guard identifier == bundleIdentifier else { throw VerificationError.wrongBundleIdentifier(identifier) }
        let build = Int(info["CFBundleVersion"] as? String ?? "") ?? 0
        guard build > installedBuild else { throw VerificationError.notNewer(build) }
        var staticCode: SecStaticCode?
        var status = SecStaticCodeCreateWithPath(url as CFURL, [], &staticCode)
        guard status == errSecSuccess, let staticCode else { throw VerificationError.signature(status) }
        status = SecStaticCodeCheckValidity(staticCode, SecCSFlags(rawValue: kSecCSCheckAllArchitectures | kSecCSCheckNestedCode), requirement)
        guard status == errSecSuccess else { throw VerificationError.signature(status) }
    }

    /// The running app's designated requirement; nil when it has none (an unsigned build), and
    /// then no update can be verified.
    public static func runningAppRequirement() -> SecRequirement? {
        var code: SecCode?
        guard SecCodeCopySelf([], &code) == errSecSuccess, let code else { return nil }
        var staticCode: SecStaticCode?
        guard SecCodeCopyStaticCode(code, [], &staticCode) == errSecSuccess, let staticCode else { return nil }
        var requirement: SecRequirement?
        guard SecCodeCopyDesignatedRequirement(staticCode, [], &requirement) == errSecSuccess else { return nil }
        return requirement
    }
}

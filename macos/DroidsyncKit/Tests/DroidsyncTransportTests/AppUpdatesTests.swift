import XCTest
@testable import DroidsyncTransport

final class AppUpdatesTests: XCTestCase {
    private func release(_ tag: String, draft: Bool = false, prerelease: Bool = false, manifest: Bool = true) -> AppUpdates.Release {
        let base = "https://github.com/o/r/releases/download/\(tag)/"
        var assets = [AppUpdates.Release.Asset(name: "Droidtosh-\(tag)-mac.zip", browserDownloadURL: URL(string: base + "Droidtosh-\(tag)-mac.zip")!)]
        if manifest {
            assets.append(AppUpdates.Release.Asset(name: AppUpdates.manifestName, browserDownloadURL: URL(string: base + AppUpdates.manifestName)!))
        }
        return AppUpdates.Release(tagName: tag, draft: draft, prerelease: prerelease, assets: assets)
    }

    /// Each tag's manifest; its version is the tag without the "v", and `builds` can override the
    /// build number to show it doesn't decide the order.
    private func manifests(builds: [String: Int] = [:]) -> (URL) throws -> AppUpdates.Manifest {
        { url in
            let tag = url.deletingLastPathComponent().lastPathComponent
            guard tag != "vbroken" else { throw URLError(.badServerResponse) }
            let json = """
            {"version":"\(tag.dropFirst())","build":\(builds[tag] ?? 1),"mac":{"file":"Droidtosh-\(tag)-mac.zip","sha256":"AB12"}}
            """
            return try AppUpdates.decodeManifest(Data(json.utf8))
        }
    }

    private func select(_ releases: [AppUpdates.Release], beta: Bool, installed: String, builds: [String: Int] = [:]) async -> AppUpdates.Offer? {
        let fetch = manifests(builds: builds)
        return await AppUpdates.select(from: releases, includeBeta: beta, installed: SemVer(installed)!) { try fetch($0) }
    }

    func testPicksTheHighestStableVersion() async {
        let releases = [release("v0.2.0"), release("v0.1.5"), release("v0.3.0-beta.1", prerelease: true)]
        let offer = await select(releases, beta: false, installed: "0.1.0")
        XCTAssertEqual(offer?.version, SemVer("0.2.0"))
        XCTAssertEqual(offer?.sha256, "ab12")
        XCTAssertEqual(offer?.download.lastPathComponent, "Droidtosh-v0.2.0-mac.zip")
        XCTAssertEqual(offer?.prerelease, false)
    }

    func testBetaIncludesPrereleases() async {
        let releases = [release("v0.3.0-beta.1", prerelease: true), release("v0.2.0")]
        let offer = await select(releases, beta: true, installed: "0.1.0")
        XCTAssertEqual(offer?.version, SemVer("0.3.0-beta.1"))
        XCTAssertEqual(offer?.prerelease, true)
    }

    func testOrdersBySemanticVersionNotBuildNumber() async {
        // A beta built from a longer branch can carry a higher build number than the final release.
        let releases = [release("v0.3.0"), release("v0.3.0-beta.2", prerelease: true)]
        let offer = await select(releases, beta: true, installed: "0.3.0-beta.1", builds: ["v0.3.0": 500, "v0.3.0-beta.2": 900])
        XCTAssertEqual(offer?.version, SemVer("0.3.0"))
    }

    func testOffersOnlyANewerVersion() async {
        let releases = [release("v0.2.0")]
        let same = await select(releases, beta: false, installed: "0.2.0")
        XCTAssertNil(same)
        let local = await select(releases, beta: false, installed: "0.2.0+268.c5b81ac")
        XCTAssertNil(local, "build metadata doesn't make a version newer or older")
        let candidate = await select(releases, beta: false, installed: "0.2.0-rc.1")
        XCTAssertEqual(candidate?.version, SemVer("0.2.0"))
    }

    func testSkipsDraftsMissingManifestsAndBrokenOrNonSemverManifests() async {
        let releases = [release("v9.0.0", draft: true), release("v8.0.0", manifest: false), release("vbroken"), release("vnext"), release("v0.2.0")]
        let offer = await select(releases, beta: true, installed: "0.1.0")
        XCTAssertEqual(offer?.version, SemVer("0.2.0"))
    }

    /// Serves like GitHub: the list returns at most 20 releases (newest first), and "latest" the
    /// newest stable one, or 404 when there is none.
    private func github(_ releases: [AppUpdates.Release], status: Int? = nil) -> (URL) async throws -> Data {
        { url in
            if let status { throw AppUpdates.HTTPError(status: status) }
            func json(_ release: AppUpdates.Release) -> [String: Any] {
                ["tag_name": release.tagName, "draft": release.draft, "prerelease": release.prerelease,
                 "assets": release.assets.map { ["name": $0.name, "browser_download_url": $0.browserDownloadURL.absoluteString] }]
            }
            if url.path.hasSuffix("/releases/latest") {
                guard let latest = releases.first(where: { !$0.draft && !$0.prerelease }) else {
                    throw AppUpdates.HTTPError(status: 404)
                }
                return try JSONSerialization.data(withJSONObject: json(latest))
            }
            return try JSONSerialization.data(withJSONObject: releases.prefix(20).map(json))
        }
    }

    func testARunOfBetasCannotHideTheLatestStableRelease() async throws {
        let betas = (1...25).reversed().map { release("v0.3.0-beta.\($0)", prerelease: true) }
        let releases = try await AppUpdates.releases(repo: "o/r", fetch: github(betas + [release("v0.2.0")]))
        let stable = await select(releases, beta: false, installed: "0.1.0")
        XCTAssertEqual(stable?.version, SemVer("0.2.0"))
        let beta = await select(releases, beta: true, installed: "0.1.0")
        XCTAssertEqual(beta?.version, SemVer("0.3.0-beta.25"))
    }

    func testAHotfixPublishedLastDoesNotHideANewerRelease() async throws {
        // GitHub marks the release published last as "latest", even an older line's hotfix.
        let releases = try await AppUpdates.releases(repo: "o/r", fetch: github([release("v0.1.9"), release("v0.2.0")]))
        let offer = await select(releases, beta: false, installed: "0.1.0")
        XCTAssertEqual(offer?.version, SemVer("0.2.0"))
    }

    func testNoStableReleaseYetIsNotAnErrorButOtherFailuresAre() async throws {
        let releases = try await AppUpdates.releases(repo: "o/r", fetch: github([release("v0.1.0-beta.1", prerelease: true)]))
        XCTAssertEqual(releases.map(\.tagName), ["v0.1.0-beta.1"])
        do {
            _ = try await AppUpdates.releases(repo: "o/r", fetch: github([], status: 403))
            XCTFail("a rate limit must be reported")
        } catch let error as AppUpdates.HTTPError {
            XCTAssertEqual(error.status, 403)
        }
    }

    func testSemVerPrecedence() {
        // The example ordering from semver.org §11, plus our own stages and a patch and major step.
        let ordered = ["1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
                       "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0"].map { SemVer($0)! }
        XCTAssertEqual(ordered.shuffled().sorted(), ordered)
        for (a, b) in zip(ordered, ordered.dropFirst()) { XCTAssertLessThan(a, b) }
        XCTAssertEqual(SemVer("v0.2.0-beta.1"), SemVer(major: 0, minor: 2, patch: 0, preRelease: ["beta", "1"]))
        XCTAssertEqual(SemVer("0.1.0+268.c5b81ac"), SemVer("0.1.0"))
        XCTAssertEqual(SemVer("0.2.0-beta.1+5")?.description, "0.2.0-beta.1")
        for bad in ["1.2", "1.2.3.4", "01.2.3", "1.2.3-", "1.2.3-beta.01", "next", ""] {
            XCTAssertNil(SemVer(bad), bad)
        }
    }

    func testDecodesGitHubReleases() throws {
        let json = """
        [{"tag_name":"v0.2.0","draft":false,"prerelease":true,"name":"x","assets":[{"name":"a.zip","browser_download_url":"https://example.com/a.zip","size":3}]}]
        """
        let releases = try AppUpdates.decodeReleases(Data(json.utf8))
        XCTAssertEqual(releases.first?.tagName, "v0.2.0")
        XCTAssertEqual(releases.first?.prerelease, true)
        XCTAssertEqual(releases.first?.assets.first?.browserDownloadURL.absoluteString, "https://example.com/a.zip")
        XCTAssertThrowsError(try AppUpdates.decodeManifest(Data("{}".utf8)))
    }

    func testSHA256OfAFile() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: url) }
        try Data("abc".utf8).write(to: url)
        XCTAssertEqual(try AppUpdates.sha256Hex(ofFileAt: url), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    func testVerifyBundleRejectsNonBundlesAndOtherApps() throws {
        var requirement: SecRequirement?
        XCTAssertEqual(SecRequirementCreateWithString("anchor apple" as CFString, [], &requirement), errSecSuccess)
        let empty = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        XCTAssertThrowsError(try AppUpdates.verifyBundle(at: empty, bundleIdentifier: "x", installedBuild: 0, requirement: requirement!))
        // A real, Apple-signed app with a different identifier.
        let calculator = URL(fileURLWithPath: "/System/Applications/Calculator.app")
        XCTAssertThrowsError(try AppUpdates.verifyBundle(at: calculator, bundleIdentifier: "dev.southstar21.droidtosh.mac", installedBuild: 0, requirement: requirement!)) { error in
            XCTAssertEqual(error as? AppUpdates.VerificationError, .wrongBundleIdentifier("com.apple.calculator"))
        }
        // The right identifier, a valid signature, but not the pinned signer.
        var droidtosh: SecRequirement?
        XCTAssertEqual(SecRequirementCreateWithString("identifier \"com.apple.calculator\" and certificate leaf = H\"0000000000000000000000000000000000000000\"" as CFString, [], &droidtosh), errSecSuccess)
        XCTAssertThrowsError(try AppUpdates.verifyBundle(at: calculator, bundleIdentifier: "com.apple.calculator", installedBuild: 0, requirement: droidtosh!)) { error in
            guard case .signature? = error as? AppUpdates.VerificationError else { return XCTFail("\(error)") }
        }
        XCTAssertNoThrow(try AppUpdates.verifyBundle(at: calculator, bundleIdentifier: "com.apple.calculator", installedBuild: 0, requirement: requirement!))
    }
}

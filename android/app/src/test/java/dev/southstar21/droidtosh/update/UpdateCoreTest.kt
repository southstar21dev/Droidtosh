package dev.southstar21.droidtosh.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

// Robolectric only for a real org.json; the code under test has no Android dependencies otherwise.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCoreTest {
    private val repo = "owner/repo"
    private val sha = "ab".repeat(32)

    private fun release(
        tag: String,
        build: Int?,
        prerelease: Boolean = false,
        draft: Boolean = false,
        withApk: Boolean = true,
    ): String {
        val version = tag.removePrefix("v")
        val apk = "Droidtosh-$version-$build.apk"
        val assets = buildList {
            if (build != null) add(asset("droidtosh-update.json", "https://dl/$tag/droidtosh-update.json"))
            if (withApk) add(asset(apk, "https://dl/$tag/$apk"))
        }
        return """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":[${assets.joinToString(",")}]}"""
    }

    private fun asset(name: String, url: String) = """{"name":"$name","browser_download_url":"$url","size":1234}"""

    private fun manifest(version: String, build: Int) =
        """{"version":"$version","build":$build,"commit":"abc1234",""" +
            """"android":{"file":"Droidtosh-$version-$build.apk","sha256":"$sha"},""" +
            """"mac":{"file":"Droidtosh-$version-$build-mac.zip","sha256":"$sha"}}"""

    /** Serves the release list and each tag's manifest; [manifests] maps tag → manifest JSON. */
    /**
     * Serves like GitHub: the list returns at most [listLimit] releases (newest first), and
     * `/releases/latest` the newest stable one, or 404 when there is none.
     */
    private fun http(releases: List<String>, manifests: Map<String, String>, listLimit: Int = 20) = UpdateHttp { url ->
        if (url == UpdateFormat.releasesUrl(repo)) return@UpdateHttp "[${releases.take(listLimit).joinToString(",")}]"
        if (url == UpdateFormat.latestReleaseUrl(repo)) {
            return@UpdateHttp releases.firstOrNull { "\"draft\":false,\"prerelease\":false" in it }
                ?: throw UpdateHttpException(404)
        }
        val tag = url.removePrefix("https://dl/").substringBefore('/')
        manifests[tag] ?: throw IOException("404 $url")
    }

    private fun checker(releases: List<String>, manifests: Map<String, String>) =
        UpdateChecker(repo, http(releases, manifests))

    @Test
    fun stableChannelSkipsPrereleases() {
        val releases = listOf(release("v0.3.0-beta.1", 330, prerelease = true), release("v0.2.0", 320))
        val manifests = mapOf("v0.3.0-beta.1" to manifest("0.3.0-beta.1", 330), "v0.2.0" to manifest("0.2.0", 320))

        val stable = checker(releases, manifests).check(SemVer.parse("0.1.0")!!, beta = false)
        assertEquals("0.2.0", stable?.version)
        assertEquals("https://dl/v0.2.0/Droidtosh-0.2.0-320.apk", stable?.apk?.downloadUrl)

        val beta = checker(releases, manifests).check(SemVer.parse("0.1.0")!!, beta = true)
        assertEquals("0.3.0-beta.1", beta?.version)
        assertEquals(330, beta?.build)
    }

    @Test
    fun skipsDraftsAndReleasesWithoutManifest() {
        val releases = listOf(
            release("v0.4.0", 340, draft = true),
            release("v0.3.0", null),
            release("v0.2.0", 320),
        )
        val manifests = mapOf("v0.4.0" to manifest("0.4.0", 340), "v0.2.0" to manifest("0.2.0", 320))
        assertEquals("0.2.0", checker(releases, manifests).check(SemVer.parse("0.1.0")!!, beta = true)?.version)
    }

    @Test
    fun offersOnlyANewerVersion() {
        val releases = listOf(release("v0.2.0", 320))
        val manifests = mapOf("v0.2.0" to manifest("0.2.0", 320))
        assertNull(checker(releases, manifests).check(SemVer.parse("0.2.0")!!, beta = false))
        assertNull(checker(releases, manifests).check(SemVer.parse("0.3.0")!!, beta = false))
        // Build metadata on a local build doesn't count; a pre-release is older than its release.
        assertNull(checker(releases, manifests).check(SemVer.parse("0.2.0+268.c5b81ac")!!, beta = false))
        assertEquals("0.2.0", checker(releases, manifests).check(SemVer.parse("0.2.0-rc.1")!!, beta = false)?.version)
    }

    @Test
    fun ordersBySemanticVersionNotBuildNumber() {
        // A beta built from a longer branch can carry a higher build number than the final release.
        val releases = listOf(release("v0.3.0", 500), release("v0.3.0-beta.2", 900, prerelease = true))
        val manifests = mapOf("v0.3.0" to manifest("0.3.0", 500), "v0.3.0-beta.2" to manifest("0.3.0-beta.2", 900))
        val offer = checker(releases, manifests).check(SemVer.parse("0.3.0-beta.1")!!, beta = true)
        assertEquals("0.3.0", offer?.version)
    }

    @Test
    fun skipsAManifestWhoseVersionIsNotSemver() {
        val releases = listOf(release("vnext", 999), release("v0.2.0", 320))
        val manifests = mapOf("vnext" to manifest("next", 999), "v0.2.0" to manifest("0.2.0", 320))
        assertEquals("0.2.0", checker(releases, manifests).check(SemVer.parse("0.1.0")!!, beta = false)?.version)
    }

    @Test
    fun picksTheHighestVersionNotTheNewestRelease() {
        // A hotfix of an older line published after a newer release.
        val releases = listOf(release("v0.1.9", 310), release("v0.2.0", 320))
        val manifests = mapOf("v0.1.9" to manifest("0.1.9", 310), "v0.2.0" to manifest("0.2.0", 320))
        assertEquals("0.2.0", checker(releases, manifests).check(SemVer.parse("0.1.0")!!, beta = false)?.version)
    }

    @Test
    fun skipsAReleaseWhoseApkIsMissingOrManifestIsBroken() {
        val releases = listOf(release("v0.3.0", 330, withApk = false), release("v0.2.5", 325), release("v0.2.0", 320))
        val manifests = mapOf(
            "v0.3.0" to manifest("0.3.0", 330),
            "v0.2.5" to """{"version":"0.2.5"}""",
            "v0.2.0" to manifest("0.2.0", 320),
        )
        assertEquals("0.2.0", checker(releases, manifests).check(SemVer.parse("0.1.0")!!, beta = false)?.version)
    }

    @Test
    fun aRunOfBetasCannotHideTheLatestStableRelease() {
        // 25 betas published after 0.2.0 push it out of the 20-entry release list.
        val betas = (25 downTo 1).map { release("v0.3.0-beta.$it", 300 + it, prerelease = true) }
        val releases = betas + release("v0.2.0", 320)
        val manifests = (1..25).associate { "v0.3.0-beta.$it" to manifest("0.3.0-beta.$it", 300 + it) } +
            ("v0.2.0" to manifest("0.2.0", 320))
        assertEquals("0.2.0", checker(releases, manifests).check(SemVer.parse("0.1.0")!!, beta = false)?.version)
        assertEquals("0.3.0-beta.25", checker(releases, manifests).check(SemVer.parse("0.1.0")!!, beta = true)?.version)
    }

    @Test
    fun noStableReleaseYetIsNotAnError() {
        val releases = listOf(release("v0.1.0-beta.1", 101, prerelease = true))
        val manifests = mapOf("v0.1.0-beta.1" to manifest("0.1.0-beta.1", 101))
        assertNull(checker(releases, manifests).check(SemVer.parse("0.0.1")!!, beta = false))
        assertEquals("0.1.0-beta.1", checker(releases, manifests).check(SemVer.parse("0.0.1")!!, beta = true)?.version)
    }

    @Test
    fun otherHttpErrorsStillFail() {
        val failing = UpdateChecker(repo) { throw UpdateHttpException(403) }
        assertThrows(UpdateHttpException::class.java) { failing.check(SemVer.parse("0.1.0")!!, beta = false) }
    }

    @Test
    fun malformedReleaseListThrows() {
        val broken = UpdateChecker(repo) { """{"message":"Not Found"}""" }
        assertThrows(IOException::class.java) { broken.check(SemVer.parse("0.1.0")!!, beta = false) }
        assertThrows(IOException::class.java) { UpdateFormat.parseReleases("not json") }
    }

    @Test
    fun parsesTheManifest() {
        val parsed = UpdateFormat.parseManifest(manifest("0.2.0-beta.1", 312))
        assertEquals("0.2.0-beta.1", parsed.version)
        assertEquals(312, parsed.build)
        assertEquals("abc1234", parsed.commit)
        assertEquals(ManifestFile("Droidtosh-0.2.0-beta.1-312.apk", sha), parsed.android)
        assertEquals("Droidtosh-0.2.0-beta.1-312-mac.zip", parsed.mac?.file)
    }

    @Test
    fun rejectsAManifestWithABadChecksumOrMissingFields() {
        assertThrows(IOException::class.java) {
            UpdateFormat.parseManifest("""{"version":"1","build":2,"android":{"file":"a.apk","sha256":"xyz"}}""")
        }
        assertThrows(IOException::class.java) { UpdateFormat.parseManifest("""{"build":2}""") }
        // A Mac-only manifest parses, but offers nothing to the phone.
        val macOnly = UpdateFormat.parseManifest("""{"version":"1","build":2}""")
        assertNull(macOnly.android)
    }

    @Test
    fun sha256OfKnownInput() {
        val progress = mutableListOf<Long>()
        val digest = Sha256.of("abc".byteInputStream()) { progress += it }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
        assertEquals(listOf(3L), progress)
        assertTrue(Sha256.matches(digest, digest.uppercase()))
        assertFalse(Sha256.matches(digest, sha))
    }
}

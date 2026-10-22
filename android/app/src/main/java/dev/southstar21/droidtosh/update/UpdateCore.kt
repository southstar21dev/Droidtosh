package dev.southstar21.droidtosh.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/*
 * The over-the-air update rules from docs/RELEASING.md, free of Android framework types so they run in
 * plain JVM tests: read the GitHub release list and each release's droidtosh-update.json, and pick the
 * newest build the user may install.
 */

/** A file attached to a GitHub release. */
data class ReleaseAsset(val name: String, val downloadUrl: String, val size: Long)

/** One entry of `GET /repos/<repo>/releases`. */
data class Release(
    val tag: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
) {
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

data class ManifestFile(val file: String, val sha256: String)

/** `droidtosh-update.json`. */
data class UpdateManifest(
    val version: String,
    val build: Int,
    val commit: String,
    val android: ManifestFile?,
    val mac: ManifestFile?,
)

/** An update this phone can install: the release, its manifest (with its parsed version) and the APK asset. */
data class UpdateCandidate(
    val release: Release,
    val manifest: UpdateManifest,
    val apk: ReleaseAsset,
    val semVer: SemVer,
) {
    val version: String get() = manifest.version
    val build: Int get() = manifest.build
}

/** Fetches a URL's body as text; throws [UpdateHttpException] on an HTTP error, [IOException] on others. */
fun interface UpdateHttp {
    fun get(url: String): String
}

/** The server answered with a non-2xx [status]. */
class UpdateHttpException(val status: Int) : IOException("HTTP $status")

object UpdateFormat {
    const val MANIFEST_ASSET = "droidtosh-update.json"

    fun releasesUrl(repo: String): String = "https://api.github.com/repos/$repo/releases?per_page=20"

    /** The newest release that is neither a draft nor a pre-release, however many betas followed it. */
    fun latestReleaseUrl(repo: String): String = "https://api.github.com/repos/$repo/releases/latest"

    /** Parses the release list; throws [IOException] when it isn't one. */
    fun parseReleases(json: String): List<Release> = try {
        val array = JSONArray(json)
        (0 until array.length()).map { index -> release(array.getJSONObject(index)) }
    } catch (error: JSONException) {
        throw IOException("unexpected release list", error)
    }

    /** Parses one release (`/releases/latest`); throws [IOException] when it isn't one. */
    fun parseRelease(json: String): Release = try {
        release(JSONObject(json))
    } catch (error: JSONException) {
        throw IOException("unexpected release", error)
    }

    private fun release(json: JSONObject): Release {
        val assets = json.optJSONArray("assets") ?: JSONArray()
        return Release(
            tag = json.getString("tag_name"),
            draft = json.optBoolean("draft", false),
            prerelease = json.optBoolean("prerelease", false),
            assets = (0 until assets.length()).map { i ->
                val asset = assets.getJSONObject(i)
                ReleaseAsset(
                    name = asset.getString("name"),
                    downloadUrl = asset.getString("browser_download_url"),
                    size = asset.optLong("size", -1L),
                )
            },
        )
    }

    /** Parses `droidtosh-update.json`; throws [IOException] when it is malformed. */
    fun parseManifest(json: String): UpdateManifest = try {
        val root = JSONObject(json)
        UpdateManifest(
            version = root.getString("version"),
            build = root.getInt("build"),
            commit = root.optString("commit", ""),
            android = root.optJSONObject("android")?.let(::manifestFile),
            mac = root.optJSONObject("mac")?.let(::manifestFile),
        )
    } catch (error: JSONException) {
        throw IOException("malformed update manifest", error)
    }

    private fun manifestFile(json: JSONObject): ManifestFile {
        val sha256 = json.getString("sha256").lowercase()
        if (!sha256.matches(SHA256_PATTERN)) throw JSONException("bad sha256")
        return ManifestFile(file = json.getString("file"), sha256 = sha256)
    }

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
}

/** Picks the update to offer (docs/RELEASING.md). */
object UpdateSelector {
    /** Releases that may hold an update: no drafts, betas only when wanted, and a manifest attached. */
    fun eligible(releases: List<Release>, beta: Boolean): List<Release> =
        releases.filter { !it.draft && (beta || !it.prerelease) && it.asset(UpdateFormat.MANIFEST_ASSET) != null }

    /**
     * The highest version above [installed] (semantic version order) whose APK is attached, or null. A
     * manifest whose version isn't a semantic version is skipped.
     */
    fun choose(candidates: List<Pair<Release, UpdateManifest>>, installed: SemVer): UpdateCandidate? =
        candidates
            .mapNotNull { (release, manifest) ->
                val version = SemVer.parse(manifest.version) ?: return@mapNotNull null
                val apk = manifest.android?.let { release.asset(it.file) } ?: return@mapNotNull null
                UpdateCandidate(release, manifest, apk, version)
            }
            .filter { it.semVer > installed }
            .maxByOrNull { it.semVer }
}

/**
 * Runs one check (docs/RELEASING.md): the recent release list plus the latest stable release, which
 * GitHub returns however many betas followed it (the list alone can lose it behind 20 betas, and
 * "latest" alone can be an older line's hotfix published last). Then it reads the manifests of the
 * newest eligible releases.
 */
class UpdateChecker(private val repo: String, private val http: UpdateHttp) {
    fun check(installed: SemVer, beta: Boolean): UpdateCandidate? {
        val releases = (listOfNotNull(latestStable()) + UpdateFormat.parseReleases(http.get(UpdateFormat.releasesUrl(repo))))
            .distinctBy { it.tag }
        val withManifests = UpdateSelector.eligible(releases, beta)
            .take(MAX_MANIFESTS)
            .mapNotNull { release ->
                val asset = release.asset(UpdateFormat.MANIFEST_ASSET) ?: return@mapNotNull null
                // One broken manifest shouldn't hide the other releases.
                runCatching { release to UpdateFormat.parseManifest(http.get(asset.downloadUrl)) }.getOrNull()
            }
        return UpdateSelector.choose(withManifests, installed)
    }

    /** The latest stable release, or null when there is none yet (GitHub answers 404). */
    private fun latestStable(): Release? = try {
        UpdateFormat.parseRelease(http.get(UpdateFormat.latestReleaseUrl(repo)))
    } catch (error: UpdateHttpException) {
        if (error.status == 404) null else throw error
    }

    private companion object {
        /** The newest few eligible releases; the highest version is almost always the newest. */
        const val MAX_MANIFESTS = 5
    }
}

object Sha256 {
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Reads [input] to the end, reporting the bytes read so far, and returns its SHA-256 in hex. */
    fun of(input: InputStream, onProgress: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            total += read
            onProgress(total)
        }
        return hex(digest.digest())
    }

    fun matches(actual: String, expected: String): Boolean = actual.equals(expected, ignoreCase = true)
}

package dev.southstar21.droidtosh.calls

import android.app.Notification
import android.content.Context
import org.json.JSONObject
import java.util.Locale

data class VoipProfile(
    val packageName: String,
    val label: String,
    val acceptPatterns: List<String>,
    val declinePatterns: List<String>,
    val endPatterns: List<String>,
    val chatDeepLink: String? = null,
)

enum class VoipAction { ACCEPT, DECLINE, END }

object VoipProfiles {
    private const val ASSET_NAME = "voip_profiles.json"

    private fun parseProfile(json: JSONObject): VoipProfile = VoipProfile(
        packageName = json.getString("package"),
        label = json.optString("label", json.getString("package")),
        acceptPatterns = json.optJSONArray("accept")?.toStringList().orEmpty(),
        declinePatterns = json.optJSONArray("decline")?.toStringList().orEmpty(),
        endPatterns = json.optJSONArray("end")?.toStringList().orEmpty(),
        chatDeepLink = json.optString("chat_deep_link", "").ifEmpty { null },
    )

    fun load(context: Context): List<VoipProfile> = runCatching {
        val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val profiles = root.optJSONArray("profiles") ?: return emptyList()
        (0 until profiles.length()).map { parseProfile(profiles.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun generic(context: Context): VoipProfile = runCatching<VoipProfile?> {
        val raw = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val json = JSONObject(raw).optJSONObject("generic") ?: return@runCatching null
        VoipProfile(
            packageName = "*",
            label = "Generic",
            acceptPatterns = json.optJSONArray("accept")?.toStringList().orEmpty(),
            declinePatterns = json.optJSONArray("decline")?.toStringList().orEmpty(),
            endPatterns = json.optJSONArray("end")?.toStringList().orEmpty(),
        )
    }.getOrNull() ?: VoipProfile("*", "Generic", emptyList(), emptyList(), emptyList())

    private fun org.json.JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it).lowercase(Locale.ROOT) }
}

class VoipActionMatcher(
    profiles: List<VoipProfile>,
    private val generic: VoipProfile,
) {
    private val byPackage = profiles.associateBy { it.packageName }

    fun hasProfile(packageName: String): Boolean = byPackage.containsKey(packageName)

    /**
     * [allowGeneric] gates the cross-app generic table: words like "Accept" or "Dismiss" are only
     * call actions when the notification is otherwise known to be a call.
     */
    fun match(
        packageName: String,
        actions: List<Notification.Action>,
        allowGeneric: Boolean = true,
    ): Map<VoipAction, Int> {
        val profile = byPackage[packageName]
        val tables = listOfNotNull(profile, generic.takeIf { allowGeneric })
        val result = mutableMapOf<VoipAction, Int>()
        actions.forEachIndexed { index, action ->
            val title = action.title?.toString()?.lowercase(Locale.ROOT) ?: return@forEachIndexed
            if (title.isBlank()) return@forEachIndexed
            val role = roleFor(title, tables) ?: return@forEachIndexed
            result.putIfAbsent(role, index)
        }
        return result
    }

    private fun roleFor(title: String, tables: List<VoipProfile>): VoipAction? {
        for (table in tables) {
            if (table.acceptPatterns.any { matchesWord(title, it) }) return VoipAction.ACCEPT
            if (table.endPatterns.any { matchesWord(title, it) }) return VoipAction.END
            if (table.declinePatterns.any { matchesWord(title, it) }) return VoipAction.DECLINE
        }
        return null
    }

    internal companion object {
        /**
         * Whole-word match, so "end" doesn't hit "Send" or "Add to calendar". Scripts written without
         * spaces (CJK) can't be split into words and fall back to a substring match.
         */
        fun matchesWord(title: String, pattern: String): Boolean {
            if (pattern.isEmpty()) return false
            if (pattern.any { Character.isIdeographic(it.code) }) return title.contains(pattern)
            var from = title.indexOf(pattern)
            while (from >= 0) {
                val end = from + pattern.length
                val startsWord = from == 0 || !title[from - 1].isLetterOrDigit()
                val endsWord = end == title.length || !title[end].isLetterOrDigit()
                if (startsWord && endsWord) return true
                from = title.indexOf(pattern, from + 1)
            }
            return false
        }
    }
}

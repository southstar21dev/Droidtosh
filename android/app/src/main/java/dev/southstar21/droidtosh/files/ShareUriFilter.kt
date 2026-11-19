package dev.southstar21.droidtosh.files

import android.net.Uri

/**
 * Share targets are exported, so any app can hand them a URI. Only `content://` URIs from other
 * apps are accepted: `file://` paths would be read with Droidtosh's own permissions, and URIs
 * from this app's own providers (authorities start with the package name) expose its private data.
 */
object ShareUriFilter {
    fun isAllowed(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (!"content".equals(scheme, ignoreCase = true)) return false
        val host = authority?.substringAfterLast('@')?.lowercase() ?: return false
        if (host.isEmpty()) return false
        val own = ownPackage.lowercase()
        return host != own && !host.startsWith("$own.")
    }

    fun isAllowed(uri: Uri, ownPackage: String): Boolean = isAllowed(uri.scheme, uri.authority, ownPackage)
}

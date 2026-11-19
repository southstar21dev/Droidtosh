package dev.southstar21.droidtosh.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareUriFilterTest {
    private val pkg = "dev.southstar21.droidtosh"

    @Test
    fun allowsContentUrisFromOtherApps() {
        assertTrue(ShareUriFilter.isAllowed("content", "com.android.providers.media.documents", pkg))
        assertTrue(ShareUriFilter.isAllowed("content", "dev.southstar21.droidtosh2.files", pkg))
    }

    @Test
    fun rejectsFileAndOtherSchemes() {
        assertFalse(ShareUriFilter.isAllowed("file", "", pkg))
        assertFalse(ShareUriFilter.isAllowed("http", "example.com", pkg))
        assertFalse(ShareUriFilter.isAllowed(null, null, pkg))
    }

    @Test
    fun rejectsOwnProviders() {
        assertFalse(ShareUriFilter.isAllowed("content", "$pkg.clipboard", pkg))
        assertFalse(ShareUriFilter.isAllowed("content", "$pkg.diagnostics", pkg))
        assertFalse(ShareUriFilter.isAllowed("content", "$pkg.CLIPBOARD", pkg))
        assertFalse(ShareUriFilter.isAllowed("content", "user@$pkg.clipboard", pkg))
        assertFalse(ShareUriFilter.isAllowed("content", pkg, pkg))
        assertFalse(ShareUriFilter.isAllowed("CONTENT", null, pkg))
    }
}

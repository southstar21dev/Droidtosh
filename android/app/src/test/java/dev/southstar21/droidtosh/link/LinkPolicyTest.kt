package dev.southstar21.droidtosh.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPolicyTest {
    private fun web(url: String) = LinkPolicy.validate(url, allowTel = true)

    @Test
    fun acceptsHttpAndHttpsWithAHost() {
        assertEquals(LinkPolicy.Web("https://example.com/path?q=1", "example.com"), web("https://example.com/path?q=1"))
        assertEquals(LinkPolicy.Web("http://Example.COM:8080/", "example.com"), web("http://Example.COM:8080/"))
        assertEquals("example.com", web("HTTPS://example.com")?.display)
    }

    @Test
    fun rejectsOtherSchemes() {
        listOf(
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://contacts/people",
            "intent://scan/#Intent;scheme=zxing;end",
            "ftp://example.com/",
            "market://details?id=x",
            "example.com",
            "//example.com",
        ).forEach { assertNull(it, web(it)) }
    }

    @Test
    fun rejectsMissingHostAndUserInfo() {
        assertNull(web("https://"))
        assertNull(web("https:///path"))
        assertNull(web("https://user:pass@example.com/"))
        assertNull(web("https://google.com@evil.example/"))
    }

    @Test
    fun rejectsControlWhitespaceAndFormatCharacters() {
        assertNull(web("https://example.com/a b"))
        assertNull(web("https://example.com/\n"))
        assertNull(web("https://example.com/\u0000"))
        assertNull(web("https://example.com/\u007f"))
        assertNull(web("https://exam​ple.com/"))
        assertNull(web("https://example.com/‮fdp"))
        assertNull(web(" https://example.com/"))
        // Supplementary-plane format (a tag character) and private-use code points, as on the Mac.
        assertNull(web("https://example.com/\uDB40\uDC01"))
        assertNull(web("https://example.com/\uDB80\uDC00"))
        // A lone surrogate is not a character at all.
        assertNull(web("https://example.com/\uD800"))
    }

    @Test
    fun capsTheLengthInBytes() {
        val prefix = "https://example.com/"
        assertTrue(web(prefix + "a".repeat(LinkPolicy.MAX_BYTES - prefix.length)) != null)
        assertNull(web(prefix + "a".repeat(LinkPolicy.MAX_BYTES - prefix.length + 1)))
        // Multi-byte characters count by their UTF-8 size, not their length.
        assertNull(web(prefix + "é".repeat(LinkPolicy.MAX_BYTES / 2)))
    }

    @Test
    fun telIsOnlyForThePhoneAndMustLookLikeANumber() {
        assertEquals(LinkPolicy.Tel("tel:+49(170)123-4567", "+49(170)123-4567"), web("tel:+49(170)123-4567"))
        assertEquals("tel:*31#", LinkPolicy.validate("TEL:*31#", allowTel = true)?.url?.lowercase())
        assertNull(LinkPolicy.validate("tel:+491701234567", allowTel = false))
        assertNull(web("tel:"))
        assertNull(web("tel:abc"))
        assertNull(web("tel:+++"))
        assertNull(web("tel:123;ext=4"))
        assertNull(web("tel:" + "1".repeat(40)))
    }

    @Test
    fun singleWebUrlOnlyAcceptsOneLinkAndTrimsTheEnds() {
        assertEquals("https://example.com/x", LinkPolicy.singleWebUrl("  https://example.com/x\n"))
        assertNull(LinkPolicy.singleWebUrl("look at https://example.com/x"))
        assertNull(LinkPolicy.singleWebUrl("https://example.com/x https://example.org/"))
        assertNull(LinkPolicy.singleWebUrl("just some text"))
        assertNull(LinkPolicy.singleWebUrl("tel:+491701234567"))
        assertNull(LinkPolicy.singleWebUrl(""))
    }
}

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

    // The same corpus is in DroidsyncKit LinkPolicyTests.swift; keep the two lists identical.
    private val acceptedHosts = listOf(
        "https://example.com/" to "example.com",
        "HTTPS://EXAMPLE.com" to "example.com",
        "HtTp://x.y" to "x.y",
        "http://example.com:8080/x" to "example.com",
        "https://example.com?x=1" to "example.com",
        "https://example.com#frag" to "example.com",
        "https://example.com/@x" to "example.com",
        "https://example.com?x=a@b" to "example.com",
        "https://example.com/a:b" to "example.com",
        "https://BÜCHER.example/" to "bÜcher.example",
        "https://bücher.example/" to "bücher.example",
        "https://xn--bcher-kva.example/" to "xn--bcher-kva.example",
        "https://日本語.jp/" to "日本語.jp",
        "https://my_host.example/" to "my_host.example",
        "https://under_score/" to "under_score",
        "https://[::1]/" to "[::1]",
        "https://[2001:DB8::1]:8443/x" to "[2001:db8::1]",
        "https://[::ffff:1.2.3.4]" to "[::ffff:1.2.3.4]",
        "https://example.com:1/" to "example.com",
        "https://example.com:65535/" to "example.com",
        "https://example.com:00080/" to "example.com",
        "https://example.com./" to "example.com.",
        "https://192.168.0.1:3000/" to "192.168.0.1",
        "https://a-b.c-d/" to "a-b.c-d",
        "https://example.com/\u0301x" to "example.com",
        "https://example.com?\u0301" to "example.com",
        "https://example.com#\u20dd" to "example.com",
    )

    private val rejectedHosts = listOf(
        "https://",
        "https:///path",
        "https://?q=1",
        "https://#f",
        "https://:80/",
        "https://user:pass@example.com/",
        "https://google.com@evil.example/",
        "https://@example.com/",
        "https://[::1",
        "https://[]/",
        "https://[zz::1]/",
        "https://[::1]x/",
        "https://[1.2.3.4]/",
        "https://[::1]:/",
        "https://[::1]:0/",
        "https://example.com:0/",
        "https://example.com:65536/",
        "https://example.com:123456/",
        "https://example.com:/",
        "https://example.com:80a/",
        "https://example.com::80/",
        "https://example.com../",
        "https://.example.com/",
        "https://a..b/",
        "https://./",
        "https://ex!ample.com/",
        "https://example.com\\@evil.example/",
        "https://exa%6dple.com/",
        "https://ex*ample.com/",
        "https://😀.example/",
        "https://example。com/",
        "https://exa(mple.com/",
        "https://example.com\\path",
        "http\u017f://example.com/",
        "https:/\u0301/example.com/",
    )

    @Test
    fun hostRuleCorpusMatchesTheMac() {
        for ((url, host) in acceptedHosts) assertEquals(url, host, (web(url) as? LinkPolicy.Web)?.host)
        for (url in rejectedHosts) assertNull(url, web(url))
    }
}

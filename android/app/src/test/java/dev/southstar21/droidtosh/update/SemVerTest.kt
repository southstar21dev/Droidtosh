package dev.southstar21.droidtosh.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    private fun v(text: String) = SemVer.parse(text)!!

    @Test
    fun followsSemverPrecedence() {
        // The example ordering from semver.org §11, plus our own stages and a patch and major step.
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0",
        ).map(::v)
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sorted())
        for (i in 0 until ordered.size - 1) assertTrue("${ordered[i]} < ${ordered[i + 1]}", ordered[i] < ordered[i + 1])
    }

    @Test
    fun parsesTagsAndIgnoresBuildMetadata() {
        assertEquals(SemVer(0, 2, 0, listOf("beta", "1")), v("v0.2.0-beta.1"))
        assertEquals(0, v("0.1.0+268.c5b81ac").compareTo(v("0.1.0")))
        assertEquals("0.2.0-beta.1", v("0.2.0-beta.1+5").toString())
        assertTrue(v("0.2.0-beta.1").isPreRelease)
    }

    @Test
    fun rejectsWhatIsNotSemver() {
        listOf("1.2", "1.2.3.4", "01.2.3", "1.2.3-", "1.2.3-beta.01", "next", "", "1.2.3-be ta").forEach {
            assertNull(it, SemVer.parse(it))
        }
    }
}

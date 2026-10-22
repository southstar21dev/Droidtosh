package dev.southstar21.droidtosh.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test
    fun stripsPathTricks() {
        assertEquals("x", FileNames.sanitize("../x"))
        assertEquals("ab", FileNames.sanitize("a/b"))
        assertEquals("ab", FileNames.sanitize("a\\b"))
        assertEquals("ab", FileNames.sanitize("a:b"))
        assertEquals("etcpasswd", FileNames.sanitize("/etc/passwd"))
        assertEquals("file", FileNames.sanitize(".."))
        assertEquals("file", FileNames.sanitize("."))
        assertEquals("bashrc", FileNames.sanitize(".bashrc"))
        // One pass: trim, strip leading dots, trim (the Mac does the same).
        assertEquals(".x", FileNames.sanitize(" . .x"))
    }

    @Test
    fun removesControlAndBidiCharacters() {
        assertEquals("ab", FileNames.sanitize("a\u0000b"))
        assertEquals("ab", FileNames.sanitize("a\nb\u007F"))
        assertEquals("ab", FileNames.sanitize("a\u0085b"))
        assertEquals("ab", FileNames.sanitize("a b "))
        // "evil‮txt.exe" would display as "evilexe.txt".
        assertEquals("eviltxt.exe", FileNames.sanitize("evil‮txt.exe"))
        assertEquals("ab", FileNames.sanitize("‎a⁦b⁩‏"))
    }

    @Test
    fun normalizesToNfcBeforeCapping() {
        // "é" as e + U+0301 (as macOS file systems store it) becomes the single precomposed scalar.
        assertEquals("café.txt", FileNames.sanitize("café.txt"))
        val decomposed = "é".repeat(120) + ".md"
        assertEquals("é".repeat(98) + ".md", FileNames.sanitize(decomposed))
    }

    @Test
    fun keepsOtherPunctuation() {
        assertEquals("a*b?c\"d<e>f|g", FileNames.sanitize("a*b?c\"d<e>f|g"))
        assertEquals("name.", FileNames.sanitize("  name. "))
    }

    @Test
    fun fallsBackWhenNothingIsLeft() {
        assertEquals("file", FileNames.sanitize(""))
        assertEquals("file", FileNames.sanitize("   "))
        assertEquals("file", FileNames.sanitize("\u0001\u0002"))
        assertEquals("file", FileNames.sanitize("... "))
    }

    @Test
    fun capsLongNamesAndKeepsTheExtension() {
        val result = FileNames.sanitize("a".repeat(300) + ".pdf")
        assertEquals(200, result.toByteArray().size)
        assertTrue(result.endsWith(".pdf"))
    }

    @Test
    fun capsAtCharacterBoundaries() {
        // 4 UTF-8 bytes per emoji: 49 of them plus ".txt" is 200 bytes.
        val result = FileNames.sanitize("😀".repeat(80) + ".txt")
        assertEquals("😀".repeat(49) + ".txt", result)
    }

    @Test
    fun keepsUnicode() {
        assertEquals("Отчёт 2026.pdf", FileNames.sanitize("Отчёт 2026.pdf"))
        assertEquals("日本語.txt", FileNames.sanitize("日本語.txt"))
    }

    @Test
    fun treatsAnOverlongExtensionAsPartOfTheName() {
        val result = FileNames.sanitize("b".repeat(190) + "." + "x".repeat(30))
        assertEquals(200, result.toByteArray().size)
        assertTrue(result.startsWith("b".repeat(190) + "."))
    }

    @Test
    fun uniqueAddsNumberedSuffixes() {
        val taken = setOf("a.txt", "a (1).txt")
        assertEquals("a (2).txt", FileNames.unique("a.txt") { it in taken })
        assertEquals("b.txt", FileNames.unique("b.txt") { it in taken })
        assertEquals("noext (1)", FileNames.unique("noext") { it == "noext" })
        assertEquals("archive.tar (1).gz", FileNames.unique("archive.tar.gz") { it == "archive.tar.gz" })
    }
}

package dev.southstar21.droidtosh.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class FolderZipperTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun makeFolder(): File {
        val folder = File(temp.newFolder(), "Trip Photos")
        File(folder, "day 1/raw").mkdirs()
        File(folder, "empty").mkdirs()
        File(folder, "notes.txt").writeText("top")
        File(folder, "day 1/raw/img.dng").writeBytes(ByteArray(100_000) { (it % 251).toByte() })
        return folder
    }

    private fun entries(zip: File): Map<String, ByteArray> = ZipFile(zip).use { file ->
        file.entries().asSequence().associate { it.name to file.getInputStream(it).readBytes() }
    }

    @Test
    fun zipsTheFolderAtTheRootKeepingItsStructure() {
        val out = File(temp.newFolder(), FolderZipper.archiveName("Trip Photos"))
        FolderZipper.zip(LocalFolderNode(makeFolder()), out)
        assertEquals("Trip Photos.zip", out.name)
        val entries = entries(out)
        assertEquals(
            setOf("Trip Photos/day 1/raw/img.dng", "Trip Photos/empty/", "Trip Photos/notes.txt"),
            entries.keys,
        )
        assertArrayEquals("top".toByteArray(), entries.getValue("Trip Photos/notes.txt"))
        assertArrayEquals(ByteArray(100_000) { (it % 251).toByte() }, entries.getValue("Trip Photos/day 1/raw/img.dng"))
    }

    @Test
    fun separatorsAndDuplicateNamesCantEscapeOrCollide() {
        val node = object : FolderNode {
            override val name = "../evil"
            override val isDirectory = true
            override val modifiedAtMs = 0L
            override fun children() = listOf(leaf("a/b.txt", "1"), leaf("ab.txt", "2"), leaf("..", "3"))
            override fun open() = error("a folder")
        }
        val out = File(temp.newFolder(), "x.zip")
        FolderZipper.zip(node, out)
        // "..evil" is a plain name once the separator is gone; ".." alone falls back to "file".
        assertEquals(setOf("..evil/ab.txt", "..evil/ab (1).txt", "..evil/file"), entries(out).keys)
        assertEquals("..evil.zip", FolderZipper.archiveName("../evil"))
        assertEquals("folder.zip", FolderZipper.archiveName(".."))
    }

    private fun leaf(name: String, text: String) = object : FolderNode {
        override val name = name
        override val isDirectory = false
        override val modifiedAtMs = 0L
        override fun children() = emptyList<FolderNode>()
        override fun open() = text.byteInputStream()
    }

    @Test(expected = FolderTooLargeException::class)
    fun stopsOnceTheArchivePassesTheLimit() {
        FolderZipper.zip(LocalFolderNode(makeFolder()), File(temp.newFolder(), "big.zip"), maxSize = 1_000)
    }

    @Test
    fun failureOrCancelDeletesThePartialArchive() {
        val dir = temp.newFolder()
        val out = File(dir, "a.zip")
        runCatching { FolderZipper.zip(LocalFolderNode(makeFolder()), out, maxSize = 1_000) }
        assertFalse(out.exists())
        val cancelled = runCatching { FolderZipper.zip(LocalFolderNode(makeFolder()), out) { true } }
        assertEquals(FolderZipCancelledException::class, cancelled.exceptionOrNull()!!::class)
        assertFalse(out.exists())
    }
}

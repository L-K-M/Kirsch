package com.example.kirsch.capture

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class CapturePackageZipperTest {
    private fun readEntries(bytes: ByteArray): Map<String, String> {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().decodeToString()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun zipsPackagesWithDeterministicPathsAndSkipsPartials() {
        val root = Files.createTempDirectory("zipper").toFile()
        try {
            val b = root.resolve("capture-b").apply { mkdirs() }
            b.resolve("capture.json").writeText("{\"id\":\"b\"}")
            b.resolve("frames").apply { mkdirs() }
            b.resolve("frames/frame-00.json").writeText("meta-b0")
            b.resolve("frames/frame-00.i420.partial").writeText("incomplete")
            val a = root.resolve("capture-a").apply { mkdirs() }
            a.resolve("capture.json").writeText("{\"id\":\"a\"}")

            val out = ByteArrayOutputStream()
            val result = CapturePackageZipper.zip(listOf(b, a), out)
            val entries = readEntries(out.toByteArray())

            assertEquals(
                listOf(
                    "capture-a/capture.json",
                    "capture-b/capture.json",
                    "capture-b/frames/frame-00.json",
                ),
                entries.keys.toList(),
            )
            assertEquals("meta-b0", entries["capture-b/frames/frame-00.json"])
            assertEquals(3, result.entryCount)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptySourcesProduceEmptyZip() {
        val out = ByteArrayOutputStream()
        val result = CapturePackageZipper.zip(emptyList(), out)
        assertEquals(0, result.entryCount)
        assertEquals(0L, result.byteCount)
        assertEquals(0, readEntries(out.toByteArray()).size)
    }
}

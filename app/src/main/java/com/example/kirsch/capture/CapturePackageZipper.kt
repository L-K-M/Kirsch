package com.example.kirsch.capture

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Streams capture packages into a single zip so they can leave the device
 * through Storage Access Framework destinations. App-private
 * `Android/data` storage is not browsable on many devices, which otherwise
 * blocks the device-matrix workflow of copying packages off secondary
 * phones.
 */
object CapturePackageZipper {
    data class Result(val entryCount: Int, val byteCount: Long)

    /**
     * Writes each source directory as a top-level folder in the zip.
     * Entry order is deterministic (sorted by path) and in-progress
     * `.partial` files are excluded. Duplicate directories are deduplicated
     * by canonical path; distinct directories sharing a name are rejected
     * because their zip entries would collide.
     */
    fun zip(sources: List<File>, output: OutputStream): Result {
        val bases = sources.map(File::getCanonicalFile).distinctBy(File::getPath)
        val collidingNames = bases.groupBy(File::getName).filterValues { it.size > 1 }.keys
        require(collidingNames.isEmpty()) {
            "Source directories must have unique names; duplicates: $collidingNames"
        }
        var entries = 0
        var bytes = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            for (base in bases.sortedBy(File::getName)) {
                val files = base.walkTopDown()
                    .filter { it.isFile && !it.name.endsWith(".partial") }
                    .sortedBy { it.relativeTo(base).invariantSeparatorsPath }
                for (file in files) {
                    val name = "${base.name}/${file.relativeTo(base).invariantSeparatorsPath}"
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    entries++
                    bytes += file.length()
                }
            }
        }
        return Result(entries, bytes)
    }
}

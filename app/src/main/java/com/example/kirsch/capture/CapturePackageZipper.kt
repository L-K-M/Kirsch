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
    data class Source(
        val archivePath: String,
        val directory: File,
        val includedFiles: List<File>? = null,
        val contentOverrides: Map<String, ByteArray> = emptyMap(),
    )

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
        return zipNamed(bases.map { Source(it.name, it) }, output)
    }

    fun zipNamed(sources: List<Source>, output: OutputStream): Result {
        val canonical = sources.map { source ->
            source.copy(
                directory = source.directory.canonicalFile,
                includedFiles = source.includedFiles?.map(File::getCanonicalFile),
            )
        }
            .distinctBy { it.archivePath to it.directory.path }
        canonical.forEach { source ->
            require(source.archivePath.isNotBlank() && !source.archivePath.startsWith("/") &&
                source.archivePath.split('/').none { it.isBlank() || it == "." || it == ".." }) {
                "Archive paths must be normalized and relative"
            }
            require(source.directory.isDirectory) { "Source is not a directory: ${source.directory}" }
        }
        val collidingPaths = canonical.groupBy(Source::archivePath).filterValues { it.size > 1 }.keys
        require(collidingPaths.isEmpty()) { "Archive paths must be unique; duplicates: $collidingPaths" }
        var entries = 0
        var bytes = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            for (source in canonical.sortedBy(Source::archivePath)) {
                val base = source.directory
                val files = (source.includedFiles?.asSequence() ?: base.walkTopDown())
                    .filter {
                        it.isFile && !it.name.endsWith(".partial") && !it.name.contains(".partial.") &&
                            it.canonicalPath.startsWith(base.canonicalPath + File.separator)
                    }
                    .sortedBy { it.relativeTo(base).invariantSeparatorsPath }
                for (file in files) {
                    val relative = file.relativeTo(base).invariantSeparatorsPath
                    val name = "${source.archivePath}/$relative"
                    zip.putNextEntry(ZipEntry(name))
                    val override = source.contentOverrides[relative]
                    if (override != null) {
                        zip.write(override)
                    } else {
                        file.inputStream().use { input -> input.copyTo(zip) }
                    }
                    zip.closeEntry()
                    entries++
                    bytes += override?.size?.toLong() ?: file.length()
                }
            }
        }
        return Result(entries, bytes)
    }
}

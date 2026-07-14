package ch.lkmc.kirsch.scan

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

object ScanManifestStore {
    private val lock = Any()

    fun read(file: File): JSONObject = locked { JSONObject(file.readText()) }

    fun write(file: File, value: JSONObject) = locked { writeLocked(file, value) }

    fun update(file: File, transform: (JSONObject) -> Unit): JSONObject = locked {
        val value = JSONObject(file.readText())
        transform(value)
        writeLocked(file, value)
        value
    }

    fun <T> locked(action: () -> T): T = synchronized(lock, action)

    private fun writeLocked(destination: File, value: JSONObject) {
        check(destination.parentFile?.mkdirs() != false || destination.parentFile?.isDirectory == true)
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.partial")
        FileOutputStream(temporary).use { output ->
            output.write((value.toString(2) + "\n").toByteArray())
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

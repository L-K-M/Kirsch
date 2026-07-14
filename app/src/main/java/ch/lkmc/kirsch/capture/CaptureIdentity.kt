package ch.lkmc.kirsch.capture

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object CaptureIdentity {
    private val timestampFormat =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmssSSS'Z'")
            .withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC)

    fun normalizePrintId(input: String): String {
        val normalized = input
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
            .trimEnd('-')
        val nonEmpty = normalized.ifEmpty { "unassigned" }
        return if (nonEmpty.first().isLetter()) nonEmpty else "print-$nonEmpty"
    }

    fun captureId(instant: Instant, suffix: String): String {
        val safeSuffix = suffix
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "")
            .ifEmpty { "local" }
            .take(12)
        return "capture-${timestampFormat.format(instant).lowercase(Locale.ROOT)}-$safeSuffix"
    }
}

package com.aktcl.aron.backend.platform

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Injectable clock: every rule that depends on time reads it here, so tests can move time (docs/24 s3.8). */
fun interface AronClock {
    fun now(): Instant

    companion object {
        val SYSTEM: AronClock = AronClock { Instant.now() }
    }
}

private val RFC3339_MS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** RFC 3339 UTC with milliseconds and `Z` (docs/24 s3.1 item 5). */
fun Instant.wire(): String = RFC3339_MS.format(truncatedTo(ChronoUnit.MILLIS))

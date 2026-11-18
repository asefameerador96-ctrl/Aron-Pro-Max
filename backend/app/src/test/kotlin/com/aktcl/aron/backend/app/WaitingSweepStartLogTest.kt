package com.aktcl.aron.backend.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.aktcl.aron.backend.platform.RegistryDefaults
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-OPS-DIAGNOSE-API-01: the API says once at start-up, in its console, whether the waiting-item sweep is running (interval, batch) or switched off and by what, so an operator
 * reading ops-diagnose does not have to infer it from silence. One INFO line, no payload.
 */
class WaitingSweepStartLogTest : NightSyncHarness() {
    private fun startLines(job: com.aktcl.aron.backend.sync.WaitingChildSweepJob): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("aron.sync") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try { job.start() } finally { job.stop(); logger.detachAppender(appender) }
        return appender.list.toList().filter { it.formattedMessage.startsWith("waiting-item sweep") && !it.formattedMessage.startsWith("waiting-item sweep stopped") }
    }

    @Test
    fun enabledLogsIntervalAndBatchOnce() {
        val lines = startLines(waitingSweepJob(wiring, clock, fresh.url, firstDelayS = 3600, batch = 50))
        assertEquals(1, lines.size, lines.toString())
        assertEquals(Level.INFO, lines.single().level)
        assertTrue(Regex("""waiting-item sweep enabled \(interval \d+ s, batch 50\)""").matches(lines.single().formattedMessage), lines.single().formattedMessage)
    }

    @Test
    fun disabledByTheEnvironmentNamesTheEmergencyStop() {
        val lines = startLines(waitingSweepJob(wiring, clock, fresh.url, env = { if (it == "ARON_WAITING_SWEEP") "off" else null }, firstDelayS = 3600))
        assertEquals(1, lines.size, lines.toString())
        assertEquals(Level.INFO, lines.single().level)
        assertTrue(lines.single().formattedMessage.startsWith("waiting-item sweep disabled by ARON_WAITING_SWEEP=off"), lines.single().formattedMessage)
    }

    @Test
    fun disabledByConfigNamesTheKey() {
        val cfg = RegistryDefaults(overrides = mapOf("cfg.sync.waiting_sweep_enabled" to JsonPrimitive(false)))
        val lines = startLines(waitingSweepJob(wiring, clock, fresh.url, firstDelayS = 3600, config = cfg))
        assertEquals(1, lines.size, lines.toString())
        assertTrue(lines.single().formattedMessage.startsWith("waiting-item sweep disabled by cfg.sync.waiting_sweep_enabled"), lines.single().formattedMessage)
    }
}

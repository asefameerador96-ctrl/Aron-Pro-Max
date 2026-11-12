package com.aktcl.aron.backend.analytics

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.aktcl.aron.backend.platform.FreshDb
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A failed aggregation pass is diagnosable and carries no data (lane portal-loyalty-api, push A3): class, cause classes, SQLState and frames, never a message. */
class WorkerPassLogTest {
    private val marker = "ROWDATA-MARKER-7f3a"

    private fun <T> captured(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger("aron.analytics.worker") as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try { return block() to appender.list.toList() } finally { logger.detachAppender(appender) }
    }

    @Test
    fun aFailedPassLogsClassCauseSqlStateAndFramesButNeverTheMessage() {
        val fresh = FreshDb.create(migrate = false)
        try {
            val w = AggregationWorker(fresh.db)
            w.passBody = { throw IllegalStateException("outer $marker", java.sql.SQLException("inner $marker", "23505")) }
            val (_, events) = captured { w.pass() }
            assertEquals(1, events.size)
            val e = events.single()
            assertEquals(Level.ERROR, e.level)
            assertEquals(null, e.throwableProxy, "no throwable is attached: its message would be logged with it")
            val text = e.formattedMessage + " " + e.argumentArray?.joinToString { it.toString() }.orEmpty()
            assertFalse(text.contains(marker), text)
            assertTrue(text.contains("java.lang.IllegalStateException"), text)
            assertTrue(text.contains("java.sql.SQLException"), text)
            assertTrue(text.contains("sqlstate=23505"), text)
            assertTrue(text.contains("WorkerPassLogTest"), "a stack frame names the origin: $text")
        } finally { fresh.close() }
    }

    @Test
    fun anInterruptRestoresTheFlagAndAnErrorIsNotSwallowed() {
        val fresh = FreshDb.create(migrate = false)
        try {
            val w = AggregationWorker(fresh.db)
            w.passBody = { throw InterruptedException(marker) }
            val (_, events) = captured { w.pass() }
            assertTrue(Thread.interrupted(), "the interrupt flag is restored (and cleared here for the next test)")
            assertEquals(0, events.size)
            w.passBody = { throw OutOfMemoryError(marker) }
            var thrown: Throwable? = null
            captured { try { w.pass() } catch (t: Throwable) { thrown = t } }
            assertTrue(thrown is OutOfMemoryError, "an Error propagates")
        } finally { fresh.close() }
    }
}

package com.aktcl.aron.backend.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-OPS-MIGRATE-PAUSE-02: the deploy pauses the worker with ARON_WORKER_PAUSED=true on a revision; only that exact value pauses. */
class SettingsWorkerPausedTest {
    private val worker = mapOf("ARON_ROLE" to "worker", "ARON_DB_URL" to "jdbc:postgresql://localhost:5432/aron")

    @Test
    fun pausedOnlyWhenTheVariableIsTrue() {
        assertFalse(Settings.load(worker).workerPaused, "absent")
        assertFalse(Settings.load(worker + ("ARON_WORKER_PAUSED" to "")).workerPaused, "blank")
        assertFalse(Settings.load(worker + ("ARON_WORKER_PAUSED" to "false")).workerPaused, "false")
        assertFalse(Settings.load(worker + ("ARON_WORKER_PAUSED" to "1")).workerPaused, "1 is not true")
        assertTrue(Settings.load(worker + ("ARON_WORKER_PAUSED" to "true")).workerPaused)
        assertTrue(Settings.load(worker + ("ARON_WORKER_PAUSED" to " TRUE ")).workerPaused, "case and spaces")
    }

    @Test
    fun theFlagDoesNotChangeTheOtherRoles() {
        assertTrue(Settings.load(worker + ("ARON_ROLE" to "migrate") + ("ARON_WORKER_PAUSED" to "true")).workerPaused)
        assertFalse(Settings.load(worker + ("ARON_ROLE" to "migrate")).workerPaused)
    }
}

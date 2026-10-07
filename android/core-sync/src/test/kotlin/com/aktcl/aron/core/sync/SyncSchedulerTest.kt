package com.aktcl.aron.core.sync

import com.aktcl.aron.contract.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {
    @Test
    fun featureCodeCanRecordRequestsThroughTheInterface() {
        val seen = mutableListOf<Pair<Long, SyncTrigger>>()
        val scheduler = object : SyncScheduler {
            override fun requestSync(userId: Long, trigger: SyncTrigger) { seen += userId to trigger }
        }
        scheduler.requestSync(1001)
        scheduler.requestSync(1001, SyncTrigger.DAY_SUBMIT)
        SyncScheduler.None.requestSync(1001)
        assertEquals(listOf(1001L to SyncTrigger.WRITE_DEBOUNCE, 1001L to SyncTrigger.DAY_SUBMIT), seen)
    }
}

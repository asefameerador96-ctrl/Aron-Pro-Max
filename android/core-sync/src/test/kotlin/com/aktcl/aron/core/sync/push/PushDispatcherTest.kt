package com.aktcl.aron.core.sync.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PushDispatcherTest {
    private val shown = mutableListOf<PushNotice>()
    private val scheduled = mutableListOf<Pair<PushPullKind, Long>>()

    @Test fun aTaskPushShowsOneNoticeAndSchedulesOnePull() {
        val d = PushDispatcher({ shown += it }, { k, ms -> scheduled += k to ms }, Random(1))
        d.onMessage(mapOf("kind" to "sync_nudge", "reason" to "task_assigned", "pull_after_s" to "4"))
        assertEquals(listOf<PushNotice>(PushNotice.TaskAssigned), shown)
        assertEquals(listOf(PushPullKind.BUNDLE to 4_000L), scheduled)
    }

    @Test fun aNoticeThatCannotBeShownNeverStopsThePull() {
        val d = PushDispatcher({ throw SecurityException("notifications off") }, { k, ms -> scheduled += k to ms }, Random(1))
        d.onMessage(mapOf("kind" to "sync_nudge", "reason" to "task_assigned", "pull_after_s" to "0"))
        assertEquals(listOf(PushPullKind.BUNDLE to 0L), scheduled)
    }

    @Test fun anAnnouncementPullsNothingAndAnUnknownMessageDoesNothing() {
        val d = PushDispatcher({ shown += it }, { k, ms -> scheduled += k to ms }, Random(1))
        d.onMessage(mapOf("kind" to "announcement", "title_en" to "T", "body_en" to "B"))
        assertNull(d.onMessage(mapOf("kind" to "sync_now")))
        assertEquals(1, shown.size)
        assertTrue(scheduled.isEmpty())
    }
}

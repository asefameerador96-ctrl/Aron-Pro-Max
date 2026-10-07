package com.aktcl.aron.core.sync.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** N-038: the data keys the backend sends (notify/Push.kt, NotificationRequest) and what each one makes the phone do. */
class PushMessageTest {
    @Test fun aTaskNudgeShowsTheTaskNoticeAndPullsTheBundleOnly() {
        val m = PushMessage.parse(mapOf("kind" to "sync_nudge", "reason" to "task_assigned"))!!
        assertEquals(PushNotice.TaskAssigned, m.notice)
        assertEquals(setOf(PushPullKind.BUNDLE), m.pulls)
    }

    @Test fun aSyncNudgeForAnotherReasonPullsWithoutANotice() {
        val m = PushMessage.parse(mapOf("kind" to "sync_nudge", "reason" to "route_changed"))!!
        assertNull(m.notice)
        assertEquals(setOf(PushPullKind.BUNDLE), m.pulls)
    }

    @Test fun configAndBundlePullsAndTheDocs19Form() {
        assertEquals(setOf(PushPullKind.CONFIG), PushMessage.parse(mapOf("kind" to "config_pull"))!!.pulls)
        assertEquals(setOf(PushPullKind.BUNDLE), PushMessage.parse(mapOf("kind" to "bundle_pull"))!!.pulls)
        assertEquals(setOf(PushPullKind.CONFIG), PushMessage.parse(mapOf("type" to "cfg", "version" to "42", "pull_after_s" to "7"))!!.pulls)
    }

    /** F-SYS-073: a kill switch, min_version, blocked version or revert comes urgent and gets its own pull job. */
    @Test fun anUrgentConfigPushIsItsOwnPullKind() {
        assertEquals(setOf(PushPullKind.CONFIG_URGENT), PushMessage.parse(mapOf("kind" to "config_pull", "urgent" to "true"))!!.pulls)
        assertEquals(setOf(PushPullKind.CONFIG_URGENT), PushMessage.parse(mapOf("type" to "cfg", "version" to "42", "urgent" to "TRUE"))!!.pulls)
        assertEquals(setOf(PushPullKind.CONFIG), PushMessage.parse(mapOf("kind" to "config_pull", "urgent" to "false"))!!.pulls)
    }

    @Test fun anAnnouncementShowsItsTextInTheAppLanguageAndPullsNothing() {
        val m = PushMessage.parse(mapOf("kind" to "announcement", "title_en" to "Meeting", "body_en" to "At 9", "title_bn" to "সভা", "body_bn" to "৯টায়"))!!
        val n = m.notice as PushNotice.Announcement
        assertEquals("সভা", n.title(bangla = true)); assertEquals("৯টায়", n.body(bangla = true))
        assertEquals("Meeting", n.title(bangla = false))
        assertTrue(m.pulls.isEmpty())
        // No Bangla text given: English in a Bangla app rather than nothing.
        val en = PushMessage.parse(mapOf("kind" to "announcement", "title_en" to "Meeting", "body_en" to "At 9"))!!.notice as PushNotice.Announcement
        assertEquals("Meeting", en.title(bangla = true))
    }

    @Test fun unknownOrIncompleteMessagesAreIgnored() {
        assertNull(PushMessage.parse(emptyMap()))
        assertNull(PushMessage.parse(mapOf("kind" to "upload_now")))
        assertNull(PushMessage.parse(mapOf("kind" to "announcement", "title_en" to "x")))
        assertNull(PushMessage.parse(mapOf("type" to "something")))
    }

    @Test fun theServersPullAfterIsUsedButClampedToItsWindow() {
        val r = Random(1)
        assertEquals(37_000L, PushMessage.parse(mapOf("kind" to "bundle_pull", "pull_after_s" to "37"))!!.pullDelayMs(r))
        assertEquals(120_000L, PushMessage.parse(mapOf("kind" to "bundle_pull", "pull_after_s" to "99999"))!!.pullDelayMs(r))
        assertEquals(20_000L, PushMessage.parse(mapOf("kind" to "config_pull", "urgent" to "true", "pull_after_s" to "300"))!!.pullDelayMs(r))
        assertEquals(0L, PushMessage.parse(mapOf("kind" to "bundle_pull", "pull_after_s" to "-5"))!!.pullDelayMs(r))
    }

    @Test fun withoutPullAfterTheDelayIsSpreadOverTheJitterWindow() {
        val r = Random(3)
        val ordinary = PushMessage.parse(mapOf("kind" to "config_pull"))!!
        val task = PushMessage.parse(mapOf("kind" to "sync_nudge", "reason" to "task_assigned"))!!
        val urgent = PushMessage.parse(mapOf("kind" to "config_pull", "urgent" to "true"))!!
        val ordinaryDelays = (1..400).map { ordinary.pullDelayMs(r) }
        assertTrue(ordinaryDelays.all { it in 0..120_000L })
        // Spread, not one wave (T-2-55): the 1,000-phone fan-out lands over at least 100 s.
        assertTrue(ordinaryDelays.max() - ordinaryDelays.min() >= 100_000L)
        assertTrue((1..400).map { task.pullDelayMs(r) }.all { it in 0..20_000L })
        assertTrue((1..400).map { urgent.pullDelayMs(r) }.all { it in 0..20_000L })
    }
}

package com.aktcl.aron.core.sync.push

import kotlin.random.Random

/**
 * What a push does on the phone (N-038): show its notice, if it has one, and schedule the pulls it asks for after the
 * push's delay. Nothing else: no upload, no timer, no wake lock (docs/24 s4.7). A notice that fails to show never stops
 * the pull.
 */
class PushDispatcher(
    private val show: (PushNotice) -> Unit,
    private val schedule: (PushPullKind, Long) -> Unit,
    private val random: Random = Random.Default,
) {
    fun onMessage(data: Map<String, String>): PushMessage? {
        val message = PushMessage.parse(data) ?: return null
        message.notice?.let { notice -> try { show(notice) } catch (_: RuntimeException) { } }
        val delayMs = message.pullDelayMs(random)
        message.pulls.forEach { schedule(it, delayMs) }
        return message
    }
}

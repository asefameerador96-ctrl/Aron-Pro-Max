package com.aktcl.aron.tso

import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.session.SessionComponents

/** What the TSO host reads from the process: trusted time and the API client. Narrow on purpose, so the write paths are testable without a session. */
interface TsoEnv {
    val client: AronApiClient
    fun nowMs(): Long
    fun elapsedRealtimeMs(): Long
    fun bootCount(): Int
    fun clockOffsetMs(): Long?
    fun businessDate(): String
}

class SessionTsoEnv(private val components: SessionComponents) : TsoEnv {
    private val clock get() = components.trustedClock
    override val client: AronApiClient get() = components.apiClient
    override fun nowMs() = clock.nowMs()
    override fun elapsedRealtimeMs() = clock.elapsedRealtimeMs()
    override fun bootCount() = clock.bootCountNow()
    override fun clockOffsetMs() = clock.clockOffsetMs()
    override fun businessDate() = clock.businessDate().toString()
}

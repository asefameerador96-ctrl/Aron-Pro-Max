package com.aktcl.aron.tso

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.ActionGate
import com.aktcl.aron.core.common.GateClosedException
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.sync.SyncScheduler
import com.aktcl.aron.feature.tso.leave.LeavePayload
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Every TSO record enters through `TsoOutbox.enqueueAll`: it asks the Developer options gate at the moment it writes (D-OWNER-MDM-02). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TsoOutboxGateTest {
    private val user = 7L
    private lateinit var databases: UserDatabases
    private val env = object : TsoEnv {
        override val client: AronApiClient get() = error("offline")
        override fun nowMs() = 1_760_680_000_000L
        override fun elapsedRealtimeMs() = 5_000L
        override fun bootCount() = 3
        override fun clockOffsetMs(): Long? = 0L
        override fun businessDate() = "2026-10-17"
    }
    private val scheduler = object : SyncScheduler { override fun requestSync(userId: Long, trigger: SyncTrigger) = Unit }

    @Before fun open() { databases = UserDatabases(ApplicationProvider.getApplicationContext<Application>()) { null } }
    @After fun close() { ActionGate.check = { true }; ActionGate.onClosed = {}; databases.close(user) }

    private fun queue() = TsoReadModels(env, databases, TsoOutbox(env, databases, scheduler) { false }).leaveQueue(user)

    @Test fun aClosedGateRefusesAnApplicationAndQueuesNothing() = runBlocking {
        ActionGate.check = { false }
        val r = runCatching { queue().submit("6f1c0b9e-0000-4c4c-9a9a-0000000000b1", LeavePayload("casual", "2026-10-20", 1, "family")) }
        assertTrue(r.exceptionOrNull() is GateClosedException)
        assertEquals(0, databases.of(user).outboxDao().countInState(com.aktcl.aron.core.database.entity.OutboxState.PENDING))
    }

    @Test fun anOpenGateQueuesAsBefore() = runBlocking {
        assertTrue(queue().submit("6f1c0b9e-0000-4c4c-9a9a-0000000000b2", LeavePayload("casual", "2026-10-20", 1, "family")))
    }
}

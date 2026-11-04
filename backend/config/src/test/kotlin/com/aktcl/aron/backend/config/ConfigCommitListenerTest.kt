package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * tso-manual X4a fix round 4 (checker medium): [ConfigService.committed] hears every committed config version, with the keys
 * it touched, only AFTER its transaction has committed: another connection already sees the version when the listener runs
 * (the wiring's LatePolicyPush reads the policy and nudges phones from it, so it must never act on a change that could still
 * roll back). A change that waits for its approver, or one refused by validation, is not heard; its approval is.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigCommitListenerTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var svc: ConfigService

    @BeforeAll fun setUp() {
        env = SeededConfigDb()
        svc = ConfigService(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock)
    }
    @AfterAll fun tearDown() = env.close()

    /** Whether config version [v] is visible on a fresh JDBC connection (not the JDBI handle of the committing thread). */
    private fun committedElsewhere(v: Long): Boolean = env.fresh.dataSource.connection.use { c ->
        c.prepareStatement("SELECT count(*) FROM app.cfg_version WHERE config_version = ?").use { ps ->
            ps.setLong(1, v); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) == 1 }
        }
    }

    private fun change(key: String, value: JsonPrimitive) = ConfigChangeRequestIn("Pilot setting for the late policy", false, listOf(ConfigChangeItemIn(key, "global", 0, value)))

    @Test
    fun theListenerHearsEachCommittedVersionAfterItsCommitAndNothingElse() {
        data class Heard(val version: Long, val keys: Set<String>, val visibleElsewhere: Boolean)
        val heard = CopyOnWriteArrayList<Heard>()
        svc.committed = ConfigCommitListener { v, keys -> heard += Heard(v, keys, committedElsewhere(v)) }

        // C1: applied at once
        val c1 = svc.create(env.principal("admin1001", Role.ADMIN), change("cfg.geo.refresh_max", JsonPrimitive(2)), null, null)
        assertEquals("applied", c1.status)
        assertEquals(listOf(Heard(c1.config_version!!, setOf("cfg.geo.refresh_max"), true)), heard.toList())
        clock.advance(2)

        // C3 (the late-sale policy): nothing while it waits for the second SUPERADMIN; the approval commits it
        heard.clear()
        val key = "cfg.day.late_sync_after_final_policy"
        val c3 = svc.create(env.principal("superadmin1001", Role.SUPERADMIN), change(key, JsonPrimitive("quarantine")), null, null)
        assertEquals("pending_approval", c3.status)
        assertEquals(emptyList(), heard.toList(), "a waiting change is not heard")
        val approved = svc.decide(env.principal("superadmin1002", Role.SUPERADMIN), c3.change_id, ConfigDecisionIn("approve"), null)
        assertEquals("applied", approved.status)
        assertEquals(listOf(Heard(approved.config_version!!, setOf(key), true)), heard.toList(), "heard after the commit, with its key")
        clock.advance(2)

        // a change refused by validation commits nothing
        heard.clear()
        assertFailsWith<ApiProblem> { svc.create(env.principal("admin1001", Role.ADMIN), change("cfg.geo.refresh_max", JsonPrimitive(99)), null, null) }
        assertEquals(emptyList(), heard.toList())
    }
}

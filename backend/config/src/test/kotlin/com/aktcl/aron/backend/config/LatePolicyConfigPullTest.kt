package com.aktcl.aron.backend.config

import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * X4a ask 11 (D-TSO-MAN-57): a change of `cfg.day.late_sync_after_final_policy` sends ONE `config_pull` push to its scope,
 * after the commit; nothing else does, a replay of the same change does not send again, and a change that only waits does not.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LatePolicyConfigPullTest {
    private lateinit var env: SeededConfigDb
    private val clock = TestClock()
    private lateinit var svc: ConfigService
    private val pushes = mutableListOf<Set<String>>()
    private val admin get() = env.principal("admin1001", Role.ADMIN)
    private val sa1 get() = env.principal("superadmin1001", Role.SUPERADMIN)

    @BeforeAll fun setUp() {
        env = SeededConfigDb()
        svc = ConfigService(env.fresh.db, ConfigResolver(env.fresh.db, clock), clock)
        svc.committed = ConfigCommitListener { _, keys ->
            // After the commit: the new value is already readable (on another connection) when the listener runs.
            pushes += keys
            if (LATE in keys) assertTrue(env.one("SELECT count(*) FROM app.cfg_value WHERE key = '$LATE'")!!.toLong() >= 1)
        }
    }
    @AfterAll fun tearDown() = env.close()

    private val LATE = "cfg.day.late_sync_after_final_policy"
    private fun latePushes() = pushes.count { LATE in it }
    private val sa2 get() = env.principal("superadmin1002", Role.SUPERADMIN)

    private fun request(value: String, key: UUID? = null) =
        svc.create(sa1, ConfigChangeRequestIn("Late-sale policy switch for the test", false, listOf(ConfigChangeItemIn("cfg.day.late_sync_after_final_policy", "global", 0, JsonPrimitive(value)))), key, null)
            .also { clock.advance(2) }

    /** The policy is class C3: a SUPERADMIN requests, a second one approves; the approval is the commit. */
    private fun policy(value: String, key: UUID? = null): ConfigChangeDto {
        clock.setDhaka(12, 0)
        val c = request(value, key)
        return if (c.status == "pending_approval") svc.decide(sa2, c.change_id, ConfigDecisionIn("approve"), null).also { clock.advance(2) } else c
    }

    @Test
    fun aPolicyChangePushesOneConfigPullOnlyWhenItCommitsAndAReplayPushesNoSecond() {
        pushes.clear()
        clock.setDhaka(12, 0)
        val k = UUID.randomUUID()
        val pending = request("quarantine", k)
        assertEquals("pending_approval", pending.status)
        assertEquals(0, pushes.size, "a change that only waits for its approver sends nothing")
        val done = svc.decide(sa2, pending.change_id, ConfigDecisionIn("approve"), null)
        assertEquals("applied", done.status)
        assertEquals(listOf(setOf(LATE)), pushes, "exactly one commit heard, with the key the change wrote, after the commit")
        request("quarantine", k) // the same Idempotency-Key: the first answer, no second change
        runCatching { svc.decide(sa2, pending.change_id, ConfigDecisionIn("approve"), null) } // a second approval is refused (request state)
        assertEquals(1, latePushes(), "no replay is heard a second time")
        policy("accept_and_flag") // a return to the default is a change too
        assertEquals(2, latePushes())
    }

    @Test
    fun aRefusedApprovalPushesNothing() {
        pushes.clear()
        clock.setDhaka(8, 0) // inside the freeze window: the C3 approval is refused
        val c = request("reject")
        runCatching { svc.decide(sa2, c.change_id, ConfigDecisionIn("approve"), null) }
        assertEquals(0, pushes.size)
        svc.decide(sa1, c.change_id, ConfigDecisionIn("cancel"), null)
    }

    @Test
    fun anotherKeyPushesNothing() {
        pushes.clear()
        clock.setDhaka(12, 0)
        svc.create(sa1, ConfigChangeRequestIn("Tuning the pilot radius value", false, listOf(ConfigChangeItemIn("cfg.geo.radius_m", "outlet", env.one("SELECT id FROM app.outlet ORDER BY id LIMIT 1")!!.toLong(), JsonPrimitive(120)))), null, null)
        assertEquals(0, latePushes(), "another key is heard (as itself) but never as the late-sale policy")
    }

    @Test
    fun aRolledBackPolicyPushesToo() {
        pushes.clear()
        val c = policy("reject")
        assertEquals(1, latePushes())
        clock.setDhaka(12, 0)
        val r = svc.rollback(sa1, c.config_version!!, "revert_this_version", "Back to the previous late-sale policy", null)
        if (r.status == "pending_approval") svc.decide(sa2, r.change_id, ConfigDecisionIn("approve"), null)
        assertEquals(2, latePushes(), "the revert moves the policy back: its phones pull the CONFIG delta once more")
    }
}

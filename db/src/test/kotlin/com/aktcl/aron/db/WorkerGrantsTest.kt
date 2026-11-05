package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * D-DB-WORKER-GRANTS-01 (V0149): every table the worker's jobs write has a map row for worker_rw (and so for jobs_rw, the
 * login's group, which holds every worker_rw right). Found after train 78: bundle pre-generation could not INSERT into
 * app.bundle_snapshot and the outlet-request lapse job could not INSERT into app.outlet_request_event, silently. The fixture below is the audit of the worker's write statements (source file:line at 2026-11-05); a worker
 * write target needs its map row before the code lands, and this list gets its line in the same change.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkerGrantsTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() { db = TestPostgres.createDatabase().migrated() }

    @AfterAll
    fun tearDown() = db.close()

    /** table to privileges the worker's code issues, with the statement that needs them. */
    private val workerWrites: Map<String, Pair<List<String>, String>> = mapOf(
        "bundle_snapshot" to (listOf("INSERT", "DELETE") to "BundleService.kt:325 (pre-generation); DELETE is the 7-day retention grant of V0032, no code issues it yet"),
        "outlet_request_event" to (listOf("INSERT") to "OutletRequestLapseJob.kt:33"),
        "outlet_change_request" to (listOf("UPDATE") to "OutletRequestLapseJob.kt:30"),
        "client_error" to (listOf("DELETE") to "RetentionJob.kt:94"),
        "archive_manifest" to (listOf("INSERT", "UPDATE") to "RetentionJob.kt:77 (jobs_rw)"),
        "dirty_key" to (listOf("INSERT", "UPDATE", "DELETE") to "AggregationWorker.kt:147-190, BundlePreGeneration.kt:129"),
        "event_consumer" to (listOf("INSERT", "UPDATE") to "AggregationWorker.kt:46,69,257,290"),
        "route_day" to (listOf("INSERT", "UPDATE") to "RouteDayPlanning.kt:89, BundleService.kt:394-430, DayApi.kt:480-488 (closeLocked)"),
        "ingest_registry" to (listOf("DELETE") to "RetentionJob via app.prune_ingest_registry (invoker rights)"),
        "final_submit" to (listOf("INSERT") to "DayApi.kt:469 (AutoFinalSubmitJob via closeLocked)"),
        "domain_event" to (listOf("INSERT") to "DayApi.kt:507"),
        "risk_signal" to (listOf("INSERT", "UPDATE") to "RiskSignals.kt:157"),
        "visit" to (listOf("UPDATE") to "GeoRecheck.kt:68 (sweep)"),
        "report_export" to (listOf("UPDATE") to "ExportJobs.kt:65-93"),
        "user_notification" to (listOf("INSERT", "DELETE") to "Inbox.kt:81, InboxApi.kt:156 (prune)"),
    )

    @Test
    fun everyWorkerWriteTargetHasItsGrantForWorkerAndJobsRoles() = db.connect().use { c ->
        // archive_manifest is a jobs_rw-only right (V0051: the monthly archive job runs in the jobs container).
        for ((table, spec) in workerWrites) for (role in if (table == "archive_manifest") listOf("jobs_rw") else listOf("worker_rw", "jobs_rw")) for (priv in spec.first) {
            assertEquals("true", c.scalar("SELECT has_table_privilege('$role', 'app.$table', '$priv')::text"),
                "$role lacks $priv on app.$table (${spec.second})")
        }
    }

    @Test
    fun theWorkerMayRevokePushTokensAndNothingElseOnThem() = db.connect().use { c ->
        // Push.kt:180 (AutoFinalSubmitJob's PushNotifier): UPDATE of revoked_at and revoke_reason only.
        for (role in listOf("worker_rw", "jobs_rw")) for (col in listOf("revoked_at", "revoke_reason"))
            assertEquals("true", c.scalar("SELECT has_column_privilege('$role', 'app.push_token', '$col', 'UPDATE')::text"), "$role UPDATE $col")
    }

    @Test
    fun theAuditTrailsStayClosedToTheWorker() = db.connect().use { c ->
        // Not granted on purpose (V0149 header): the auto Final Submit job writes no audit row; security events are the API's.
        for (t in listOf("audit_log", "security_event")) for (role in listOf("worker_rw", "jobs_rw"))
            assertEquals("false", c.scalar("SELECT has_table_privilege('$role', 'app.$t', 'INSERT')::text"), "$role must not insert into app.$t")
    }
}

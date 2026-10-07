package com.aktcl.aron.backend.sync

import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.wire
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.jdbi.v3.core.Handle

/**
 * `consent_accept` (F-SYS-075, android-core-backend-consent-seed.md): one fact per user, policy, version and answer.
 * A phone that lost its local flag (TSO wipe, reinstall, new phone) asks again and sends a new client_uuid; that row is
 * acked `duplicate` with the first row's id, so Q44 and `fact_consent` count the user once. The bundle sends the
 * accepted versions back (`BundleUser.consents`) so the phone does not ask again.
 */
class ConsentRecords : RecordHandler {
    override val types = setOf("consent_accept")

    override fun sameAs(h: Handle, rec: IngestRecord): Long? {
        val key = (rec.payload["policy_key"] as? JsonPrimitive)?.content ?: return null
        val version = (rec.payload["policy_version"] as? JsonPrimitive)?.intOrNull ?: return null
        val accepted = (rec.payload["accepted"] as? JsonPrimitive)?.booleanOrNull ?: return null
        // Two phones of one user answering at once: one at a time per (user, policy, version), then the first stands.
        h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "consent:${rec.userId}:$key:$version")
        return h.createQuery(
            "SELECT id FROM app.user_consent WHERE user_id = :u AND policy_key = :k AND policy_version = :v AND accepted = :a AND voided_at IS NULL ORDER BY id LIMIT 1",
        ).bind("u", rec.userId).bind("k", key).bind("v", version).bind("a", accepted).mapTo(Long::class.java).findOne().orElse(null)
    }

    companion object {
        /** The user's accepted policy versions for the bundle, earliest acceptance per version. */
        fun accepted(h: Handle, userId: Long): List<BundleConsent> = h.createQuery(
            """
            SELECT policy_key, policy_version, min(captured_at) AS accepted_at FROM app.user_consent
            WHERE user_id = :u AND accepted AND voided_at IS NULL GROUP BY policy_key, policy_version ORDER BY policy_key, policy_version
            """.trimIndent(),
        ).bind("u", userId).map { rs, _ ->
            BundleConsent(rs.getString(1), rs.getInt(2), rs.getObject(3, java.time.OffsetDateTime::class.java).toInstant().wire())
        }.list()
    }
}

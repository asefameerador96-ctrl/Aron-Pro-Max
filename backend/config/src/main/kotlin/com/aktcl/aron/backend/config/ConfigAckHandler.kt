package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.contract.RecordOutcomeCode
import kotlinx.serialization.json.longOrNull
import org.jdbi.v3.core.Handle

/**
 * F-API-041: the `config_ack` record says which config version the phone applied. The generic writer stores the row in
 * `app.cfg_ack` (the reach view reads it); this handler (a) refuses a version the server never committed, as a
 * retryable `config_version_unknown` (the phone may have raced a failover; it resends), and (b) moves the device's
 * `config_version_applied` forward, never backward, so a late or reordered ack cannot lower it.
 */
class ConfigAckHandler : RecordHandler {
    override val types = setOf("config_ack")

    private fun applied(rec: IngestRecord): Long? = (rec.payload["config_version"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    override fun check(h: Handle, rec: IngestRecord): RecordRefusal? {
        val v = applied(rec) ?: return RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "config_version missing")
        if (v < 0) return RecordRefusal(RecordOutcomeCode.SCHEMA_INVALID, "config_version negative")
        if (v == 0L) return null
        val known = h.createQuery("SELECT EXISTS (SELECT 1 FROM app.cfg_version WHERE config_version = :v)").bind("v", v).mapTo(Boolean::class.java).one()
        return if (known) null else RecordRefusal(RecordOutcomeCode.CONFIG_VERSION_UNKNOWN, "config version $v is not committed on the server")
    }

    override fun afterStored(h: Handle, rec: IngestRecord, serverId: Long?) {
        val v = applied(rec) ?: return
        h.createUpdate("UPDATE app.device SET config_version_applied = :v WHERE id = :d AND COALESCE(config_version_applied, -1) < :v")
            .bind("v", v).bind("d", rec.deviceId).execute()
    }
}

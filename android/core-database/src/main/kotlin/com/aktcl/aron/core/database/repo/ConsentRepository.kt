package com.aktcl.aron.core.database.repo

import androidx.room.withTransaction
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.database.record.RecordMapping

/**
 * The employee-location notice (F-SYS-075, docs/21 s4.7, D-120) in the user's own database. Acceptance is a `sync_meta`
 * flag per policy version (never purged with synced rows) plus one `consent_accept` record, written in one transaction,
 * so a kill between the two cannot happen and a second tap queues nothing (uploaded once per user and policy version).
 */
class ConsentRepository(private val db: AronDatabase) {
    private val dao = db.referenceDao()

    /** When the notice of [version] was accepted on this phone (RFC 3339), or null. */
    suspend fun acceptedAt(version: Int, policyKey: String = LOCATION_NOTICE): String? = dao.meta(key(policyKey, version))

    /**
     * Stores the acceptance and queues its record; returns false (nothing written) when [version] was already accepted.
     * [stamp] carries trusted time, a fresh client uuid and the moment the notice was shown.
     */
    suspend fun accept(version: Int, locale: String, stamp: ConsentStamp, policyKey: String = LOCATION_NOTICE): Boolean = db.withTransaction {
        require(locale == "bn" || locale == "en") { "locale must be bn or en" }
        require(version >= 1) { "policy_version starts at 1" }
        val k = key(policyKey, version)
        if (dao.meta(k) != null) return@withTransaction false
        dao.putMeta(SyncMetaEntity(k, stamp.meta.capturedAt))
        db.outboxDao().insert(listOf(RecordMapping.consentAccept(stamp.clientUuid, stamp.meta, policyKey, version, locale, stamp.shownAt)))
        true
    }

    companion object {
        const val LOCATION_NOTICE = "location_notice"
        /** Config key (docs/24 s9, default true): with it, no sale starts before acceptance. */
        const val CFG_REQUIRED = "cfg.app.location_notice_required"
        private fun key(policyKey: String, version: Int) = "consent.$policyKey.v$version"
    }
}

/** What a `consent_accept` needs from the caller: a fresh client uuid, the capture envelope (trusted time) and `shown_at`. */
data class ConsentStamp(val clientUuid: String, val meta: CaptureMeta, val shownAt: String)

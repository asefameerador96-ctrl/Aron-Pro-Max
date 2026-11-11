package com.aktcl.aron.amo

import com.aktcl.aron.core.database.dao.ReferenceDao
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.feature.amo.CallTargetsStore

/** The last good call-targets answer per month, in the small key-value table `sync_meta` (no schema change): one short JSON per `YYYY-MM`. */
class RoomCallTargetsStore(private val dao: ReferenceDao) : CallTargetsStore {
    override suspend fun read(month: String): String? = dao.meta(key(month))?.takeIf { it.isNotBlank() }
    override suspend fun write(month: String, json: String) = dao.putMeta(SyncMetaEntity(key(month), json))
    override suspend fun clear(month: String) = dao.deleteMeta(key(month))

    private fun key(month: String) = "$PREFIX$month"

    companion object { const val PREFIX = "amo.call_targets." }
}

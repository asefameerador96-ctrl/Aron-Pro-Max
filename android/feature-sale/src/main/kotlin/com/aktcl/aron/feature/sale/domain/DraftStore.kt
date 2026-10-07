package com.aktcl.aron.feature.sale.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Where the in-progress sale survives a kill and relaunch (F-SR-025). One draft per visit. */
interface DraftStore {
    fun load(visitUuid: String): SaleDraft?
    fun save(draft: SaleDraft)
    fun clear(visitUuid: String)
}

/**
 * Files in the app's private storage: write to a temp file, then atomic rename, so a kill mid-write leaves the previous
 * draft whole. A corrupt or unreadable file reads as "no draft" instead of crashing the sale.
 */
class FileDraftStore(private val dir: File) : DraftStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(visitUuid: String): File {
        require(visitUuid.matches(Regex("[0-9a-f-]{36}"))) { "not a UUID" }
        return File(dir, "sale-draft-$visitUuid.json")
    }

    override fun load(visitUuid: String): SaleDraft? = try {
        file(visitUuid).takeIf { it.exists() }?.let { json.decodeFromString<SaleDraft>(it.readText()) }
    } catch (e: Exception) { null }

    override fun save(draft: SaleDraft) {
        dir.mkdirs()
        val target = file(draft.visitUuid)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(json.encodeToString(draft))
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "draft rename failed" } }
    }

    override fun clear(visitUuid: String) { file(visitUuid).delete() }
}

package com.aktcl.aron.feature.stock

import java.io.File

/**
 * The client UUIDs of the Stock Save in progress, per (SKU, quantity), kept across a process kill (D-AGATE-07). A Save that committed
 * and was killed before the screen noticed leaves its movements in Room; the UUIDs it used are here, so a Save of the SAME values after
 * the relaunch posts the SAME UUIDs, which the repository stores once: a re-entry can never double an issue that already landed.
 * Cleared when a Save's commit is confirmed. Scoped to one business date: an attempt of another date is ignored.
 */
interface StockAttemptStore {
    fun load(businessDate: String): Map<String, String>
    fun save(businessDate: String, uuids: Map<String, String>)
    fun clear()
}

/** No persistence (tests and hosts without a files directory): the attempt lives as long as the [StockLoad]. */
object NoStockAttemptStore : StockAttemptStore {
    override fun load(businessDate: String): Map<String, String> = emptyMap()
    override fun save(businessDate: String, uuids: Map<String, String>) {}
    override fun clear() {}
}

/** One small text file: first line the business date, then `sku=qty<TAB>uuid` lines. A damaged or unreadable file reads as no attempt. */
class FileStockAttemptStore(private val file: File) : StockAttemptStore {
    override fun load(businessDate: String): Map<String, String> = try {
        val lines = file.takeIf { it.exists() }?.readLines().orEmpty()
        if (lines.firstOrNull() != businessDate) emptyMap()
        else lines.drop(1).mapNotNull { l -> l.split('\t').takeIf { it.size == 2 && it[1].matches(UUID) }?.let { it[0] to it[1] } }.toMap()
    } catch (_: Exception) { emptyMap() }

    override fun save(businessDate: String, uuids: Map<String, String>) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText((listOf(businessDate) + uuids.map { (k, v) -> "$k\t$v" }).joinToString("\n"))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (_: Exception) { /* the in-memory attempt still guards this run */ }
    }

    override fun clear() { runCatching { file.delete() } }

    private companion object { val UUID = Regex("[0-9a-f-]{36}") }
}

package com.aktcl.aron.core.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import androidx.room.withTransaction
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.rules.BusinessDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** What [DeviceTelemetry] reads from the platform; a fake in tests. Every read is cheap and one-shot: nothing listens. */
interface TelemetryProbe {
    /** Bytes received plus sent by this app's uid since boot, or null when the platform does not count them. */
    fun uidBytes(): Long?
    /** CPU time of this process since it started (user plus system). */
    fun processCpuMs(): Long
    /** Changes with every process start, so CPU time can be accumulated across process starts. */
    val processToken: String
    /** Whole-device battery percent and whether it is plugged in, from the sticky intent (no receiver), or null. */
    fun battery(): Pair<Int, Boolean>?
    /** True on a metered (mobile) default network, false on Wi-Fi or other unmetered, null without a network. */
    fun metered(): Boolean?

    class Android(context: Context) : TelemetryProbe {
        private val app = context.applicationContext
        override fun uidBytes(): Long? {
            val uid = Process.myUid()
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            return if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) null else rx + tx
        }
        override fun processCpuMs(): Long = Process.getElapsedCpuTime()
        override val processToken: String = PROCESS_TOKEN
        override fun battery(): Pair<Int, Boolean>? = runCatching {
            val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            (level * 100 / scale) to (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }.getOrNull()
        override fun metered(): Boolean? = runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            if (cm.activeNetwork == null) null else cm.isActiveNetworkMetered
        }.getOrNull()

        private companion object {
            val PROCESS_TOKEN: String = UUID.randomUUID().toString()
        }
    }
}

/** What the engine needs from the telemetry: the object to carry and the answer that it arrived. */
interface BatchTelemetry {
    /** The date and object to carry in this batch, or null. */
    suspend fun pending(): Pair<String, JsonElement>?
    suspend fun sent(date: String)
}

/**
 * Daily field telemetry `telemetry.day` (F-SYS-081, doc 17 s4.4, D-507, D-509): one object per business date, at most
 * `cfg.telemetry.device_max_bytes_per_day` (1,024) bytes, sent in the batch body's `telemetry` member with the first
 * batches after the date closes; kept in the user's `sync_meta` until a batch carrying it is answered (a lost answer sends
 * it again: the server keeps one row per device and date). `starts` counts the processes that sampled.
 *
 * Nothing polls: [sample] runs when the app comes to the front, before each batch and after each sync job; bytes and CPU
 * time are deltas between samples (uid bytes restart at boot, CPU time at process start), battery is the first sample in
 * the 30 minutes after 08:00, 12:00 and 17:00 Dhaka. `b_mob` counts the app uid on a metered network, minus the photo bytes
 * the media uploader reports as `b_mob_media`; `gps` counts fixes taken (not reused) for that date's captures.
 */
class DeviceTelemetry(
    private val db: AronDatabase,
    private val probe: TelemetryProbe,
    private val clock: WallClock,
) {
    private val meta = db.referenceDao()

    suspend fun sample() = edit { day ->
        val base = meta.meta(KEY_BASE)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val cpu = probe.processCpuMs()
        // A new process since the last sample counts as one start (only processes that sample are seen: every one that
        // syncs or comes to the front).
        if (base?.str("p") != probe.processToken) day.add("starts", 1)
        val cpuDelta = if (base?.str("p") == probe.processToken) (cpu - (base.num("c") ?: cpu)).coerceAtLeast(0) else cpu
        val bytes = probe.uidBytes()
        val last = base?.num("b")
        val byteDelta = when {
            bytes == null || last == null -> 0L
            bytes >= last -> bytes - last
            else -> bytes // rebooted: the uid counters started again
        }
        if (byteDelta > 0) when (probe.metered()) {
            true -> day.add("b_mob", byteDelta)
            false -> day.add("b_wifi", byteDelta)
            null -> day.add("b_wifi", byteDelta) // the network dropped since the bytes moved: unknown, never billed as mobile
        }
        day.add("cpu_ms", cpuDelta)
        probe.battery()?.let { (pct, plugged) ->
            val minute = dhakaMinute()
            SLOTS.forEachIndexed { i, slot ->
                val key = "bat${i}"
                if (minute in slot until slot + 30 && day[key] == null) day[key] = JsonPrimitive(pct)
            }
            if (plugged && minute >= SLOTS[0]) day["plug"] = JsonPrimitive(1)
        }
        meta.putMeta(SyncMetaEntity(KEY_BASE, buildJsonObject {
            put("p", probe.processToken); put("c", cpu); bytes?.let { put("b", it) }
        }.toString()))
    }

    /** Time a sync job held the device awake (WorkManager's wake lock is the app's only one). */
    suspend fun noteWake(ms: Long) = edit { it.add("wake_ms", ms.coerceAtLeast(0)) }

    /** Photo bytes the media uploader moved on a metered network (also counted in the uid total). */
    suspend fun noteMobileMediaBytes(bytes: Long) = edit { it.add("b_mob_media", bytes.coerceAtLeast(0)) }

    /** The last time of the day the network came back (s4.2). */
    suspend fun noteConnectivityRegained(iso: String) = edit { it["regained"] = JsonPrimitive(iso) }

    /**
     * The oldest closed date's object, or null (none, or `cfg.telemetry.enabled` false: then the kept days are dropped).
     * Dates older than [KEEP_DAYS] are dropped unsent.
     */
    suspend fun pendingDay(enabled: Boolean, maxBytes: Int): Pair<String, JsonElement>? {
        val today = java.time.LocalDate.parse(BusinessDate.of(clock.nowMs()).toString())
        val days = meta.metaWithPrefix(DAY_PREFIX)
        if (!enabled) { days.forEach { meta.deleteMeta(it.key) }; return null }
        for (row in days) { // ordered by key, so by date
            val date = row.key.removePrefix(DAY_PREFIX)
            val d = runCatching { java.time.LocalDate.parse(date) }.getOrNull()
            if (d == null || d.isBefore(today.minusDays(KEEP_DAYS))) { meta.deleteMeta(row.key); continue }
            if (!d.isBefore(today)) continue
            val day = runCatching { Json.parseToJsonElement(row.value).jsonObject }.getOrNull() ?: run { meta.deleteMeta(row.key); null } ?: continue
            return date to wire(date, day, gpsFixes(date)).fit(maxBytes)
        }
        return null
    }

    /** The batch that carried [date] was answered: the date is never sent again. */
    suspend fun markSent(date: String) = meta.deleteMeta(DAY_PREFIX + date)

    /** The engine's view, with `cfg.telemetry.enabled` (default true) and the byte cap (default 1,024, held to 256..4,096). */
    fun forBatch(config: suspend (String) -> String?) = object : BatchTelemetry {
        override suspend fun pending(): Pair<String, JsonElement>? {
            val enabled = config(CFG_ENABLED)?.trim()?.removeSurrounding("\"")?.let { it != "false" } ?: true
            val max = (SessionSyncRunner.configInt(config(CFG_MAX_BYTES)) ?: DEFAULT_MAX_BYTES).coerceIn(256, 4096)
            return pendingDay(enabled, max)
        }
        override suspend fun sent(date: String) = markSent(date)
    }

    private fun wire(date: String, day: Map<String, JsonElement>, gps: Int): JsonObject {
        val media = day.num("b_mob_media") ?: 0
        return buildJsonObject {
            put("d", date)
            put("b_mob", ((day.num("b_mob") ?: 0) - media).coerceAtLeast(0))
            put("b_mob_media", media)
            put("b_wifi", day.num("b_wifi") ?: 0)
            put("cpu_ms", day.num("cpu_ms") ?: 0)
            put("wake_ms", day.num("wake_ms") ?: 0)
            put("starts", day.num("starts") ?: 0)
            put("gps", gps)
            put("bat", JsonArray(SLOTS.indices.map { day["bat$it"]?.jsonPrimitive?.intOrNull?.let(::JsonPrimitive) ?: JsonNull }))
            put("plug", day["plug"]?.jsonPrimitive?.intOrNull ?: 0)
            put("regained", day["regained"] ?: JsonNull)
        }
    }

    /** Within the byte cap: the optional members go first (about 250 bytes are used; the cap is a guard). */
    private fun JsonObject.fit(maxBytes: Int): JsonObject {
        if (toString().toByteArray(Charsets.UTF_8).size <= maxBytes) return this
        val core = JsonObject(filterKeys { it in setOf("d", "b_mob", "b_mob_media", "b_wifi", "cpu_ms", "starts", "gps", "bat") })
        return if (core.toString().toByteArray(Charsets.UTF_8).size <= maxBytes) core else JsonObject(filterKeys { it == "d" })
    }

    private suspend fun gpsFixes(date: String): Int = withContext(Dispatchers.IO) {
        runCatching {
            db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM geo_fix WHERE reused = 0 AND owner_client_uuid IN (SELECT client_uuid FROM outbox WHERE business_date = ?)",
                arrayOf<Any>(date),
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.getOrDefault(0)
    }

    private suspend fun edit(change: suspend (MutableMap<String, JsonElement>) -> Unit) {
        val key = DAY_PREFIX + BusinessDate.of(clock.nowMs())
        db.withTransaction {
            val day = meta.meta(key)?.let { runCatching { Json.parseToJsonElement(it).jsonObject.toMutableMap() }.getOrNull() } ?: mutableMapOf()
            change(day)
            meta.putMeta(SyncMetaEntity(key, JsonObject(day).toString()))
        }
    }

    private fun dhakaMinute(): Int = Math.floorMod(Math.floorDiv(clock.nowMs() + BusinessDate.DHAKA_OFFSET_MS, 60_000L), 24 * 60L).toInt()

    private fun MutableMap<String, JsonElement>.add(key: String, n: Long) { this[key] = JsonPrimitive((num(key) ?: 0L) + n) }
    private fun Map<String, JsonElement>.num(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun Map<String, JsonElement>.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val DAY_PREFIX = "telemetry.day."
        const val KEY_BASE = "telemetry.base"
        const val KEEP_DAYS = 7L
        const val CFG_ENABLED = "cfg.telemetry.enabled"
        const val CFG_MAX_BYTES = "cfg.telemetry.device_max_bytes_per_day"
        const val DEFAULT_MAX_BYTES = 1024
        /** 08:00, 12:00 and 17:00 Dhaka, in minutes. */
        private val SLOTS = intArrayOf(8 * 60, 12 * 60, 17 * 60)
    }
}

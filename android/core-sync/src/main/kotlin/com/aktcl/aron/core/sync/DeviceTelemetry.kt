package com.aktcl.aron.core.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.rules.BusinessDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
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
    /** A batch that carried [date] was refused. */
    suspend fun failed(date: String) {}
}

/**
 * Daily field telemetry `telemetry.day` (F-SYS-081, doc 17 s4.4, D-507, D-509): one object per business date for the
 * DEVICE (uid bytes and CPU time belong to the app, not to a user, so a shared phone keeps one stream, never one per user
 * database), at most `cfg.telemetry.device_max_bytes_per_day` (1,024) bytes. It rides the batch body's `telemetry` member
 * of whichever user uploads first after the date closes and is kept in [file] until a batch carrying it is answered (a
 * lost answer sends it again: the server keeps one row per device and date). A day refused twice is dropped.
 *
 * Nothing polls. A sample is taken at every save (the scheduler's request), on every default-network change, when the app
 * comes to the front, and around each sync run. Bytes since the previous sample are billed to the network that was active
 * at that sample (so a sample on every network change splits them correctly); a reboot is seen from elapsed time going
 * back; CPU time is summed across processes and each new process is one start. Battery is the first sample in the 30 min
 * after 08:00, 12:00 and 17:00 Dhaka, plus whether it was plugged in after 08:00. Platform reads happen before the lock, and
 * nothing here touches a user database except [noteGps], which the sync runner feeds per user.
 */
class DeviceTelemetry(
    private val file: java.io.File,
    private val probe: TelemetryProbe,
    private val clock: WallClock,
) {
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastSampleElapsed = Long.MIN_VALUE

    /** A sample off the caller's thread; at most one a minute unless a battery slot is open (saves come in bursts). */
    fun sampleSoon() {
        val now = clock.elapsedRealtimeMs()
        if (lastSampleElapsed != Long.MIN_VALUE && now - lastSampleElapsed in 0 until 60_000L && slotIndex(dhakaMinute()) == null) return
        scope.launch { try { sample() } catch (_: Exception) { } }
    }

    /** The default network came or went: bill the bytes so far to the old network, and keep the regain time. */
    fun onNetworkChange(available: Boolean) {
        scope.launch {
            try {
                sample()
                if (available) noteConnectivityRegained(com.aktcl.aron.core.sync.SyncEngine.iso(clock.nowMs()))
            } catch (_: Exception) { }
        }
    }

    suspend fun sample() {
        // Platform reads first, outside any lock or transaction.
        val cpu = probe.processCpuMs()
        val bytes = probe.uidBytes()
        val metered = probe.metered()
        val battery = probe.battery()
        val elapsed = clock.elapsedRealtimeMs()
        val minute = dhakaMinute()
        lastSampleElapsed = elapsed
        update { state, day ->
            val base = state["base"] as? JsonObject
            val sameProcess = base?.str("p") == probe.processToken
            if (!sameProcess) day.add("starts", 1)
            day.add("cpu_ms", if (sameProcess) (cpu - (base!!.num("c") ?: cpu)).coerceAtLeast(0) else cpu)
            val rebooted = base?.num("e")?.let { elapsed < it } ?: false
            val last = base?.num("b")
            val byteDelta = when {
                bytes == null || last == null -> 0L
                rebooted || bytes < last -> bytes // the uid counters started again at boot
                else -> bytes - last
            }
            // Billed to the network of the previous sample; without one, to the current; without either, Wi-Fi (never
            // billed as mobile on a guess).
            val billedMetered = (base?.get("m") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: metered
            if (byteDelta > 0) day.add(if (billedMetered == true) "b_mob" else "b_wifi", byteDelta)
            battery?.let { (pct, plugged) ->
                slotIndex(minute)?.let { i -> if (day["bat$i"] == null) day["bat$i"] = JsonPrimitive(pct) }
                if (plugged && minute >= SLOTS[0]) day["plug"] = JsonPrimitive(1)
            }
            state["base"] = buildJsonObject {
                put("p", probe.processToken); put("c", cpu); put("e", elapsed)
                bytes?.let { put("b", it) }
                metered?.let { put("m", it.toString()) }
            }
        }
    }

    /** Time a sync job kept the device awake (WorkManager's wake lock is the app's only one). */
    suspend fun noteWake(ms: Long) = update { _, day -> day.add("wake_ms", ms.coerceAtLeast(0)) }

    /** Photo bytes the media uploader moved on a metered network (also inside the uid total); `b_mob_media` is sent only once reported. */
    suspend fun noteMobileMediaBytes(bytes: Long) = update { _, day -> day.add("b_mob_media", bytes.coerceAtLeast(0)) }

    /** The last time of the day the network came back (s4.2). */
    suspend fun noteConnectivityRegained(iso: String) = update { _, day -> day["regained"] = JsonPrimitive(iso) }

    /** Fixes taken (not reused) by [userId] for the kept dates, counted from that user's database; summed over users. */
    suspend fun noteGps(userId: Long, count: suspend (date: String) -> Int) {
        val dates = lock.withLock { read()["days"]?.let { it as? JsonObject }?.keys?.toList() } ?: return
        val counts = dates.associateWith { runCatching { count(it) }.getOrNull() }
        lock.withLock {
            val state = read()
            val days = (state["days"] as? JsonObject)?.toMutableMap() ?: return@withLock
            counts.forEach { (d, n) ->
                val day = (days[d] as? JsonObject)?.toMutableMap() ?: return@forEach
                if (n != null) day["gps_u$userId"] = JsonPrimitive(n)
                days[d] = JsonObject(day)
            }
            state["days"] = JsonObject(days)
            write(state)
        }
    }

    /**
     * The oldest closed date's object, or null (none, or `cfg.telemetry.enabled` false: then the kept days are dropped).
     * Dates older than [KEEP_DAYS], or refused [MAX_FAILS] times, are dropped unsent.
     */
    suspend fun pendingDay(enabled: Boolean, maxBytes: Int): Pair<String, JsonElement>? = lock.withLock {
        val today = java.time.LocalDate.parse(BusinessDate.of(clock.nowMs()).toString())
        val state = read()
        val days = (state["days"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (!enabled) {
            if (days.isNotEmpty()) { state["days"] = JsonObject(emptyMap()); write(state) }
            return@withLock null
        }
        var changed = false
        var found: Pair<String, JsonElement>? = null
        for (date in days.keys.sorted()) {
            val d = runCatching { java.time.LocalDate.parse(date) }.getOrNull()
            val day = days[date] as? JsonObject
            if (d == null || day == null || d.isBefore(today.minusDays(KEEP_DAYS)) || (day.num("fails") ?: 0) >= MAX_FAILS) {
                days.remove(date); changed = true; continue
            }
            if (d.isBefore(today) && found == null) found = date to wire(date, day).fit(maxBytes)
        }
        if (changed) { state["days"] = JsonObject(days); write(state) }
        found
    }

    /** The batch that carried [date] was answered: the date is never sent again. */
    suspend fun markSent(date: String) = lock.withLock {
        val state = read()
        val days = (state["days"] as? JsonObject)?.toMutableMap() ?: return@withLock
        if (days.remove(date) != null) { state["days"] = JsonObject(days); write(state) }
    }

    /** A batch that carried [date] was refused: after [MAX_FAILS] the day is dropped so it can never hold back records. */
    suspend fun markFailed(date: String) = lock.withLock {
        val state = read()
        val days = (state["days"] as? JsonObject)?.toMutableMap() ?: return@withLock
        val day = (days[date] as? JsonObject)?.toMutableMap() ?: return@withLock
        day.add("fails", 1)
        days[date] = JsonObject(day)
        state["days"] = JsonObject(days)
        write(state)
    }

    /** The engine's view, with `cfg.telemetry.enabled` (default true) and the byte cap (default 1,024, held to 256..4,096). */
    fun forBatch(config: suspend (String) -> String?) = object : BatchTelemetry {
        override suspend fun pending(): Pair<String, JsonElement>? {
            val enabled = config(CFG_ENABLED)?.trim()?.removeSurrounding("\"")?.let { it != "false" } ?: true
            val max = (SessionSyncRunner.configInt(config(CFG_MAX_BYTES)) ?: DEFAULT_MAX_BYTES).coerceIn(256, 4096)
            return pendingDay(enabled, max)
        }
        override suspend fun sent(date: String) = markSent(date)
        override suspend fun failed(date: String) = markFailed(date)
    }

    private fun wire(date: String, day: Map<String, JsonElement>): JsonObject = buildJsonObject {
        put("d", date)
        put("b_mob", day.num("b_mob") ?: 0)
        day.num("b_mob_media")?.let { put("b_mob_media", it) } // absent = not reported, never "zero photos"
        put("b_wifi", day.num("b_wifi") ?: 0)
        put("cpu_ms", day.num("cpu_ms") ?: 0)
        put("wake_ms", day.num("wake_ms") ?: 0)
        put("starts", day.num("starts") ?: 0)
        put("gps", day.filterKeys { it.startsWith("gps_u") }.values.sumOf { (it as? JsonPrimitive)?.longOrNull ?: 0L })
        put("bat", JsonArray(SLOTS.indices.map { day["bat$it"]?.jsonPrimitive?.intOrNull?.let(::JsonPrimitive) ?: JsonNull }))
        put("plug", day["plug"]?.jsonPrimitive?.intOrNull ?: 0)
        put("regained", day["regained"] ?: JsonNull)
    }

    /** Within the byte cap: the optional members go first (about 250 bytes are used; the cap is a guard). */
    private fun JsonObject.fit(maxBytes: Int): JsonObject {
        if (toString().toByteArray(Charsets.UTF_8).size <= maxBytes) return this
        val core = JsonObject(filterKeys { it in setOf("d", "b_mob", "b_mob_media", "b_wifi", "cpu_ms", "starts", "gps", "bat") })
        return if (core.toString().toByteArray(Charsets.UTF_8).size <= maxBytes) core else JsonObject(filterKeys { it == "d" })
    }

    /** One read-modify-write of today's day under the lock; the file is replaced atomically. */
    private suspend fun update(change: (MutableMap<String, JsonElement>, MutableMap<String, JsonElement>) -> Unit) = lock.withLock {
        val date = BusinessDate.of(clock.nowMs()).toString()
        val state = read()
        val days = (state["days"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val day = (days[date] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        change(state, day)
        days[date] = JsonObject(day)
        state["days"] = JsonObject(days)
        write(state)
    }

    private suspend fun read(): MutableMap<String, JsonElement> = withContext(Dispatchers.IO) {
        runCatching { Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap() }.getOrNull() ?: mutableMapOf()
    }

    private suspend fun write(state: Map<String, JsonElement>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = java.io.File(file.path + ".tmp")
        tmp.writeText(JsonObject(state).toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun slotIndex(minute: Int): Int? = SLOTS.indices.firstOrNull { minute in SLOTS[it] until SLOTS[it] + 30 }

    private fun dhakaMinute(): Int = Math.floorMod(Math.floorDiv(clock.nowMs() + BusinessDate.DHAKA_OFFSET_MS, 60_000L), 24 * 60L).toInt()

    private fun MutableMap<String, JsonElement>.add(key: String, n: Long) { this[key] = JsonPrimitive((num(key) ?: 0L) + n) }
    private fun Map<String, JsonElement>.num(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun Map<String, JsonElement>.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val KEEP_DAYS = 7L
        const val MAX_FAILS = 2
        const val CFG_ENABLED = "cfg.telemetry.enabled"
        const val CFG_MAX_BYTES = "cfg.telemetry.device_max_bytes_per_day"
        const val DEFAULT_MAX_BYTES = 1024
        /** 08:00, 12:00 and 17:00 Dhaka, in minutes. */
        private val SLOTS = intArrayOf(8 * 60, 12 * 60, 17 * 60)

        /** Fixes taken (not reused) for the captures of [date] in [db]. */
        suspend fun gpsFixes(db: AronDatabase, date: String): Int = withContext(Dispatchers.IO) {
            db.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM geo_fix WHERE reused = 0 AND owner_client_uuid IN (SELECT client_uuid FROM outbox WHERE business_date = ?)",
                arrayOf<Any>(date),
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
    }
}

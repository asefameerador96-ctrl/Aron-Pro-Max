package com.aktcl.aron.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * A stand-in for `POST /v1/sync/batch` with the server's idempotency rules of docs/24 s3.3: an ingest registry keyed by
 * client_uuid with a payload hash (duplicate, payload_conflict), a batch store keyed by (device, batch_uuid) with the
 * record-set fingerprint (replayed, ERR_SYNC_BATCH_UUID_REUSED), records processed in array order. Fault hooks let tests
 * lose a response after the commit, fail before it, or answer per record.
 */
class FakeIngestServer : Dispatcher() {
    data class Stored(val type: String, val hash: String, val serverId: Long, val record: JsonObject)

    val registry = LinkedHashMap<String, Stored>()
    private val batches = HashMap<String, Pair<String, String>>() // device|batch -> (fingerprint, response)
    val requests = ArrayList<Seen>()
    var validTokens = mutableSetOf("upload-1")
    private var nextId = 1000L

    /** Lose the next N responses after the batch was committed (the connection drops). */
    var dropAfterCommit = 0

    /** Answer the next N requests with a status before any processing. */
    val failBefore = ArrayDeque<Int>()

    /** A batch containing any of these uuids fails with 500 before any processing (a poison family). */
    val poison = mutableSetOf<String>()

    /** A batch containing any of these uuids gets 400 ERR_VALIDATION with a pointer to that record. */
    val invalid = mutableSetOf<String>()

    /** client_uuid -> remaining retryable rejects (`parent_missing`). */
    val rejectRetryable = HashMap<String, Int>()

    /** client_uuids rejected for good (`schema_invalid`). */
    val rejectFinal = mutableSetOf<String>()

    /** client_uuids quarantined (`arithmetic_mismatch`); resolutions to deliver with the next response. */
    val quarantine = mutableSetOf<String>()

    /**
     * F-SYS-072: uuids the registry holds as quarantined `device_integrity_failed` (BC-53 step 2). A resend is quarantined
     * again while [enforce] (or an older server) holds, else released: processed like a new record.
     */
    val integrityHeld = mutableSetOf<String>()
    var enforce = false
    val pendingResolutions = ArrayList<Pair<String, String>>()
    var holdS = 0

    data class Seen(val batchUuid: String?, val attempt: String?, val gzip: Boolean, val body: JsonObject?, val status: Int, val replayed: Boolean)

    fun storedOf(type: String) = registry.values.filter { it.type == type }

    /** F-SYS-047: the generation every batch answer carries, and the statement GET /v1/sync/generation returns. */
    var generation = "9b2f6a4e-1c3d-4e5f-8a7b-0c1d2e3f4a5b"
    var generationKind = "initial"
    var lostAfterUtc: String? = null
    var mintedAt = "2026-10-05T12:00:00.000Z"
    var generationReads = 0
    /** Answers the next N generation reads with 503. */
    var generationFails = 0
    /** The `since` of each generation read (null without it). */
    val generationSince = ArrayList<String?>()
    /** F-SYS-047 `?since=`: generation -> its `lost_after_utc`, in mint order (the lineage history). */
    val history = LinkedHashMap<String, String?>()
    var previousGeneration: String? = null

    /** F-SYS-080: the digest bodies received; the next N digests answer this status. */
    val digests = ArrayList<JsonObject>()
    val digestFailBefore = ArrayDeque<Int>()
    /** Server's window: dates older than today - 7 (by the fake's [today]) are answered as matching. */
    var today = "2026-10-05"

    /**
     * A point-in-time restore: the registry keeps its first [keep] records (insertion order), the batch store is gone, and
     * a new generation is minted with [lostAfter] as `lost_after_utc`.
     */
    fun restore(keep: Int, newGeneration: String, lostAfter: String) {
        val kept = registry.entries.take(keep).map { it.key to it.value }
        registry.clear(); kept.forEach { (k, v) -> registry[k] = v }
        batches.clear()
        if (history.isEmpty()) history[generation] = null
        previousGeneration = generation
        generation = newGeneration; generationKind = "pitr"; lostAfterUtc = lostAfter
        history[newGeneration] = lostAfter
    }

    /** F-SYS-080: drops the given uuids from the registry (rows lost without a new generation; the digest finds them). */
    fun lose(uuids: Collection<String>) { uuids.forEach { registry.remove(it) } }

    private fun digest(body: JsonObject): JsonObject {
        val window = java.time.LocalDate.parse(today).minusDays(7)
        val resend = buildJsonArray {
            for (item in body["items"]!!.jsonArray.map { it.jsonObject }) {
                val date = item["business_date"]!!.jsonPrimitive.content
                val type = item["type"]!!.jsonPrimitive.content
                val d = java.time.LocalDate.parse(date)
                if (d.isBefore(window) || d.isAfter(java.time.LocalDate.parse(today))) continue
                val mine = DigestHash.buckets(registry.filter { (_, v) -> v.type == type && v.record["business_date"]?.jsonPrimitive?.content == date }.keys)
                val phone = item["buckets"]!!.jsonArray.map { it.jsonObject }
                val differ = (0 until 16).filter { b ->
                    phone[b]["count"]!!.jsonPrimitive.content.toLong() != mine[b].count || phone[b]["hash"]!!.jsonPrimitive.content != mine[b].hash
                }
                if (differ.isNotEmpty()) add(buildJsonObject {
                    put("business_date", JsonPrimitive(date)); put("type", JsonPrimitive(type))
                    put("buckets", JsonArray(differ.map { JsonPrimitive(it) }))
                })
            }
        }
        return buildJsonObject { put("resend", resend) }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        if (request.url.encodedPath == "/v1/sync/generation") {
            if (request.headers["Authorization"]?.removePrefix("Bearer ") !in validTokens) return api(401, problem("ERR_TOKEN_EXPIRED", 401))
            generationReads++
            val since = request.url.queryParameter("since")
            generationSince += since
            if (generationFails > 0) { generationFails--; return api(503, problem("ERR_SERVICE_UNAVAILABLE", 503)) }
            // The earliest loss of every generation minted after `since`; null when `since` is current or unknown.
            val after = history.keys.toList().let { k -> k.indexOf(since).takeIf { it >= 0 }?.let { k.drop(it + 1) } }
            val earliest = after?.takeIf { it.isNotEmpty() }?.mapNotNull { history[it] }?.minOrNull()
            return api(200, buildJsonObject {
                put("generation", JsonPrimitive(generation)); put("kind", JsonPrimitive(generationKind))
                put("restore_point_utc", lostAfterUtc?.let { JsonPrimitive(it) } ?: JsonNull)
                put("lost_after_utc", lostAfterUtc?.let { JsonPrimitive(it) } ?: JsonNull)
                put("minted_at", JsonPrimitive(mintedAt))
                put("previous_generation", previousGeneration?.let { JsonPrimitive(it) } ?: JsonNull)
                put("earliest_lost_after_utc", earliest?.let { JsonPrimitive(it) } ?: JsonNull)
            }.toString())
        }
        if (request.url.encodedPath == "/v1/sync/digest") {
            if (request.headers["Authorization"]?.removePrefix("Bearer ") !in validTokens) return api(401, problem("ERR_TOKEN_EXPIRED", 401))
            val body = Json.parseToJsonElement(request.body!!.utf8()).jsonObject
            digests += body
            digestFailBefore.removeFirstOrNull()?.let { return api(it, problem("ERR_SERVICE_UNAVAILABLE", it)) }
            return api(200, digest(body).toString())
        }
        if (request.url.encodedPath != "/v1/sync/batch") return api(404, problem("ERR_NOT_FOUND", 404))
        val gzip = request.headers["Content-Encoding"] == "gzip"
        val raw = request.body?.toByteArray() ?: ByteArray(0)
        val body = if (gzip) Json.parseToJsonElement(GZIPInputStream(raw.inputStream()).readBytes().decodeToString()).jsonObject else null
        fun seen(status: Int, replayed: Boolean = false) =
            requests.add(Seen(body?.get("batch_uuid")?.jsonPrimitive?.content, request.headers["X-Batch-Attempt"], gzip, body, status, replayed))

        if (!gzip) { seen(415); return api(415, problem("ERR_UNSUPPORTED_MEDIA_TYPE", 415)) }
        val auth = request.headers["Authorization"]?.removePrefix("Bearer ")
        if (auth !in validTokens) { seen(401); return api(401, problem("ERR_TOKEN_EXPIRED", 401)) }
        failBefore.removeFirstOrNull()?.let { status ->
            seen(status)
            return api(status, problem(if (status == 503) "ERR_SERVICE_UNAVAILABLE" else "ERR_INTERNAL", status))
        }
        body!!
        val records = body["records"]!!.jsonArray.map { it.jsonObject }
        if (records.any { it.uuid() in poison }) { seen(500); return api(500, problem("ERR_INTERNAL", 500)) }

        records.indexOfFirst { it.uuid() in invalid }.takeIf { it >= 0 }?.let { i ->
            seen(400)
            return api(400, """{"status":400,"code":"ERR_VALIDATION","errors":[{"pointer":"/records/$i/payload/qty_base","code":"minimum"}]}""")
        }
        val batchKey = body["device_uuid"]!!.jsonPrimitive.content + "|" + body["batch_uuid"]!!.jsonPrimitive.content
        val fingerprint = sha256(records.map { "${it.uuid()}:${sha256(canonical(it))}" }.sorted().joinToString("\n"))
        batches[batchKey]?.let { (fp, stored) ->
            if (fp != fingerprint) { seen(409); return api(409, problem("ERR_SYNC_BATCH_UUID_REUSED", 409)) }
            seen(200, replayed = true)
            val replay = Json.parseToJsonElement(stored).jsonObject.let { JsonObject(it + ("replayed" to JsonPrimitive(true))) }
            return api(200, replay.toString())
        }

        val acks = buildJsonArray {
            for (r in records) {
                val uuid = r.uuid()
                val type = r["type"]!!.jsonPrimitive.content
                val hash = sha256(canonical(r))
                val prior = registry[uuid]
                add(
                    when {
                        prior != null && prior.hash == hash -> ack(uuid, type, "duplicate", serverId = prior.serverId)
                        prior != null -> ack(uuid, type, "quarantined", code = "payload_conflict")
                        (rejectRetryable[uuid] ?: 0) > 0 -> {
                            rejectRetryable[uuid] = rejectRetryable.getValue(uuid) - 1
                            ack(uuid, type, "rejected", code = "parent_missing", retryable = true)
                        }
                        uuid in rejectFinal -> ack(uuid, type, "rejected", code = "schema_invalid", retryable = false)
                        uuid in integrityHeld && enforce -> ack(uuid, type, "quarantined", code = "device_integrity_failed")
                        uuid in quarantine -> ack(uuid, type, "quarantined", code = "arithmetic_mismatch")
                        else -> {
                            val id = nextId++
                            registry[uuid] = Stored(type, hash, id, r)
                            ack(uuid, type, "accepted", serverId = id)
                        }
                    },
                )
            }
        }
        val statuses = acks.map { it.jsonObject["status"]!!.jsonPrimitive.content }
        val response = buildJsonObject {
            put("batch_uuid", body["batch_uuid"]!!)
            put("replayed", JsonPrimitive(false))
            put("received_at", JsonPrimitive("2026-10-05T10:00:00.000Z"))
            put("acks", acks)
            put("summary", buildJsonObject {
                listOf("accepted", "duplicate", "rejected", "quarantined").forEach { s -> put(s, JsonPrimitive(statuses.count { it == s })) }
            })
            put("server_totals", buildJsonArray {
                add(buildJsonObject {
                    put("business_date", JsonPrimitive("2026-10-05")); put("as_of", JsonPrimitive("2026-10-05T10:00:00.000Z"))
                    // The server's count per type is its registry; money is what an exact server computes, i.e. the
                    // device's figures when nothing was lost (F-SYS-009 plumbing, not the server's arithmetic).
                    put("by_type", JsonObject(registry.values.groupBy { it.type }.mapValues { (_, v) ->
                        buildJsonObject { put("accepted", JsonPrimitive(v.size)); put("rejected", JsonPrimitive(0)); put("quarantined", JsonPrimitive(0)) }
                    }))
                    put("money", body["device_money"]?.jsonObject?.get("2026-10-05") ?: JsonObject(emptyMap()))
                })
            })
            put("day_states", JsonArray(emptyList()))
            put("resolutions", buildJsonArray {
                pendingResolutions.forEach { (u, res) ->
                    add(buildJsonObject {
                        put("client_uuid", JsonPrimitive(u)); put("type", JsonPrimitive("memo"))
                        put("resolution", JsonPrimitive(res)); put("resolved_at", JsonPrimitive("2026-10-05T11:00:00.000Z"))
                    })
                }
            })
            put("hold_s", JsonPrimitive(holdS))
            put("config_version", JsonPrimitive(318))
            put("generation", JsonPrimitive(generation))
            put("unknown_future_member", JsonPrimitive("ignored"))
        }.toString()
        pendingResolutions.clear()
        batches[batchKey] = fingerprint to response
        seen(200)
        if (dropAfterCommit > 0) {
            dropAfterCommit--
            return MockResponse.Builder().code(200).addHeader("X-Aron-Api", "1").body(response)
                .onResponseStart(SocketEffect.ShutdownConnection).build()
        }
        return api(200, response)
    }

    private fun JsonObject.uuid() = this["client_uuid"]!!.jsonPrimitive.content

    private fun ack(uuid: String, type: String, status: String, code: String? = null, retryable: Boolean? = null, serverId: Long? = null) =
        buildJsonObject {
            put("client_uuid", JsonPrimitive(uuid)); put("type", JsonPrimitive(type)); put("status", JsonPrimitive(status))
            code?.let { put("code", JsonPrimitive(it)) }
            retryable?.let { put("retryable", JsonPrimitive(it)) }
            serverId?.let { put("server_id", JsonPrimitive(it)) }
        }

    private fun api(code: Int, body: String) = MockResponse.Builder().code(code).addHeader("X-Aron-Api", "1")
        .addHeader("Content-Type", "application/json").body(body).build()

    private fun problem(code: String, status: Int) = """{"type":"about:blank","status":$status,"code":"$code"}"""

    companion object {
        /** Sorted-key canonical form (enough for the RFC 8785 role here: same record, same text). */
        fun canonical(e: JsonElement): String = when (e) {
            is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"${it.key}\":${canonical(it.value)}" }
            is JsonArray -> e.joinToString(",", "[", "]") { canonical(it) }
            else -> e.toString()
        }

        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

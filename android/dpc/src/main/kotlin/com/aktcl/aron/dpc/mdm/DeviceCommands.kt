package com.aktcl.aron.dpc.mdm

import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECPoint
import java.util.Base64
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.longOrNull

/** Contract `DeviceCommand`: one signed instruction pulled by the phone (GET /v1/devices/me/commands). */
@Serializable
data class DeviceCommand(
    @SerialName("command_id") val commandId: String,
    val type: String,
    val params: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    val sig: String,
)

@Serializable
data class DeviceCommandList(val items: List<DeviceCommand> = emptyList())

/** The two calls of the channel. The app implements them with its device-proof HTTP client; any exception means offline. */
interface CommandApi {
    fun pull(): List<DeviceCommand>
    /** POST /v1/devices/me/commands/{id}/ack; [state] is `acked` or `failed`. */
    fun ack(commandId: String, state: String, result: JsonObject?)
}

/** What the phone does for each command type; every call may throw, the processor turns that into `failed`. */
interface CommandGateway {
    fun isDeviceOwner(): Boolean
    fun lockNow(message: String?)
    fun clearLockMessage()
    fun showMessage(textBn: String?, textEn: String?)
    /** One balanced-power fix on demand; null when none could be had. Never continuous. */
    fun locateOnce(): Fix?
    fun reboot()
    /** Lifts `no_factory_reset` and wipes; does not return on a real phone. */
    fun liftFactoryResetRestriction()
    fun wipe()

    data class Fix(val lat: Double, val lng: Double, val accuracyM: Double, val atMs: Long)
}

/** Verifies the server's ES256 signature over the canonical command. */
fun interface CommandVerifier {
    fun verify(signed: ByteArray, sigBase64Url: String): Boolean

    companion object {
        /** Public key of the server signing key from its JWK coordinates (base64url x and y, P-256). */
        fun fromJwk(x: String, y: String): CommandVerifier {
            val dec = Base64.getUrlDecoder()
            val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
            val key: PublicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, dec.decode(x)), BigInteger(1, dec.decode(y))), params))
            return fromKey(key)
        }

        fun fromKey(key: PublicKey) = CommandVerifier { signed, sig ->
            runCatching {
                val raw = Base64.getUrlDecoder().decode(sig)
                if (raw.size != 64) return@runCatching false
                Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(signed); verify(derOf(raw)) }
            }.getOrDefault(false)
        }

        /** JWS ES256 signatures are R||S; the JCA verifies DER. */
        private fun derOf(raw: ByteArray): ByteArray {
            fun part(b: ByteArray): ByteArray {
                var i = 0
                while (i < b.size - 1 && b[i] == 0.toByte()) i++
                val t = b.copyOfRange(i, b.size)
                return if (t[0] < 0) byteArrayOf(0) + t else t
            }
            val r = part(raw.copyOfRange(0, 32)); val s = part(raw.copyOfRange(32, 64))
            val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
    }
}

/** Canonical text the server signs: same scheme as directives, prefix `aron-command-v1`, bound to this phone's uuid. */
fun DeviceCommand.signedText(deviceUuid: String): ByteArray =
    listOf("aron-command-v1", commandId, deviceUuid, type, createdAt, expiresAt, params.toString()).joinToString("\n").toByteArray()

/** Handled commands and the acks still owed, one small file replaced atomically (survives kill and reboot). */
class CommandLedger(private val dir: File) {
    private val file get() = File(dir, "mdm-commands.txt")

    /** commandId -> (state, result json, acked). One tab-separated line each. */
    data class Entry(val state: String, val result: String, val acked: Boolean)

    @Synchronized
    fun all(): Map<String, Entry> = file.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull {
        val p = it.split('\t'); if (p.size == 4) p[0] to Entry(p[1], p[3], p[2] == "1") else null
    }.toMap()

    @Synchronized
    fun put(id: String, e: Entry) {
        val m = LinkedHashMap(all()); m[id] = e
        // Bounded: keep the newest 500 (ids are UUIDs, a replay older than that is long expired).
        val kept = m.entries.toList().takeLast(500)
        dir.mkdirs()
        val tmp = File(dir, "mdm-commands.tmp")
        tmp.writeText(kept.joinToString("\n") { "${it.key}\t${it.value.state}\t${if (it.value.acked) 1 else 0}\t${it.value.result.replace('\n', ' ').replace('\t', ' ')}" })
        if (!file.let { tmp.renameTo(it) }) { file.delete(); check(tmp.renameTo(file)) { "cannot store the command ledger" } }
    }
}

data class SyncReport(val executed: Int, val duplicates: Int, val rejected: List<String>, val acksPending: Int, val offline: Boolean)

/**
 * Pull, verify, execute, ack. Call [sync] from the sync path and from an FCM data-message nudge (no payload is trusted;
 * the nudge only triggers the pull). Idempotent: a command id is executed once, whatever the server redelivers; an ack
 * that could not be sent is retried at the next [sync] with the stored result.
 */
class CommandProcessor(
    private val api: CommandApi,
    private val gw: CommandGateway,
    private val verifier: CommandVerifier,
    private val deviceUuid: String,
    private val ledger: CommandLedger,
    private val mdm: MdmPolicyStore,
    private val nowMs: () -> Long,
    /** Re-evaluates blocking, kiosk and the boundary alarm after a new MDM policy. */
    private val onPolicyChanged: () -> Unit = {},
) {
    private val json = Json

    @Synchronized
    fun sync(): SyncReport {
        var offline = false
        // 1. acks owed from earlier runs
        for ((id, e) in ledger.all()) if (!e.acked) offline = !sendAck(id, e) || offline
        // 2. pull
        val items = try { api.pull() } catch (ex: Exception) { return SyncReport(0, 0, emptyList(), pendingAcks(), true) }
        var executed = 0; var dup = 0; val rejected = mutableListOf<String>()
        for (c in items) {
            val why = reject(c)
            if (why != null) { rejected += "${c.commandId}:$why"; continue }
            val seen = ledger.all()[c.commandId]
            if (seen != null) { dup++; if (!seen.acked) offline = !sendAck(c.commandId, seen) || offline; continue }
            val (state, result) = execute(c)
            val entry = CommandLedger.Entry(state, result.toString(), false)
            ledger.put(c.commandId, entry) // write-ahead: the command is never run twice, even if the ack fails
            executed++
            offline = !sendAck(c.commandId, entry) || offline
            // Disruptive steps run after the result is stored and the ack tried, so a reboot or wipe never loses it.
            if (state == "acked") when (c.type) {
                "reboot" -> runCatching { gw.reboot() }
                "factory_reset" -> runCatching { gw.liftFactoryResetRestriction(); gw.wipe() }
            }
        }
        return SyncReport(executed, dup, rejected, pendingAcks(), offline)
    }

    private fun pendingAcks() = ledger.all().count { !it.value.acked }

    private fun reject(c: DeviceCommand): String? {
        val exp = runCatching { Instant.parse(c.expiresAt) }.getOrNull() ?: return "bad_expiry"
        if (c.sig.length != 86) return "unsigned"
        if (!verifier.verify(c.signedText(deviceUuid), c.sig)) return "bad_signature" // also a command made for another phone
        if (exp.toEpochMilliseconds() <= nowMs()) return "expired"
        if (c.type !in TYPES) return "unknown_type"
        return null
    }

    private fun sendAck(id: String, e: CommandLedger.Entry): Boolean = try {
        api.ack(id, e.state, runCatching { json.parseToJsonElement(e.result).jsonObject }.getOrNull())
        ledger.put(id, e.copy(acked = true)); true
    } catch (ex: Exception) { false }

    private fun execute(c: DeviceCommand): Pair<String, JsonObject> = try {
        when (c.type) {
            "lock" -> { gw.lockNow(c.params["message"]?.jsonPrimitive?.contentOrNull); "acked" to JsonObject(emptyMap()) }
            "unlock" -> { gw.clearLockMessage(); "acked" to buildJsonObject { put("note", "lock_message_cleared") } }
            "message" -> {
                val bn = c.params["text_bn"]?.jsonPrimitive?.contentOrNull; val en = c.params["text_en"]?.jsonPrimitive?.contentOrNull
                if (bn.isNullOrBlank() && en.isNullOrBlank()) fail("empty_message") else { gw.showMessage(bn, en); "acked" to JsonObject(emptyMap()) }
            }
            "locate" -> gw.locateOnce()?.let { f ->
                "acked" to buildJsonObject { put("lat", f.lat); put("lng", f.lng); put("accuracy_m", f.accuracyM); put("fix_at_ms", f.atMs) }
            } ?: fail("no_fix")
            "reboot" -> if (gw.isDeviceOwner()) "acked" to JsonObject(emptyMap()) else fail("not_device_owner")
            "factory_reset" -> if (!gw.isDeviceOwner()) fail("not_device_owner") else "acked" to buildJsonObject { put("wiping", true) }
            "apply_policy" -> applyPolicy(c)
            else -> fail("unknown_type")
        }
    } catch (ex: Exception) { fail("exception_" + (ex::class.simpleName ?: "error")) }
        .let { (s, r) -> s to capped(r) }

    private fun applyPolicy(c: DeviceCommand): Pair<String, JsonObject> {
        val id = c.params["policy_id"]?.jsonPrimitive?.longOrNull ?: return fail("no_policy_id")
        val ver = c.params["version"]?.jsonPrimitive?.longOrNull?.toInt() ?: return fail("no_version")
        val doc = runCatching { Json { ignoreUnknownKeys = true }.decodeFromJsonElement(MdmPolicyDoc.serializer(), c.params["policy"] ?: return fail("no_policy")) }.getOrNull() ?: return fail("bad_policy")
        val stored = mdm.save(id, ver, doc)
        if (stored) onPolicyChanged()
        return "acked" to buildJsonObject { put("policy_id", id); put("version", ver); put("applied", stored) }
    }

    private fun fail(code: String): Pair<String, JsonObject> = "failed" to buildJsonObject { put("error", code) }

    private fun capped(r: JsonObject): JsonObject = if (r.toString().length <= MAX_RESULT) r else buildJsonObject { put("error", "result_too_large") }

    companion object {
        const val MAX_RESULT = 2048
        val TYPES = setOf("lock", "unlock", "message", "locate", "reboot", "factory_reset", "apply_policy")
    }
}

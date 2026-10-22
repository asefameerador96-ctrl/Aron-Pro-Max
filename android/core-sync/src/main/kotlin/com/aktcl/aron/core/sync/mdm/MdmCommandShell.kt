package com.aktcl.aron.core.sync.mdm

import android.content.Context
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.DeviceProofSigner
import com.aktcl.aron.core.network.ProofStrings
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.dpc.mdm.CommandApi
import com.aktcl.aron.dpc.mdm.CommandProcessor
import com.aktcl.aron.dpc.mdm.CommandVerifier
import com.aktcl.aron.dpc.mdm.DeviceCommand
import com.aktcl.aron.dpc.mdm.DeviceCommandList
import com.aktcl.aron.dpc.mdm.MdmChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** The server's public ES256 keys (`x`, `y`, base64url), from `GET /v1/auth/jwks`. */
data class ServerKeys(val keys: List<Pair<String, String>>)

/** The three HTTP calls of the command channel, all under the phone's own `X-Device-Proof` (no user needed). */
interface MdmCommandHttp {
    /** Throws on any non-success (offline, 401, 5xx): the DPC treats that as "try at the next sync". */
    fun pull(): List<DeviceCommand>
    fun ack(commandId: String, state: String, result: JsonObject?)
    fun serverKeys(): ServerKeys?
}

class DeviceMdmCommandHttp(
    private val client: AronApiClient,
    private val signer: DeviceProofSigner?,
    private val deviceUuid: () -> String?,
    private val trustedNowMs: () -> Long,
) : MdmCommandHttp {
    private val json = Json { ignoreUnknownKeys = true }

    private fun proof(methodPath: String, body: ByteArray): String? = deviceUuid()?.let { signer?.sign(ProofStrings.device(it, methodPath, body, trustedNowMs())) }

    override fun pull(): List<DeviceCommand> = runBlocking {
        val p = proof("GET $PULL", ByteArray(0)) ?: throw IllegalStateException("not enrolled")
        val r = client.call(PULL, CallAuth.None, build = { header("X-Device-Proof", p) }, decode = { body, _ -> json.decodeFromString(DeviceCommandList.serializer(), body).items })
        when (r) { is ApiResult.Success -> r.value; else -> throw java.io.IOException("pull: $r") }
    }

    override fun ack(commandId: String, state: String, result: JsonObject?) = runBlocking {
        val path = "/v1/devices/me/commands/$commandId/ack"
        val body = buildJsonObject { put("state", state); if (result != null) put("result", result) }.toString().toByteArray(Charsets.UTF_8)
        val p = proof("POST $path", body) ?: throw IllegalStateException("not enrolled")
        val r = client.call(path, CallAuth.None, build = { header("X-Device-Proof", p); post(body.toRequestBody(JSON)) }, decode = { _, _ -> Unit })
        when (r) {
            is ApiResult.Success -> Unit
            // The server will never accept this ack (unknown command, bad body): do not owe it forever.
            is ApiResult.Failure -> if (r.httpStatus in 400..499 && r.httpStatus != 401 && r.httpStatus != 429) Unit else throw java.io.IOException("ack: ${r.httpStatus}")
            else -> throw java.io.IOException("ack: $r")
        }
    }

    override fun serverKeys(): ServerKeys? = runBlocking {
        val r = client.call("/v1/auth/jwks", CallAuth.None, build = { }, decode = { body, _ ->
            json.parseToJsonElement(body).jsonObject["keys"]!!.jsonArray.map { it.jsonObject }.filter { it["kty"]?.jsonPrimitive?.content == "EC" }
                .map { it["x"]!!.jsonPrimitive.content to it["y"]!!.jsonPrimitive.content }
        })
        (r as? ApiResult.Success)?.value?.takeIf { it.isNotEmpty() }?.let { ServerKeys(it) }
    }

    companion object {
        const val PULL = "/v1/devices/me/commands"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Where the server keys are kept between runs (a few hundred bytes). */
interface ServerKeyStore {
    fun load(): ServerKeys?
    fun save(k: ServerKeys)
}

/**
 * Pull and apply the MDM commands of this phone: on every sync run and on an `mdm_nudge` push. It never needs a signed-in
 * user. A key that does not verify (rotation) is refreshed once and the pull repeated. Never throws; returns whether a
 * command was executed.
 */
class MdmCommandRunner(
    private val http: MdmCommandHttp,
    private val keys: ServerKeyStore,
    private val deviceUuid: () -> String?,
    private val processorFor: (api: CommandApi, verifier: CommandVerifier, deviceUuid: String) -> CommandProcessor,
) {
    private fun verifier(k: ServerKeys): CommandVerifier {
        val all = k.keys.map { (x, y) -> CommandVerifier.fromJwk(x, y) }
        return CommandVerifier { signed, sig -> all.any { it.verify(signed, sig) } }
    }

    private fun api() = object : CommandApi {
        override fun pull() = http.pull()
        override fun ack(commandId: String, state: String, result: JsonObject?) = http.ack(commandId, state, result)
    }

    @Synchronized
    fun run(): Boolean = try {
        val uuid = deviceUuid() ?: return false
        var cached = keys.load()
        var refreshed = false
        if (cached == null) { cached = http.serverKeys()?.also(keys::save) ?: return false; refreshed = true }
        var report = processorFor(api(), verifier(cached), uuid).sync()
        if (report.rejected.isNotEmpty() && !refreshed) {
            val fresh = http.serverKeys()
            if (fresh != null && fresh != cached) { keys.save(fresh); report = processorFor(api(), verifier(fresh), uuid).sync() }
        }
        report.executed > 0
    } catch (e: Exception) {
        false
    }
}

/** The apps' handle: built once per process from [SessionComponents]. */
class MdmCommandShell(context: Context, components: SessionComponents, afterPolicyChanged: () -> Unit = {}) {
    private val app = context.applicationContext
    private val runner = MdmCommandRunner(
        DeviceMdmCommandHttp(components.apiClient, components.proofSigner, { components.deviceIdentity.deviceUuid }, components.clock::nowMs),
        PrefsKeys(app), { components.deviceIdentity.deviceUuid },
        { api, verifier, uuid -> MdmChannel.processor(app, api, verifier, uuid, afterPolicyChanged) },
    )

    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "mdm-commands").apply { isDaemon = true } }

    /** Blocking work: call from a worker or a background dispatcher. */
    fun pullAndApply(): Boolean = runner.run()

    /** For the end of a sync run (non-suspending hook): one pull on its own thread, result ignored; the next sync covers a failure. */
    fun afterSync() { try { exec.execute { runner.run() } } catch (_: java.util.concurrent.RejectedExecutionException) { } }

    /** For the push job: runs on the IO dispatcher. */
    suspend fun pullAndApplyIo(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runner.run() }

    private class PrefsKeys(context: Context) : ServerKeyStore {
        private val prefs = context.getSharedPreferences("aron-mdm-keys", Context.MODE_PRIVATE)
        override fun load(): ServerKeys? = prefs.getString("keys", null)?.split(';')?.mapNotNull { it.split(',').takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }?.takeIf { it.isNotEmpty() }?.let(::ServerKeys)
        override fun save(k: ServerKeys) { prefs.edit().putString("keys", k.keys.joinToString(";") { "${it.first},${it.second}" }).apply() }
    }
}

package com.aktcl.aron.dpc.enrolment

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** What the server said to one enrolment call. */
sealed interface EnrolCallResult {
    data class Enrolled(val response: EnrolDeviceResponse) : EnrolCallResult
    /** 4xx with a problem `code` (e.g. ERR_ENROLMENT_TOKEN_EXHAUSTED): final, the token cannot be used again. */
    data class Refused(val code: String) : EnrolCallResult
    /** Transport failure, 5xx, 429, an edge page: try again later with the same request. */
    data class Retry(val reason: String) : EnrolCallResult
}

/** `POST /v1/devices/enrol` (unauthenticated: the token is the credential). The app wires it to core-network. */
fun interface EnrolmentTransport {
    suspend fun enrol(apiBaseUrl: String, request: EnrolDeviceRequest): EnrolCallResult
}

/** The device key (core-geo `AndroidDeviceKeyStore`), behind a port because dpc does not depend on core-geo. */
interface EnrolmentKeys {
    fun newAlias(): String
    fun exists(alias: String): Boolean
    /** Creates the attested key; returns the public key and the chain (standard base64 DER, leaf first). */
    fun create(alias: String, challenge: ByteArray): Pair<JwkEcPublic, List<String>>
    fun deleteAllExcept(keep: String)
    fun delete(alias: String)
}

/** Facts about this app and phone for the request. */
interface DeviceFacts {
    val appPackage: String
    val appVersion: String
    val signingCertSha256Hex: String
    fun isDeviceOwner(): Boolean
    fun deviceInfo(): DeviceInfoDto
}

/** Where the enrolment stands; persisted so a kill or reboot mid-enrolment resumes with the same key and UUID. */
data class PendingEnrolment(
    val extras: EnrolmentExtras,
    val deviceUuid: String,
    val keyAlias: String? = null,
    val publicKey: JwkEcPublic? = null,
    val chain: List<String> = emptyList(),
) {
    override fun toString() = "PendingEnrolment(device_uuid=$deviceUuid, key=$keyAlias, $extras)"
}

sealed interface EnrolmentState {
    data object NotProvisioned : EnrolmentState
    data class Waiting(val reason: String) : EnrolmentState
    data class Enrolled(val deviceUuid: String, val deviceId: Long, val keyAlias: String) : EnrolmentState
    data class Refused(val code: String) : EnrolmentState
}

/**
 * Enrolment after QR provisioning (docs/24 s10.4 steps 3-4; N-030). The provisioning activity stores the validated extras
 * ([accept]); [run] then creates the device key once (challenge SHA-256(token)), sends `POST /v1/devices/enrol` and, on
 * success, applies the policy before login, records the device UUID and the key alias, deletes any other device key and
 * forgets the token. A retry after a lost response sends the same UUID and key, so the server can recognise it. A refused
 * token is final: the pending key is deleted and the portal must issue a new QR.
 */
class EnrolmentCoordinator(
    private val store: EnrolmentStore,
    private val keys: EnrolmentKeys,
    private val transport: EnrolmentTransport,
    private val facts: DeviceFacts,
    private val newUuid: () -> String,
    private val onEnrolled: suspend (EnrolDeviceResponse, keyAlias: String) -> Unit,
) {
    private val mutex = Mutex()

    /** Stores the provisioning extras; the same token scanned again keeps the UUID and key of the first attempt. */
    fun accept(extras: EnrolmentExtras) {
        store.acceptPending(extras, newUuid)?.let { stale -> keys.delete(stale) }
    }

    /** One run at a time (app start and a background retry must never race: a race could delete the enrolled key). */
    suspend fun run(): EnrolmentState = mutex.withLock { runLocked() }

    private suspend fun runLocked(): EnrolmentState {
        store.enrolled()?.let { done ->
            // A post-enrolment step that failed earlier (policy apply, UUID record) is completed before anything else.
            if (store.postEnrolPending()) store.enrolledResponse()?.let { finish(it, done.keyAlias) }
            return done
        }
        var p = store.pending() ?: return store.refused() ?: EnrolmentState.NotProvisioned
        if (p.keyAlias == null || p.publicKey == null || !keys.exists(p.keyAlias!!)) {
            val alias = keys.newAlias()
            val (jwk, chain) = try {
                keys.create(alias, sha256(p.extras.enrolmentToken))
            } catch (e: Exception) {
                return EnrolmentState.Waiting("key_create_failed")
            }
            // Base64 with line wraps would make a retry differ from the first send; store the canonical form.
            val updated = p.copy(keyAlias = alias, publicKey = jwk, chain = chain.map { c -> c.filterNot { it.isWhitespace() } })
            // Saved only if no new QR replaced the pending enrolment meanwhile.
            if (!store.replacePending(p, updated)) { keys.delete(alias); return EnrolmentState.Waiting("superseded") }
            p = updated
        }
        val request = EnrolDeviceRequest(
            enrolmentToken = p.extras.enrolmentToken,
            deviceUuid = p.deviceUuid,
            appPackage = facts.appPackage,
            appVersion = facts.appVersion,
            appSigningCertSha256 = facts.signingCertSha256Hex,
            deviceOwner = facts.isDeviceOwner(),
            publicKey = p.publicKey!!,
            keyAttestationChain = p.chain,
            deviceInfo = facts.deviceInfo(),
        )
        return when (val r = try { transport.enrol(p.extras.apiBaseUrl, request) } catch (e: Exception) { EnrolCallResult.Retry("transport") }) {
            is EnrolCallResult.Retry -> EnrolmentState.Waiting(r.reason)
            // Only a token refusal is final; any other 4xx (a bad request from an old app build) can be retried later.
            is EnrolCallResult.Refused -> if (!r.code.startsWith("ERR_ENROLMENT_")) EnrolmentState.Waiting("refused:${r.code}") else {
                store.enrolled() ?: run {
                    p.keyAlias?.let { keys.delete(it) }
                    store.saveRefused(r.code)
                    EnrolmentState.Refused(r.code)
                }
            }
            is EnrolCallResult.Enrolled -> {
                if (r.response.deviceUuid != p.deviceUuid) {
                    p.keyAlias?.let { keys.delete(it) }
                    store.saveRefused(DEVICE_UUID_MISMATCH)
                    return EnrolmentState.Refused(DEVICE_UUID_MISMATCH)
                }
                val done = EnrolmentState.Enrolled(p.deviceUuid, r.response.deviceId, p.keyAlias!!)
                // The server has enrolled the phone: record that first (and forget the token), then apply the policy.
                store.saveEnrolled(done, r.response)
                finish(r.response, done.keyAlias)
                runCatching { keys.deleteAllExcept(done.keyAlias) }
                done
            }
        }
    }

    private suspend fun finish(response: EnrolDeviceResponse, alias: String) {
        try {
            onEnrolled(response, alias)
            store.clearPostEnrolPending()
        } catch (e: Exception) {
            // Kept pending: the next run() retries the policy apply; the enrolment itself is not lost.
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    companion object {
        const val DEVICE_UUID_MISMATCH = "device_uuid_mismatch"
    }
}

/**
 * Pending, refused and enrolled state in no-backup storage. The token lives here only between provisioning and a
 * successful or refused enrolment, then it is deleted (it is a one-time credential, never logged).
 */
class EnrolmentStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingFile get() = File(dir, "enrolment-pending.txt")
    private val doneFile get() = File(dir, "enrolment-done.txt")
    private val refusedFile get() = File(dir, "enrolment-refused.txt")

    @Synchronized fun pending(): PendingEnrolment? = runCatching {
        val m = read(pendingFile) ?: return null
        val extras = EnrolmentExtras(m.getValue("token"), m.getValue("api"), m.getValue("env"), m.getValue("flavour"), m.getValue("lockdown"), m["zone"]?.ifEmpty { null })
        PendingEnrolment(
            extras, m.getValue("uuid"), m["alias"]?.ifEmpty { null },
            m["jwk"]?.ifEmpty { null }?.let { json.decodeFromString(JwkEcPublic.serializer(), it) },
            m["chain"]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
        )
    }.getOrNull()

    /**
     * Stores [extras] as the pending enrolment unless the same token is already pending (then nothing changes). Returns
     * the key alias of a replaced pending enrolment, which the caller deletes.
     */
    @Synchronized fun acceptPending(extras: EnrolmentExtras, newUuid: () -> String): String? {
        val current = pending()
        if (current != null && current.extras.enrolmentToken == extras.enrolmentToken) return null
        savePending(PendingEnrolment(extras, newUuid()))
        return current?.keyAlias
    }

    /** Compare-and-set: replaces [expected] with [updated] only if [expected] is still the pending enrolment. */
    @Synchronized fun replacePending(expected: PendingEnrolment, updated: PendingEnrolment): Boolean {
        val now = pending() ?: return false
        if (now.extras.enrolmentToken != expected.extras.enrolmentToken || now.deviceUuid != expected.deviceUuid || now.keyAlias != expected.keyAlias) return false
        savePending(updated)
        return true
    }

    @Synchronized fun savePending(p: PendingEnrolment) {
        refusedFile.delete()
        write(pendingFile, mapOf(
            "token" to p.extras.enrolmentToken, "api" to p.extras.apiBaseUrl, "env" to p.extras.env, "flavour" to p.extras.flavour,
            "lockdown" to p.extras.lockdownLevel, "zone" to (p.extras.zoneCode ?: ""), "uuid" to p.deviceUuid, "alias" to (p.keyAlias ?: ""),
            "jwk" to (p.publicKey?.let { json.encodeToString(JwkEcPublic.serializer(), it) } ?: ""), "chain" to p.chain.joinToString(","),
        ))
    }

    @Synchronized fun enrolled(): EnrolmentState.Enrolled? = runCatching {
        val m = read(doneFile) ?: return null
        EnrolmentState.Enrolled(m.getValue("uuid"), m.getValue("id").toLong(), m.getValue("alias"))
    }.getOrNull()

    @Synchronized fun saveEnrolled(e: EnrolmentState.Enrolled, response: EnrolDeviceResponse) {
        write(doneFile, mapOf("uuid" to e.deviceUuid, "id" to e.deviceId.toString(), "alias" to e.keyAlias,
            "post" to "1", "response" to json.encodeToString(EnrolDeviceResponse.serializer(), response)))
        pendingFile.delete()
    }

    @Synchronized fun postEnrolPending(): Boolean = read(doneFile)?.get("post") == "1"

    @Synchronized fun enrolledResponse(): EnrolDeviceResponse? =
        runCatching { read(doneFile)?.get("response")?.let { json.decodeFromString(EnrolDeviceResponse.serializer(), it) } }.getOrNull()

    /** Marks the post-enrolment steps done and drops the stored response (the policy store keeps the policy). */
    @Synchronized fun clearPostEnrolPending() {
        val m = read(doneFile) ?: return
        write(doneFile, m - "response" + ("post" to "0"))
    }

    @Synchronized fun refused(): EnrolmentState.Refused? = read(refusedFile)?.get("code")?.let { EnrolmentState.Refused(it) }

    @Synchronized fun saveRefused(code: String) {
        write(refusedFile, mapOf("code" to code))
        pendingFile.delete()
    }

    private fun read(f: File): Map<String, String>? = f.takeIf { it.isFile }?.readText()?.split('\n')
        ?.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap()

    private fun write(f: File, m: Map<String, String>) {
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        // One record per line: values never contain line breaks (extras are validated; JSON is single-line).
        tmp.writeText(m.entries.joinToString("\n") { "${it.key}=${it.value.filterNot { c -> c == '\n' || c == '\r' }}" })
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) { "cannot store enrolment state" } }
    }
}

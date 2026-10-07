package com.aktcl.aron.core.sync.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.aktcl.aron.contract.EnrolDeviceRequest
import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.core.geo.integrity.AndroidDeviceKeyStore
import com.aktcl.aron.core.geo.integrity.DeviceKeySpecs
import com.aktcl.aron.core.geo.integrity.DeviceKeyStore
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.TransportFailure
import com.aktcl.aron.core.network.WireJson
import com.aktcl.aron.core.session.DeviceIdentity
import com.aktcl.aron.core.session.EnrolmentOutcome
import com.aktcl.aron.core.session.SessionComponents
import com.aktcl.aron.dpc.DeviceOwnerPolicy
import com.aktcl.aron.dpc.enrolment.EnrolCallResult
import com.aktcl.aron.dpc.enrolment.EnrolDeviceResponse
import com.aktcl.aron.dpc.enrolment.Enrolment
import com.aktcl.aron.dpc.enrolment.EnrolmentCoordinator
import com.aktcl.aron.dpc.enrolment.EnrolmentExtras
import com.aktcl.aron.dpc.enrolment.EnrolmentKeys
import com.aktcl.aron.dpc.enrolment.EnrolmentState
import com.aktcl.aron.dpc.enrolment.EnrolmentStore
import com.aktcl.aron.dpc.enrolment.EnrolmentTransport
import com.aktcl.aron.dpc.enrolment.JwkEcPublic
import com.aktcl.aron.dpc.enrolment.PendingEnrolment
import com.aktcl.aron.dpc.enrolment.ecJwk
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.aktcl.aron.dpc.enrolment.DeviceFacts as EnrolmentFacts

/**
 * Device enrolment for the SR, AMO and TSO apps (docs/24 s10.4; the gate `cfg.device.require_enrolled`): the dpc
 * [EnrolmentCoordinator] wired to the app's API client, the Keystore device key and [DeviceIdentity].
 *
 * Two ways in, one coordinator:
 * - device-owner QR: the provisioning activity stores the QR extras ([Enrolment.install] makes it use this coordinator);
 *   [install] runs the pending enrolment at app start, the enrolment screen retries it;
 * - a normally installed phone: the enrolment screen hands the token (typed, pasted or the portal's QR text) to [submit].
 *
 * On success the server's `device_uuid` becomes the phone's [DeviceIdentity] (login and `X-Device-Id` use it) and, on a
 * device-owner phone, the policy is applied. Only reached before the first login of a new install: no sale waits for it.
 * The token is never logged; it leaves the phone's storage once the server answered.
 */
class DeviceEnrolment(
    private val store: EnrolmentStore,
    private val keys: EnrolmentKeys,
    transport: EnrolmentTransport,
    private val facts: EnrolmentFacts,
    private val identity: DeviceIdentity,
    /** The API origin this build talks to (`https://host`); enrolment always goes there (see [ApiEnrolmentTransport]). */
    private val apiOrigin: String,
    private val applyPolicy: (EnrolDeviceResponse) -> Unit,
) {
    /**
     * The phone keeps the UUID it already sends as `X-Device-Id` (a session signed in on a dev server without the gate
     * stays valid). Only after the server refused that UUID itself (revoked or taken) does a new token get a fresh one.
     */
    private fun uuidForNewToken(): String =
        if (store.refused()?.code in UUID_REFUSALS) ClientIds.newUuid() else identity.deviceUuid

    val coordinator = EnrolmentCoordinator(store, keys, transport, facts, ::uuidForNewToken) { response, _ ->
        // The UUID first: the server knows the phone by it from now on, whatever happens to the policy apply.
        identity.setEnrolledUuid(response.deviceUuid)
        if (facts.isDeviceOwner()) applyPolicy(response)
    }

    /** True once the server has enrolled this phone (the DPC's done marker). */
    fun enrolled(): Boolean = store.enrolled() != null

    /** A token or QR is stored and waits for the server (the screen shows "enrolling" and retries). */
    fun pending(): Boolean = store.pending() != null

    /** The last final refusal, while no newer token is pending. */
    fun refusedCode(): String? = if (pending()) null else store.refused()?.code

    /**
     * Application.onCreate: makes the provisioning activity use this coordinator and start the enrolment as soon as it has
     * stored the QR extras (same process); then, off the main thread, repairs the device UUID if a kill came between the
     * done marker and [DeviceIdentity.setEnrolledUuid] and runs a pending enrolment. Nothing runs when there is nothing to do.
     */
    fun install(scope: CoroutineScope): Job {
        Enrolment.install(coordinator) { scope.launch { runCatching { submit(null) } } }
        return scope.launch {
            reconcile()
            if (pending() || store.postEnrolPending()) runCatching { submit(null) }
        }
    }

    /** The done marker is the truth: the phone's UUID follows it. */
    fun reconcile() {
        val done = store.enrolled() ?: return
        runCatching { if (identity.deviceUuid != done.deviceUuid) identity.setEnrolledUuid(done.deviceUuid) }
    }

    /**
     * The enrolment screen: [input] is a token or the portal's QR text; null retries the pending one. The same token sent
     * again keeps the UUID and key of the first attempt (a lost response is recognised by the server).
     */
    suspend fun submit(input: String?): EnrolmentOutcome {
        if (input != null) {
            val extras = when (val r = readInput(input, facts.appPackage, apiOrigin)) {
                is Input.Ok -> r.extras
                is Input.Bad -> return EnrolmentOutcome.Unreadable(r.reason)
            }
            coordinator.accept(extras)
        }
        val sent = store.pending()
        return when (val s = coordinator.run()) {
            is EnrolmentState.Enrolled -> { reconcile(); EnrolmentOutcome.Enrolled }
            is EnrolmentState.Waiting -> {
                val code = s.reason.removePrefix("refused:").takeIf { s.reason.startsWith("refused:") }
                when {
                    code == null -> EnrolmentOutcome.Waiting(offline = s.reason in OFFLINE_REASONS)
                    // The server's last word on this phone or request: the token leaves the phone like a refused token.
                    code in FINAL_REFUSALS -> { sent?.let { dropPending(it, code) }; EnrolmentOutcome.Refused(code) }
                    else -> EnrolmentOutcome.Refused(code)
                }
            }
            is EnrolmentState.Refused -> EnrolmentOutcome.Refused(s.code)
            EnrolmentState.NotProvisioned -> EnrolmentOutcome.Unreadable("no_token")
        }
    }

    /** Drops [sent] only if it is still the pending enrolment (a newer token entered meanwhile is kept). */
    private fun dropPending(sent: PendingEnrolment, code: String) {
        synchronized(store) { // the store's own monitor: no accept() can slip in between the check and the drop
            val now = store.pending() ?: return
            if (store.enrolled() != null || now.extras.enrolmentToken != sent.extras.enrolmentToken || now.deviceUuid != sent.deviceUuid) return
            now.keyAlias?.let { runCatching { keys.delete(it) } }
            store.saveRefused(code)
        }
    }

    sealed interface Input {
        data class Ok(val extras: EnrolmentExtras) : Input
        data class Bad(val reason: String) : Input
    }

    companion object {
        private const val QR_EXTRAS = "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
        private val TOKEN = Regex("^[A-Za-z0-9_-]{43,64}$")
        private val OFFLINE_REASONS = setOf("offline", "timeout", "transport", "edge_response")
        /** Refusals of the UUID itself: the next token is sent with a fresh UUID. */
        private val UUID_REFUSALS = setOf("ERR_DEVICE_REVOKED", "ERR_CONFLICT")
        /** Answers no retry of the same request can change (besides the coordinator's ERR_ENROLMENT_* ones). */
        private val FINAL_REFUSALS = UUID_REFUSALS + "ERR_VALIDATION"
        /** Local refusal: the app's own signing certificate could not be read, so nothing is sent. */
        const val ERR_APP_SIGNATURE_UNREADABLE = "ERR_ENROLMENT_APP_SIGNATURE_UNREADABLE"

        /**
         * A bare token (43 to 64 base64url characters, spaces and line breaks ignored), or the portal's provisioning QR
         * text (JSON with the `aron.*` admin extras, validated as the device-owner path validates them).
         */
        fun readInput(input: String, ownPackage: String, apiOrigin: String): Input {
            val text = input.trim()
            if (text.startsWith("{")) {
                val bundle = runCatching { Json.parseToJsonElement(text).jsonObject[QR_EXTRAS]?.jsonObject }.getOrNull()
                    ?: return Input.Bad("qr_unreadable")
                var why = "qr_invalid"
                val extras = EnrolmentExtras.parse({ k -> runCatching { bundle[k]?.jsonPrimitive?.content }.getOrNull() }, ownPackage) { why = it }
                    ?: return Input.Bad(why)
                // A QR of another server (a production token scanned into a dev build) is refused before any call.
                if (!sameOrigin(extras.apiBaseUrl, apiOrigin)) return Input.Bad("other_server")
                return Input.Ok(extras)
            }
            val token = text.filterNot { it.isWhitespace() }
            if (!TOKEN.matches(token)) return Input.Bad("token_malformed")
            val flavour = ownPackage.substringAfterLast('.')
            // A bare token carries no extras: env is unknown here and the lockdown level is the server's (from the token
            // row, returned in the response). Both fields are stored for the record only.
            return Input.Ok(EnrolmentExtras(token, apiOrigin.trimEnd('/'), env = "", flavour = flavour, lockdownLevel = "dev", zoneCode = null))
        }

        /** Scheme, host (any case) and effective port. */
        fun sameOrigin(a: String, b: String): Boolean {
            fun key(u: String) = runCatching {
                val uri = java.net.URI(u.trim().trimEnd('/'))
                val scheme = uri.scheme?.lowercase() ?: return@runCatching null
                val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else if (scheme == "http") 80 else -1
                Triple(scheme, uri.host?.lowercase(), port)
            }.getOrNull()
            return key(a)?.let { it == key(b) } ?: false
        }

        /** The production wiring, one per process (the app's DI). */
        fun create(context: Context, components: SessionComponents): DeviceEnrolment {
            val app = context.applicationContext
            return DeviceEnrolment(
                Enrolment.pendingStore(app), KeystoreEnrolmentKeys(AndroidDeviceKeyStore(app)), ApiEnrolmentTransport(components.apiClient),
                AndroidEnrolmentFacts(app, components.appVersion), components.deviceIdentity, components.apiClient.origin.origin,
                applyPolicy = { r -> DeviceOwnerPolicy.get(app).receive(r.policy) },
            )
        }
    }
}

/** The dpc key port over the core-geo Keystore device key. */
class KeystoreEnrolmentKeys(private val keys: DeviceKeyStore) : EnrolmentKeys {
    override fun newAlias(): String = DeviceKeySpecs.newAlias()
    override fun exists(alias: String): Boolean = keys.exists(alias)
    override fun create(alias: String, challenge: ByteArray): Pair<JwkEcPublic, List<String>> =
        keys.create(alias, challenge).let { k -> ecJwk(k.publicKey.x, k.publicKey.y) to k.chain }
    override fun deleteAllExcept(keep: String) = keys.deleteAllExcept(keep)
    override fun delete(alias: String) = keys.delete(alias)
}

/**
 * `POST /v1/devices/enrol` on the app's API client (unauthenticated: the token is the credential). The request is never
 * logged. It always goes to the client's origin, where this build logs in and syncs: an enrolment on another server (a
 * provisioning QR naming another origin) would leave the phone unknown where it works, and the token would leave for a
 * server the build does not trust. A 4xx with a problem code is the server's final word on this request; 408, 429, 5xx, an edge page or no
 * network are retried later with the same request.
 */
class ApiEnrolmentTransport(private val client: AronApiClient) : EnrolmentTransport {
    override suspend fun enrol(apiBaseUrl: String, request: EnrolDeviceRequest): EnrolCallResult {
        // Without the signing digest the server can only refuse; nothing is sent and the token is dropped.
        if (!SHA256_HEX.matches(request.appSigningCertSha256)) return EnrolCallResult.Refused(DeviceEnrolment.ERR_APP_SIGNATURE_UNREADABLE)
        val r = client.call(
            path = "/v1/devices/enrol",
            auth = CallAuth.None,
            // Short, like login: a weak network gives the screen its "try again" quickly instead of a long wait.
            callTimeoutS = CALL_TIMEOUT_S,
            build = { post(WireJson.requests.encodeToString(EnrolDeviceRequest.serializer(), request).toRequestBody(JSON)) },
            decode = { body, _ -> WireJson.responses.decodeFromString(EnrolDeviceResponse.serializer(), body) },
        )
        return when (r) {
            is ApiResult.Success -> EnrolCallResult.Enrolled(r.value)
            is ApiResult.Failure -> {
                val code = r.problem.code
                if (r.httpStatus in 400..499 && r.httpStatus != 408 && r.httpStatus != 429 && code != null) EnrolCallResult.Refused(code)
                else EnrolCallResult.Retry("http_${r.httpStatus}")
            }
            is ApiResult.Transport -> EnrolCallResult.Retry(
                when (r.failure) {
                    TransportFailure.OFFLINE -> "offline"
                    TransportFailure.TIMEOUT -> "timeout"
                    TransportFailure.EDGE_RESPONSE -> "edge_response"
                    TransportFailure.MALFORMED -> "malformed"
                },
            )
            is ApiResult.NotModified -> EnrolCallResult.Retry("unexpected_304")
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        const val CALL_TIMEOUT_S = 20L
    }
}

/** This app and phone for the enrolment request. */
class AndroidEnrolmentFacts(context: Context, override val appVersion: String) : EnrolmentFacts {
    private val app = context.applicationContext
    override val appPackage: String = app.packageName
    override val signingCertSha256Hex: String by lazy { currentSignerSha256(app).orEmpty() }
    override fun isDeviceOwner(): Boolean =
        runCatching { app.getSystemService(android.app.admin.DevicePolicyManager::class.java)?.isDeviceOwnerApp(app.packageName) == true }.getOrDefault(false)
    override fun deviceInfo() = AndroidDeviceFacts.deviceInfo(app)

    companion object {
        /** SHA-256 (lower-case hex) of the app's current signing certificate (the one the key attestation names). */
        @Suppress("DEPRECATION")
        @SuppressLint("PackageManagerGetSignatures")
        fun currentSignerSha256(context: Context): String? = runCatching {
            fun hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= 28) {
                val si = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
                val sig = if (si.hasMultipleSigners()) si.apkContentsSigners.firstOrNull() else si.signingCertificateHistory.lastOrNull()
                sig?.toByteArray()?.let(::hex)
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()?.let(::hex)
            }
        }.getOrNull()
    }
}

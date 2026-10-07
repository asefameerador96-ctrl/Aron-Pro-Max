package com.aktcl.aron.core.geo.integrity

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityException
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Why no Play Integrity token was produced. Sent as the explicit marker (REQUEST: android-geo-dpc-integrity-marker). */
enum class IntegrityUnavailable(val wire: String) {
    NO_PLAY_SERVICES("no_play_services"),
    NOT_CONFIGURED("not_configured"),
    OFFLINE("offline"),
    API_ERROR("api_error"),
    TIMEOUT("timeout"),
}

/** The outcome of one integrity attempt; never an exception. */
sealed interface IntegrityResult {
    /** Contract `PlayIntegrityEvidence`. */
    data class Evidence(val token: String, val nonce: String) : IntegrityResult
    data class Unavailable(val reason: IntegrityUnavailable, val detail: String? = null) : IntegrityResult
}

/** Produces a Play Integrity token bound to [requestHash]. */
interface IntegrityTokenSource {
    suspend fun token(requestHash: String): IntegrityResult
}

/**
 * Play Integrity standard requests (docs/24 s8.7). The provider is prepared once per process (warm-up) and re-prepared
 * after an error. [cloudProjectNumber] is the Google Cloud project number (not a secret); 0 means not configured.
 */
class PlayIntegritySource(
    context: Context,
    private val cloudProjectNumber: Long,
    private val timeoutMs: Long = 20_000,
) : IntegrityTokenSource {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private var provider: StandardIntegrityTokenProvider? = null

    override suspend fun token(requestHash: String): IntegrityResult = mutex.withLock {
        if (cloudProjectNumber <= 0) return@withLock IntegrityResult.Unavailable(IntegrityUnavailable.NOT_CONFIGURED)
        val gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app)
        if (gms != ConnectionResult.SUCCESS) return@withLock IntegrityResult.Unavailable(IntegrityUnavailable.NO_PLAY_SERVICES, "gms=$gms")
        val result = withTimeoutOrNull(timeoutMs) {
            try {
                val p = provider ?: IntegrityManagerFactory.createStandard(app)
                    .prepareIntegrityToken(PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build())
                    .await().also { provider = it }
                val t = p.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build()).await()
                IntegrityResult.Evidence(t.token(), "") // nonce filled by the caller
            } catch (e: CancellationException) {
                throw e
            } catch (e: StandardIntegrityException) {
                provider = null
                IntegrityResult.Unavailable(IntegrityUnavailable.API_ERROR, "code=${e.errorCode}")
            } catch (e: Exception) {
                provider = null
                IntegrityResult.Unavailable(IntegrityUnavailable.API_ERROR, e.javaClass.simpleName)
            }
        }
        result ?: IntegrityResult.Unavailable(IntegrityUnavailable.TIMEOUT)
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
    // A task cancelled by Play services is an API failure, not a cancellation of the caller's coroutine.
    addOnCanceledListener { if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("integrity task cancelled"))) }
}

/** A single-use server nonce (contract `DeviceNonce`, `POST /v1/devices/nonce`); null when offline or refused. */
fun interface NonceSource {
    suspend fun nonce(): String?
}

/**
 * Gets Play Integrity evidence for a status report at login, enrolment, check-in and every `cfg.device.integrity_refresh_h`
 * (docs/24 s8.7). Never throws and never blocks a sale: offline, no Play services or an API error give an explicit
 * [IntegrityResult.Unavailable] marker, and the report goes out without a token.
 */
class IntegrityEvidenceService(
    private val nonces: NonceSource,
    private val tokens: IntegrityTokenSource,
    private val deviceUuid: () -> String,
) {
    suspend fun collect(): IntegrityResult {
        val nonce = try { nonces.nonce() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            ?: return IntegrityResult.Unavailable(IntegrityUnavailable.OFFLINE)
        return try {
            when (val r = tokens.token(IntegrityCodec.requestHash(nonce, deviceUuid()))) {
                is IntegrityResult.Evidence -> r.copy(nonce = nonce)
                is IntegrityResult.Unavailable -> r
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IntegrityResult.Unavailable(IntegrityUnavailable.API_ERROR, e.javaClass.simpleName)
        }
    }

    companion object {
        /** True when the last token is older than [refreshH] hours, or there is none, or the clock went backwards. */
        fun due(lastTokenAtMs: Long?, nowMs: Long, refreshH: Int): Boolean =
            lastTokenAtMs == null || nowMs < lastTokenAtMs || nowMs - lastTokenAtMs >= refreshH.coerceIn(1, 168) * 3_600_000L
    }
}

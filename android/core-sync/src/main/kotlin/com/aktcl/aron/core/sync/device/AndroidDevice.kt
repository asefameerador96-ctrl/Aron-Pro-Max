package com.aktcl.aron.core.sync.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import com.aktcl.aron.contract.DeviceInfo
import com.aktcl.aron.core.geo.integrity.DeviceKeyStore
import com.aktcl.aron.core.geo.integrity.NonceSource
import com.aktcl.aron.core.network.ApiResult
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.CallAuth
import com.aktcl.aron.core.network.DeviceProofSigner
import com.aktcl.aron.core.network.Grant
import com.aktcl.aron.core.network.WireJson
import com.aktcl.aron.dpc.DeviceOwnerPolicy
import com.aktcl.aron.dpc.blocking.BlockingStore
import com.aktcl.aron.dpc.enrolment.EnrolmentStore
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.TimeZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** [DeviceFacts] from the system services and the device-owner policy (docs/24 s10.3). Every read is guarded: a fact that cannot be read is null or a safe default. */
class AndroidDeviceFacts(context: Context, private val policy: () -> DeviceOwnerPolicy) : DeviceFacts {
    private val app = context.applicationContext
    // Read-only view of the blocking state the DPC persists (evaluating it here would change what it reports).
    private val blockingStore = BlockingStore(File(app.noBackupFilesDir, "dpc"))

    override fun read(): DeviceFactsSnapshot {
        val p = runCatching(policy).getOrNull()
        val applied = p?.lastReport
        val blocking = runCatching { blockingStore.load() }.getOrNull()
        val battery = runCatching { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val lm = app.getSystemService(LocationManager::class.java)
        // isLocationEnabled is API 28; the 8.x phones (minSdk 26) read the GPS and network providers instead.
        val locationOn = runCatching {
            if (lm == null) false
            else if (android.os.Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        return DeviceFactsSnapshot(
            deviceInfo = deviceInfo(app),
            deviceOwner = runCatching { app.getSystemService(android.app.admin.DevicePolicyManager::class.java)?.isDeviceOwnerApp(app.packageName) == true }.getOrDefault(false),
            lockdownLevelApplied = applied?.lockdownLevel?.takeIf { it == "dev" || it == "prod" } ?: "dev",
            policyVersionApplied = applied?.policyVersion,
            policyApplyErrors = applied?.errors.orEmpty(),
            restrictionsApplied = applied?.restrictionsApplied,
            batteryOptimisationIgnored = runCatching { app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) }.getOrNull(),
            blockingActive = blocking?.activeSince != null,
            blockingSinceMs = blocking?.activeSince,
            suspendedPackages = blocking?.suspended,
            locationEnabled = locationOn,
            locationMode = if (locationOn) "unknown" else "off",
            timeZone = TimeZone.getDefault().id,
            playServicesVersion = runCatching {
                @Suppress("DEPRECATION") app.packageManager.getPackageInfo("com.google.android.gms", 0).versionCode.takeIf { it > 0 }
            }.getOrNull(),
            batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0,
            charging = plugged?.let { it > 0 },
            freeStorageMb = runCatching { (StatFs(app.filesDir.absolutePath).availableBytes / (1024 * 1024)).toInt() }.getOrNull(),
        )
    }

    companion object {
        /** Contract `DeviceInfo` of this phone (also used by enrolment). */
        fun deviceInfo(context: Context): DeviceInfo {
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
            val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" } ?: "universal"
            return DeviceInfo(
                manufacturer = Build.MANUFACTURER.orEmpty().take(60).ifEmpty { "unknown" },
                model = Build.MODEL.orEmpty().take(80).ifEmpty { "unknown" },
                osApiLevel = Build.VERSION.SDK_INT.coerceIn(26, 99),
                osVersion = Build.VERSION.RELEASE.orEmpty().take(20).ifEmpty { "unknown" },
                securityPatch = Build.VERSION.SECURITY_PATCH?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) },
                abi = abi,
                ramMb = (mem.totalMem / (1024 * 1024)).toInt().coerceIn(256, 65536),
                storageTotalMb = runCatching { (StatFs(context.filesDir.absolutePath).totalBytes / (1024 * 1024)).toInt() }.getOrNull(),
            )
        }
    }
}

/** [IntegrityState] in the app's no-backup SharedPreferences (device-wide, survives a kill). */
class PrefsIntegrityState(context: Context) : IntegrityState {
    private val prefs = context.applicationContext.getSharedPreferences("aron-integrity", Context.MODE_PRIVATE)
    private fun long(key: String) = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    private fun put(key: String, v: Long?) = prefs.edit().apply { if (v == null) remove(key) else putLong(key, v) }.apply()

    override var lastTokenAtMs: Long? get() = long("token_at"); set(v) = put("token_at", v)
    override var lastAttemptAtMs: Long? get() = long("attempt_at"); set(v) = put("attempt_at", v)
    override var integrityRef: String? get() = prefs.getString("ref", null); set(v) = prefs.edit().putString("ref", v).apply()
    override var evidenceWanted: String? get() = prefs.getString("wanted_for", null); set(v) = prefs.edit().putString("wanted_for", v).apply()
}

/** Contract `DeviceNonce`; not generated in shared:contract yet. */
@Serializable
data class DeviceNonceDto(val nonce: String, @SerialName("expires_at") val expiresAt: String)

/** `POST /v1/devices/nonce` (contract `DeviceNonce`): a single-use nonce for a Play Integrity request; null when it cannot be had. */
class DeviceNonceApi(private val client: AronApiClient) : NonceSource {
    override suspend fun nonce(): String? = when (
        val r = client.call(
            path = "/v1/devices/nonce",
            auth = CallAuth.Grant(Grant.FULL),
            build = { post(ByteArray(0).toRequestBody(null)) },
            decode = { body, _ -> WireJson.responses.decodeFromString(DeviceNonceDto.serializer(), body).nonce },
        )
    ) {
        is ApiResult.Success -> r.value
        else -> null
    }
}

/**
 * The `X-Device-Proof` signer (docs/24 s8.3) over the enrolled device key (android-geo-dpc N-026, N-030). Before enrolment
 * (a dev phone) there is no key and calls go out without the header.
 */
class KeystoreProofSigner(private val keys: DeviceKeyStore, private val alias: () -> String?) : DeviceProofSigner {
    override fun sign(proofString: String): String? {
        val a = runCatching(alias).getOrNull() ?: return null
        return keys.sign(a, proofString.toByteArray(Charsets.UTF_8))
    }

    companion object {
        /** The alias the enrolment stored in the DPC's no-backup folder. */
        fun enrolledAlias(context: Context): () -> String? {
            val store = EnrolmentStore(File(context.applicationContext.noBackupFilesDir, "dpc"))
            return { store.enrolled()?.keyAlias }
        }
    }
}

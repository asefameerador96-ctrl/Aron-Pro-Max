package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.OffsetDateTime

@Serializable data class MinVersionCodes(val sr: Int, val amo: Int, val tso: Int)
@Serializable data class BannerDto(val bn: String, val en: String, val severity: String)
@Serializable data class SupportContactDto(val label_en: String, val phone: String, val label_bn: String? = null, val hours: String? = null)
@Serializable data class PublicConfigDto(val min_version_codes: MinVersionCodes, val maintenance_banner: BannerDto?, val support_contacts: List<SupportContactDto>, val server_time: String)

@Serializable
data class AppReleaseDto(
    val release_id: Long, val flavour: String, val version_name: String, val version_code: Int, val abi: String, val sha256: String, val size_bytes: Int,
    val download_url: String, val signing_cert_sha256: String, val status: String, val rollout_pct: Int, val notes_en: String?, val notes_bn: String?,
    val created_at: String, val published_at: String?, val version: Int,
)

@Serializable
data class UpdateCheckDto(val update_available: Boolean, val blocked: Boolean, val min_version_code: Int, val latest: AppReleaseDto?, val prompt_policy: String, val wifi_only: Boolean)

/**
 * `GET /v1/config/public` (F-API-042, unauthenticated, cached by Front Door for 60 s, no user data) and
 * `GET /v1/app/update-check` (update decision for one flavour and ABI, rollout by a stable device hash).
 */
class ConfigPublic(private val db: Database, private val resolver: ConfigResolver, private val clock: AronClock = AronClock.SYSTEM, private val supportContacts: () -> List<SupportContactDto> = { emptyList() }) {
    private val global = listOf(ScopeNode("global", 0))
    private fun value(key: String): JsonElement = resolver.resolve(key, global, clock.now()).value

    fun publicConfig(): PublicConfigDto {
        val m = value("cfg.release.min_version_code") as? JsonObject
        fun code(f: String) = (m?.get(f) as? JsonPrimitive)?.intOrNull?.coerceAtLeast(1) ?: 1
        val banner = (value("cfg.ops.maintenance_banner") as? JsonObject)?.let { b ->
            val bn = (b["bn"] as? JsonPrimitive)?.content ?: (b["en"] as? JsonPrimitive)?.content
            val en = (b["en"] as? JsonPrimitive)?.content ?: bn
            if (bn == null || en == null) null else BannerDto(bn.take(200), en.take(200), (b["severity"] as? JsonPrimitive)?.content?.takeIf { it in setOf("info", "warning", "critical") } ?: "info")
        }
        return PublicConfigDto(MinVersionCodes(code("sr"), code("amo"), code("tso")), banner, supportContacts(), clock.now().wire())
    }

    fun updateCheck(flavour: String, versionCode: Int, abi: String, deviceUuid: String?): UpdateCheckDto {
        if (flavour !in setOf("sr", "amo", "tso")) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad flavour", errors = listOf(FieldError("query.flavour", "invalid_value")))
        if (abi !in setOf("universal", "arm64-v8a", "armeabi-v7a")) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad abi", errors = listOf(FieldError("query.abi", "invalid_value")))
        if (versionCode !in 1..2100000000) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad version_code", errors = listOf(FieldError("query.version_code", "out_of_range")))
        val min = ((value("cfg.release.min_version_code") as? JsonObject)?.get(flavour) as? JsonPrimitive)?.intOrNull ?: 1
        val blocked = ((value("cfg.release.blocked_version_codes") as? JsonArray)?.any { (it as? JsonPrimitive)?.intOrNull == versionCode }) ?: false
        val policy = (value("cfg.release.update_prompt_policy") as? JsonPrimitive)?.content?.takeIf { it in setOf("silent", "prompt", "force_after_date") } ?: "prompt"
        val wifi = (value("cfg.release.update_wifi_only") as? JsonPrimitive)?.content == "true"
        val latest = db.jdbi.withHandle<List<AppReleaseDto>, Exception> { h ->
            h.createQuery(
                "SELECT * FROM app.app_release WHERE flavour = :f AND status = 'published' AND version_code > :v AND abi IN (:abi, 'universal') ORDER BY version_code DESC, (abi = :abi) DESC LIMIT 5",
            ).bind("f", flavour).bind("v", versionCode).bind("abi", abi).map { rs, _ ->
                AppReleaseDto(
                    rs.getLong("id"), flavour, rs.getString("version_name"), rs.getInt("version_code"), rs.getString("abi"), hex(rs.getBytes("sha256")), rs.getInt("size_bytes"),
                    rs.getString("download_url"), hex(rs.getBytes("signing_cert_sha256")), rs.getString("status"), rs.getInt("rollout_pct"), rs.getString("notes_en"), rs.getString("notes_bn"),
                    rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(), rs.getObject("published_at", OffsetDateTime::class.java)?.toInstant()?.wire(), rs.getInt("version"),
                )
            }.list()
        }.firstOrNull { inRollout(it, deviceUuid) }
        return UpdateCheckDto(latest != null, blocked, min, latest, policy, wifi)
    }

    /** Staged exposure: a stable hash of device and release decides, so a phone's answer never flips between calls. */
    private fun inRollout(r: AppReleaseDto, deviceUuid: String?): Boolean {
        if (r.rollout_pct >= 100) return true
        if (r.rollout_pct <= 0 || deviceUuid == null) return false
        val d = MessageDigest.getInstance("SHA-256").digest("${r.release_id}:$deviceUuid".toByteArray())
        return ((d[0].toInt() and 0xff) * 256 + (d[1].toInt() and 0xff)) % 100 < r.rollout_pct
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

class ConfigPublicDeps(val public: ConfigPublic, val guard: AuthGuardDeps)

/** Public config is unauthenticated; the update check needs a signed-in phone. */
fun Route.configPublicRoutes(d: ConfigPublicDeps) {
    get("/config/public") {
        call.response.header(HttpHeaders.CacheControl, "public, max-age=60")
        call.respond(d.public.publicConfig())
    }
    authenticated(d.guard) {
        get("/app/update-check") {
            val q = call.request.queryParameters
            val vc = q["version_code"]?.toIntOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "version_code is required", errors = listOf(FieldError("query.version_code", "required")))
            call.respond(d.public.updateCheck(q["flavour"] ?: "", vc, q["abi"] ?: "", call.principal.deviceUuid))
        }
    }
}

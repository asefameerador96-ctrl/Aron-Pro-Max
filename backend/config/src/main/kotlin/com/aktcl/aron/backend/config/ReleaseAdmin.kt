package com.aktcl.aron.backend.config

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

@Serializable
data class AppReleaseWriteIn(
    val flavour: String, val version_name: String, val version_code: Int, val abi: String, val sha256: String, val size_bytes: Int,
    val download_url: String, val signing_cert_sha256: String, val notes_en: String? = null, val notes_bn: String? = null,
)

/** Contract `AppReleasePatch`; a member left out is unchanged (`notes_*` present as null clears the note). */
@Serializable
data class AppReleasePatchIn(
    val status: String? = null, val rollout_pct: Int? = null, val notes_en: String? = null, val notes_bn: String? = null, val change_reason: String? = null,
)

@Serializable data class AppReleasePageDto(val items: List<AppReleaseDto>, val next_cursor: String?)
@Serializable data class ReleasePolicyDto(val flavour: String, val min_version_code: Int, val latest_version_code: Int?, val blocked_version_codes: List<Int>, val devices_below_min: Int)
@Serializable data class ReleasePolicyListDto(val items: List<ReleasePolicyDto>)

/**
 * `admin-releases` (docs/24 s8 matrix "Releases": SUPPORT reads, ADMIN registers drafts, SUPERADMIN publishes). CI registers
 * a signed APK as a draft; a second person publishes it (maker-checker, `published_by <> created_by` in the table too);
 * the phones see it through `GET /v1/app/update-check` (published, staged by `rollout_pct`). The signing certificate is
 * checked against [expectedCertSha256] (the release key's SHA-256, from the server's environment) when one is configured.
 */
class ReleaseAdmin(
    private val db: Database,
    private val resolver: ConfigResolver,
    private val clock: AronClock = AronClock.SYSTEM,
    private val expectedCertSha256: String? = System.getenv("ARON_RELEASE_CERT_SHA256")?.trim()?.lowercase()?.takeIf { HEX64.matches(it) },
) {
    private fun value(key: String) = resolver.resolve(key, listOf(ScopeNode("global", 0)), clock.now()).value

    fun list(p: AronPrincipal, flavour: String?, limit: Int, cursor: String?): AppReleasePageDto {
        if (p.role !in READERS) throw forbidden()
        if (flavour != null && flavour !in FLAVOURS) bad("query.flavour")
        val after = cursor?.let { decodeCursor(it) }
        return db.jdbi.withHandle<AppReleasePageDto, Exception> { h ->
            val rows = h.createQuery(
                "SELECT * FROM app.app_release WHERE (CAST(:f AS text) IS NULL OR flavour = :f) AND (CAST(:c AS bigint) IS NULL OR id < :c) ORDER BY id DESC LIMIT :lim",
            ).bind("f", flavour).bind("c", after).bind("lim", limit + 1).map { rs, _ -> dto(rs) }.list()
            AppReleasePageDto(rows.take(limit), if (rows.size > limit) encodeCursor(rows[limit - 1].release_id) else null)
        }
    }

    fun requireWriter(p: AronPrincipal) { if (p.role !in WRITERS) throw forbidden() }

    /** Registers a draft. A repeat of the same registration (CI retry: same APK hash and fields) answers the existing draft. */
    fun create(p: AronPrincipal, w: AppReleaseWriteIn, requestId: String?): AppReleaseDto {
        if (p.role !in WRITERS) throw forbidden()
        if (w.flavour !in FLAVOURS) bad("/flavour")
        if (!VERSION_NAME.matches(w.version_name)) bad("/version_name")
        if (w.version_code !in 1..2_100_000_000) bad("/version_code", "out_of_range")
        if (w.abi !in ABIS) bad("/abi")
        val sha = w.sha256.lowercase().takeIf { HEX64.matches(it) } ?: bad("/sha256")
        val cert = w.signing_cert_sha256.lowercase().takeIf { HEX64.matches(it) } ?: bad("/signing_cert_sha256")
        if (expectedCertSha256 != null && cert != expectedCertSha256) bad("/signing_cert_sha256", "not_release_key", "the APK is not signed with the release key")
        val maxMb = (value("cfg.release.apk_max_mb") as? JsonPrimitive)?.intOrNull ?: 30
        if (w.size_bytes < 1 || w.size_bytes.toLong() > maxMb.toLong() * 1024 * 1024 || w.size_bytes > 104_857_600) bad("/size_bytes", "out_of_range")
        val url = runCatching { java.net.URI(w.download_url) }.getOrNull()
        if (w.download_url.length > 1000 || url?.scheme != "https" || url.host.isNullOrBlank()) bad("/download_url")
        w.notes_en?.let { if (it.length > 2000) bad("/notes_en", "too_long") }
        w.notes_bn?.let { if (it.length > 2000) bad("/notes_bn", "too_long") }
        return db.jdbi.inTransaction<AppReleaseDto, Exception> { h ->
            h.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 11))", "release:${w.flavour}:${w.version_code}:${w.abi}")
            val same = h.createQuery("SELECT * FROM app.app_release WHERE sha256 = decode(:s, 'hex') OR (flavour = :f AND version_code = :c AND abi = :a)")
                .bind("s", sha).bind("f", w.flavour).bind("c", w.version_code).bind("a", w.abi).map { rs, _ -> dto(rs) }.list()
            same.firstOrNull()?.let { e ->
                val replay = same.size == 1 && e.sha256 == sha && e.flavour == w.flavour && e.version_code == w.version_code && e.abi == w.abi &&
                    e.version_name == w.version_name && e.signing_cert_sha256 == cert && e.size_bytes == w.size_bytes && e.download_url == w.download_url
                if (replay) return@inTransaction e
                throw ApiProblem(ProblemCode.ERR_CONFLICT, "a release with this APK hash or this flavour, version code and ABI exists", context = mapOf("release_id" to JsonPrimitive(e.release_id)))
            }
            val id = try { h.createQuery(
                """
                INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url, signing_cert_sha256, notes_en, notes_bn, created_by)
                VALUES (:f, :n, :c, :a, decode(:s, 'hex'), :b, :u, decode(:k, 'hex'), :ne, :nb, :by) RETURNING id
                """.trimIndent(),
            ).bind("f", w.flavour).bind("n", w.version_name).bind("c", w.version_code).bind("a", w.abi).bind("s", sha).bind("b", w.size_bytes)
                .bind("u", w.download_url).bind("k", cert).bind("ne", w.notes_en).bind("nb", w.notes_bn).bind("by", p.userId).mapTo(Long::class.java).one()
            } catch (e: org.jdbi.v3.core.statement.UnableToExecuteStatementException) {
                // A concurrent registration of the same APK under another code (the lock covers flavour, code and ABI only).
                if ((e.cause as? java.sql.SQLException)?.sqlState == "23505") throw ApiProblem(ProblemCode.ERR_CONFLICT, "a release with this APK hash exists")
                throw e
            }
            val out = one(h, id)!!
            AuditWriter.write(h, p, "app_release", id.toString(), "create", null, audit(out), null, requestId)
            out
        }
    }

    /**
     * Status moves: draft to published (SUPERADMIN, never the release's author), published to blocked or retired, blocked to
     * published or retired, draft to retired; retired is final. ADMIN may only edit the notes and a draft's rollout.
     * `patchedNotes` lists the note members present in the body (null clears).
     */
    fun patch(p: AronPrincipal, id: Long, ifMatch: Int, req: AppReleasePatchIn, patchedNotes: Set<String>, requestId: String?): AppReleaseDto {
        if (p.role !in WRITERS) throw forbidden()
        if (req.status == null && req.rollout_pct == null && patchedNotes.isEmpty()) bad("body", "empty")
        req.status?.let { if (it !in setOf("published", "blocked", "retired")) bad("/status") }
        req.rollout_pct?.let { if (it !in 0..100) bad("/rollout_pct", "out_of_range") }
        req.notes_en?.let { if (it.length > 2000) bad("/notes_en", "too_long") }
        req.notes_bn?.let { if (it.length > 2000) bad("/notes_bn", "too_long") }
        req.change_reason?.let { if (it.length !in 10..500) bad("/change_reason") }
        return db.jdbi.inTransaction<AppReleaseDto, Exception> { h ->
            val before = h.createQuery("SELECT * FROM app.app_release WHERE id = :i FOR UPDATE").bind("i", id).map { rs, _ -> dto(rs) to rs.getObject("created_by") as Long? }
                .findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "release not found")
            val (cur, author) = before
            if (cur.version != ifMatch) throw ApiProblem(ProblemCode.ERR_PRECONDITION_FAILED, "the release changed since it was read", context = mapOf("version" to JsonPrimitive(cur.version)))
            val target = req.status?.takeIf { it != cur.status }
            val rolloutChange = req.rollout_pct?.takeIf { it != cur.rollout_pct }
            if (p.role != Role.SUPERADMIN && (target != null || (rolloutChange != null && cur.status != "draft"))) {
                throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "publishing, blocking, retiring and the rollout of a published release are the super admin's")
            }
            if (target != null && target !in (MOVES[cur.status] ?: emptySet())) {
                throw ApiProblem(ProblemCode.ERR_CONFLICT, "a ${cur.status} release cannot become $target")
            }
            if (cur.status == "retired" && (rolloutChange != null || patchedNotes.isNotEmpty())) throw ApiProblem(ProblemCode.ERR_CONFLICT, "a retired release is final")
            // Every move to published (a first publish or an unblock) is a second person's; a release with no recorded author has no maker.
            if (target == "published" && (author == null || author == p.userId)) {
                throw ApiProblem(ProblemCode.ERR_SEPARATION_OF_DUTIES, "a release is published by someone other than its author")
            }
            val sets = mutableListOf<String>()
            if (target != null) sets += "status = :st"
            if (target == "published" && cur.published_at == null) sets += "published_at = :now, published_by = :me"
            if (rolloutChange != null) sets += "rollout_pct = :pct"
            if ("notes_en" in patchedNotes) sets += "notes_en = :ne"
            if ("notes_bn" in patchedNotes) sets += "notes_bn = :nb"
            if (sets.isEmpty()) return@inTransaction cur // nothing changes: no version bump, no audit row
            h.createUpdate("UPDATE app.app_release SET ${sets.joinToString(", ")} WHERE id = :i").bind("i", id).bind("st", target).bind("pct", rolloutChange)
                .bind("ne", req.notes_en).bind("nb", req.notes_bn).bind("me", p.userId).bind("now", OffsetDateTime.ofInstant(clock.now(), ZoneOffset.UTC)).execute()
            val out = one(h, id)!!
            AuditWriter.write(h, p, "app_release", id.toString(), target?.let { "status_$it" } ?: "update", audit(cur), audit(out), req.change_reason, requestId)
            out
        }
    }

    /** Per flavour: the configured minimum, the newest published code, the blocked codes and the active phones below the minimum. */
    fun policy(p: AronPrincipal): ReleasePolicyListDto {
        if (p.role !in READERS) throw forbidden()
        val mins = value("cfg.release.min_version_code") as? JsonObject
        val blocked = value("cfg.release.blocked_version_codes")
        return db.jdbi.withHandle<ReleasePolicyListDto, Exception> { h ->
            ReleasePolicyListDto(FLAVOURS.sorted().map { f ->
                val min = (mins?.get(f) as? JsonPrimitive)?.intOrNull?.coerceAtLeast(1) ?: 1
                val latest = h.createQuery("SELECT max(version_code) FROM app.app_release WHERE flavour = :f AND status = 'published'").bind("f", f).mapTo(Int::class.javaObjectType).one()
                val below = h.createQuery(
                    "SELECT count(*) FROM app.device WHERE flavour = :f AND status IN ('enrolled','active') AND split_part(app_version, '+', 2) ~ '^[0-9]{1,10}$' AND split_part(app_version, '+', 2)::bigint < :m",
                ).bind("f", f).bind("m", min).mapTo(Int::class.java).one()
                ReleasePolicyDto(f, min, latest, blockedCodes(blocked, f).sorted().take(100), below)
            })
        }
    }

    private fun one(h: Handle, id: Long): AppReleaseDto? = h.createQuery("SELECT * FROM app.app_release WHERE id = :i").bind("i", id).map { rs, _ -> dto(rs) }.findOne().orElse(null)

    private fun audit(r: AppReleaseDto) = buildJsonObject {
        put("flavour", r.flavour); put("version_code", r.version_code); put("abi", r.abi); put("status", r.status); put("rollout_pct", r.rollout_pct); put("sha256", r.sha256)
    }

    companion object {
        private val READERS = setOf(Role.SUPPORT, Role.ADMIN, Role.SUPERADMIN)
        private val WRITERS = setOf(Role.ADMIN, Role.SUPERADMIN)
        private val FLAVOURS = setOf("sr", "amo", "tso")
        private val ABIS = setOf("universal", "arm64-v8a", "armeabi-v7a")
        private val VERSION_NAME = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val MOVES = mapOf("draft" to setOf("published", "retired"), "published" to setOf("blocked", "retired"), "blocked" to setOf("published", "retired"))

        private fun forbidden() = ApiProblem(ProblemCode.ERR_FORBIDDEN, "app releases are not available to this role")
        private fun bad(pointer: String, code: String = "invalid_value", msg: String = "invalid $pointer"): Nothing =
            throw ApiProblem(ProblemCode.ERR_VALIDATION, msg, errors = listOf(FieldError(pointer, code)))

        private fun encodeCursor(id: Long) = Base64.getUrlEncoder().withoutPadding().encodeToString("r1|$id".toByteArray())
        private fun decodeCursor(c: String): Long = runCatching { String(Base64.getUrlDecoder().decode(c)) }.getOrNull()
            ?.takeIf { it.startsWith("r1|") }?.removePrefix("r1|")?.toLongOrNull()?.takeIf { it > 0 } ?: bad("query.cursor")

        internal fun dto(rs: java.sql.ResultSet) = AppReleaseDto(
            rs.getLong("id"), rs.getString("flavour"), rs.getString("version_name"), rs.getInt("version_code"), rs.getString("abi"), hex(rs.getBytes("sha256")), rs.getInt("size_bytes"),
            rs.getString("download_url"), hex(rs.getBytes("signing_cert_sha256")), rs.getString("status"), rs.getInt("rollout_pct"), rs.getString("notes_en"), rs.getString("notes_bn"),
            rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(), rs.getObject("published_at", OffsetDateTime::class.java)?.toInstant()?.wire(), rs.getInt("version"),
        )

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        /** `cfg.release.blocked_version_codes` is `{sr: [..], amo: [..], tso: [..]}` (docs/24 s9.5); a bare list (older shape) applies to every flavour. */
        internal fun blockedCodes(v: kotlinx.serialization.json.JsonElement?, flavour: String): List<Int> {
            val list = when (v) { is JsonObject -> v[flavour] as? JsonArray; is JsonArray -> v; else -> null } ?: return emptyList()
            return list.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { c -> c >= 1 } }.distinct()
        }
    }
}

class ReleaseAdminDeps(val releases: ReleaseAdmin, val guard: AuthGuardDeps)

/** `GET/POST /v1/admin/releases`, `PATCH /v1/admin/releases/{release_id}`, `GET /v1/admin/releases/policy` (tag admin-releases). */
fun Route.releaseAdminRoutes(d: ReleaseAdminDeps) {
    authenticated(d.guard) {
        get("/admin/releases") {
            val q = call.request.queryParameters
            val limit = q["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..500 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad limit", errors = listOf(FieldError("query.limit", "out_of_range"))) } ?: 100
            call.respond(d.releases.list(call.principal, q["flavour"], limit, q["cursor"]))
        }
        get("/admin/releases/policy") { call.respond(d.releases.policy(call.principal)) }
        post("/admin/releases") {
            d.releases.requireWriter(call.principal)
            val out = d.releases.create(call.principal, call.receiveStrict(AppReleaseWriteIn.serializer()), call.requestId)
            call.response.header("ETag", "\"${out.version}\"")
            call.respond(HttpStatusCode.Created, out)
        }
        patch("/admin/releases/{release_id}") {
            d.releases.requireWriter(call.principal)
            val id = call.parameters["release_id"]?.toLongOrNull()?.takeIf { it >= 1 }
                ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad release_id", errors = listOf(FieldError("path.release_id", "invalid_value")))
            val im = call.request.headers["If-Match"]?.trim()?.let { Regex("^\"([0-9]{1,10})\"$").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "If-Match must be a quoted version", errors = listOf(FieldError("header.If-Match", "required")))
            val body = call.receiveStrict(JsonObject.serializer())
            val req = runCatching { PatchJson.decodeFromJsonElement(AppReleasePatchIn.serializer(), body) }.getOrElse {
                throw ApiProblem(ProblemCode.ERR_VALIDATION, "the body does not match AppReleasePatch", errors = listOf(FieldError("body", "invalid_body")))
            }
            val out = d.releases.patch(call.principal, id, im, req, body.keys.intersect(setOf("notes_en", "notes_bn")), call.requestId)
            call.response.header("ETag", "\"${out.version}\"")
            call.respond(out)
        }
    }
}

private val PatchJson = Json { ignoreUnknownKeys = false; explicitNulls = false }

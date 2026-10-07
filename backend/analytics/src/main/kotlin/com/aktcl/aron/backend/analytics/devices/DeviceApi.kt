package com.aktcl.aron.backend.analytics.devices

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.DeviceProof
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.RateLimiter
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.authenticated
import com.aktcl.aron.backend.platform.decodeStrict
import com.aktcl.aron.backend.platform.principal
import com.aktcl.aron.backend.platform.receiveStrict
import com.aktcl.aron.backend.platform.requestId
import com.aktcl.aron.backend.platform.toProblem
import com.aktcl.aron.contract.ProblemCode
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.datetime.toJavaLocalDate
import kotlinx.io.readByteArray
import java.util.zip.GZIPInputStream

class DeviceDeps(val service: DeviceService, val reach: ReachResolver, val guard: AuthGuardDeps, val clock: AronClock = AronClock.SYSTEM)

private val DEVICE_WRITERS = setOf(Role.ADMIN, Role.SUPERADMIN, Role.SUPPORT)
private val DEVICE_READERS = DEVICE_WRITERS + setOf(Role.TSO, Role.DMO, Role.WM, Role.TOP, Role.ANALYST)
private const val MAX_BODY = 256L * 1024

/**
 * N-031 routes. `POST /devices/enrol` is open (the token is the credential); `/devices/nonce`, `/devices/me/policy` and `/devices/me/status` need the phone's own
 * `X-Device-Proof` (docs/24 s8.3, no user token); the admin routes need a user token and a role.
 */
fun Route.deviceRoutes(d: DeviceDeps) {
    val enrolLimiter = RateLimiter(10, 600, d.clock)

    post("/devices/enrol") {
        val raw = call.rawBody()
        val req = decodeStrict(EnrolDeviceRequest.serializer(), raw.decodeToString())
        enrolLimiter.tryAcquire("enrol:" + req.device_uuid).let { if (!it.allowed) throw it.toProblem() }
        call.response.status(HttpStatusCode.Created)
        call.respond(HttpStatusCode.Created, d.service.enrol(req))
    }

    post("/devices/nonce") {
        val body = call.rawBody()
        val dev = call.authDevice(d, body)
        call.respond(d.service.createNonce(dev))
    }

    get("/devices/me/policy") {
        val dev = call.authDevice(d, ByteArray(0))
        val (policy, version) = d.service.policyFor(dev)
        val etag = "\"$version\""
        call.response.header(HttpHeaders.ETag, etag)
        if (call.request.header(HttpHeaders.IfNoneMatch)?.split(',')?.any { it.trim().removePrefix("W/") == etag } == true) call.respond(HttpStatusCode.NotModified) else call.respond(policy)
    }

    post("/devices/me/status") {
        val body = call.rawBody()
        val dev = call.authDevice(d, body)
        call.respond(d.service.report(dev, decodeStrict(DeviceStatusReportDto.serializer(), body.decodeToString())))
    }

    authenticated(d.guard) {
        fun ApplicationCall.reach() = d.reach.reach(principal.userId, principal.role, principal.scopeVersion, BusinessDate.of(d.clock.now().toEpochMilli()).toJavaLocalDate())
        fun ApplicationCall.read() { if (principal.role !in DEVICE_READERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "devices are not available to this role") }
        fun ApplicationCall.write() { if (principal.role !in DEVICE_WRITERS) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "changing devices is for ADMIN, SUPERADMIN and SUPPORT") }
        fun ApplicationCall.id(name: String) = parameters[name]?.toLongOrNull()?.takeIf { it >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $name", errors = listOf(FieldError("path.$name", "invalid_value")))
        fun ApplicationCall.limit() = request.queryParameters["limit"]?.let { it.toIntOrNull()?.takeIf { v -> v in 1..500 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad limit", errors = listOf(FieldError("query.limit", "out_of_range"))) } ?: 100
        fun ApplicationCall.cursor() = request.queryParameters["cursor"]?.let { it.toLongOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad cursor", errors = listOf(FieldError("query.cursor", "invalid_value"))) }
        fun ApplicationCall.enum(name: String, allowed: Set<String>) = request.queryParameters[name]?.also { if (it !in allowed) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad $name", errors = listOf(FieldError("query.$name", "invalid_value"))) }

        post("/admin/enrolment-tokens") { call.write(); call.respond(HttpStatusCode.Created, d.service.createToken(call.principal, call.receiveStrict(EnrolmentTokenCreateRequest.serializer()))) }
        get("/admin/enrolment-tokens") { call.write(); call.respond(d.service.listTokens(call.request.queryParameters["active_only"] != "false", call.limit(), call.cursor())) }
        post("/admin/enrolment-tokens/{token_id}/revoke") { call.write(); call.respond(d.service.revokeToken(call.principal, call.id("token_id"))) }

        get("/admin/devices") {
            call.read()
            val q = call.request.queryParameters
            val zone = q["zone_id"]?.let { it.toLongOrNull()?.takeIf { v -> v >= 1 } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad zone_id", errors = listOf(FieldError("query.zone_id", "invalid_value"))) }
            call.respond(d.service.listDevices(call.reach(), call.enum("status", setOf("enrolled", "active", "suspended", "revoked", "replaced")), call.enum("trust_level", setOf("high", "normal", "low", "blocked")),
                call.enum("flavour", setOf("sr", "amo", "tso")), zone, q["search"]?.also { if (it.length !in 2..80) throw ApiProblem(ProblemCode.ERR_VALIDATION, "search must be 2..80 characters", errors = listOf(FieldError("query.search", "out_of_range"))) }, call.limit(), call.cursor()))
        }
        get("/admin/devices/{device_id}") { call.read(); call.respond(d.service.getDevice(call.reach(), call.id("device_id"))) }
        get("/admin/devices/{device_id}/status-history") { call.read(); call.respond(d.service.statusHistory(call.reach(), call.id("device_id"), call.limit(), call.cursor())) }
        post("/admin/devices/{device_id}/state") { call.write(); call.respond(d.service.changeState(call.principal, call.reach(), call.id("device_id"), call.receiveStrict(DeviceStateChangeRequest.serializer()), call.requestId)) }
        get("/admin/devices/{device_id}/directives") { call.read(); call.respond(d.service.listDirectives(call.reach(), call.id("device_id"))) }
        post("/admin/devices/{device_id}/directives") { call.write(); call.respond(HttpStatusCode.Created, d.service.createDirective(call.principal, call.reach(), call.id("device_id"), call.receiveStrict(DirectiveCreateRequest.serializer()), call.requestId)) }
    }
}

/** The body as sent, size-capped, gunzipped when sent gzip (the proof hashes the bytes on the wire, so callers pass what they read before decoding). */
private suspend fun ApplicationCall.rawBody(): ByteArray {
    request.header(HttpHeaders.ContentLength)?.toLongOrNull()?.let { if (it > MAX_BODY) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "body above $MAX_BODY bytes") }
    val raw = receiveChannel().readRemaining(MAX_BODY + 1).readByteArray()
    if (raw.size > MAX_BODY) throw ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE, "body above $MAX_BODY bytes")
    return raw
}

/**
 * `X-Device-Proof` of the phone itself: ES256 over `aron-proof-v1`, `device`, uuid, `METHOD path`, sha256 hex of the body (empty for none), nonce bucket.
 * A missing or bad proof, or an unknown phone, is 401 `ERR_DEVICE_PROOF_INVALID` (the same answer, so the endpoint does not tell who is enrolled).
 */
private fun ApplicationCall.authDevice(d: DeviceDeps, body: ByteArray): DeviceService.DeviceRow {
    val bad = ApiProblem(ProblemCode.ERR_DEVICE_PROOF_INVALID, "device proof invalid")
    val uuid = request.header("X-Device-Id")?.lowercase() ?: throw bad
    val proof = request.header("X-Device-Proof") ?: throw bad
    val dev = d.service.deviceByUuid(uuid) ?: throw bad
    val bodyHash = if (body.isEmpty()) "" else DeviceProof.sha256Hex(body)
    val ok = DeviceProof.verifyBucketed(dev.key, proof, d.clock.now().epochSecond) { bucket ->
        listOf("aron-proof-v1", "device", uuid, "${request.httpMethod.value} ${request.path()}", bodyHash, bucket.toString()).joinToString("\n")
    }
    if (!ok) throw bad
    d.service.requireUsable(dev)
    return dev
}

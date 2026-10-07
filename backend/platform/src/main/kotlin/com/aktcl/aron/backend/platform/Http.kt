package com.aktcl.aron.backend.platform

import com.aktcl.aron.contract.ProblemCode
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.serialization.ContentConverter
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.charsets.Charset
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/** What the platform needs from the rest of the server to stamp every response (docs/24 s3.1 item 6). */
class PlatformContext(
    val clock: AronClock = AronClock.SYSTEM,
    val config: ServerConfig,
    /** Server generation UUID of this database lineage (docs/24 s4.8). */
    val generation: () -> String,
    val build: String = "dev",
    /** When set, a request whose `X-Azure-FDID` differs is 403: the API is reachable only through our Front Door (WAF). */
    val frontDoorId: String? = null,
    /** [generation] without I/O, for the health probes (AUD-REL-01). */
    val cachedGeneration: () -> String = generation,
)

/** JSON for responses: every member present (required-nullable members as `null`), snake_case DTO names. */
val ResponseJson: Json = Json { encodeDefaults = true; explicitNulls = true }

/** JSON for requests: strict (an unknown member is an error); an omitted optional member equals `null` (s3.1). */
val RequestJson: Json = Json { ignoreUnknownKeys = false; explicitNulls = false; isLenient = false; coerceInputValues = false }

val RequestIdKey = AttributeKey<String>("aron.request_id")
val ProblemCodeKey = AttributeKey<String>("aron.problem_code")
val PrincipalKey = AttributeKey<AronPrincipal>("aron.principal")
private val StartNanosKey = AttributeKey<Long>("aron.start_nanos")

private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
private val requestLog = LoggerFactory.getLogger("aron.request")
private val errorLog = LoggerFactory.getLogger("aron.error")

val ApplicationCall.requestId: String get() = attributes[RequestIdKey]

/**
 * Installs what every API response needs: request id, marker headers, RFC 9457 problems for every failure
 * (unknown routes included), gzip above 1 KiB and one structured log line per request (docs/24 s3, s13.1).
 * The log line is JSON on stdout; the Application Insights Java agent (OpenTelemetry-based, attached by the image)
 * ships it with the request telemetry it collects automatically.
 */
fun Application.installAronPlatform(ctx: PlatformContext) {
    install(createApplicationPlugin("AronCore") {
        onCall { call ->
            call.attributes.put(StartNanosKey, System.nanoTime())
            val sent = call.request.headers["X-Request-Id"]?.lowercase()
            val id = if (sent != null && UUID_V4.matches(sent)) sent else UUID.randomUUID().toString()
            call.attributes.put(RequestIdKey, id)
            val h = call.response.headers
            h.append("X-Aron-Api", "1")
            h.append("X-Request-Id", id)
            h.append("X-Server-Time", ctx.clock.now().wire())
            val path = call.request.path()
            // Liveness and readiness never wait on PostgreSQL for a header (AUD-REL-01): cached values only.
            val probe = path.startsWith("/v1/health")
            // Other calls may refresh a stale cache from PostgreSQL: that blocks, so never on a call thread (AUD-PERF-02).
            val (configVersion, generation) =
                if (probe) {
                    runCatching { ctx.config.cachedConfigVersion() }.getOrDefault(0) to runCatching { ctx.cachedGeneration() }.getOrDefault(NIL_GENERATION)
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching { ctx.config.configVersion() }.getOrDefault(0) to runCatching { ctx.generation() }.getOrDefault(NIL_GENERATION)
                    }
                }
            h.append("X-Config-Version", configVersion.toString())
            h.append("X-Server-Generation", generation)
            // Container Apps probes reach the replica directly, so health is exempt from the Front Door gate.
            if (ctx.frontDoorId != null && !path.startsWith("/v1/health") && call.request.headers["X-Azure-FDID"] != ctx.frontDoorId) {
                throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "requests must come through the Aron Front Door")
            }
        }
        on(ResponseSent) { call ->
            val ms = (System.nanoTime() - (call.attributes.getOrNull(StartNanosKey) ?: System.nanoTime())) / 1_000_000
            val p = call.attributes.getOrNull(PrincipalKey)
            val line = buildJsonObject {
                put("request_id", call.attributes.getOrNull(RequestIdKey))
                put("method", call.request.httpMethod.value)
                put("route", call.request.path())
                put("status", call.response.status()?.value ?: 0)
                put("duration_ms", ms)
                put("user_id", p?.userId)
                put("device_id", p?.deviceId)
                put("problem_code", call.attributes.getOrNull(ProblemCodeKey))
            }
            requestLog.info(line.toString())
        }
    })
    install(ContentNegotiation) {
        json(ResponseJson)
        // Answer JSON whatever the Accept header says (a proxy or captive portal may rewrite it); never a bare 406.
        register(ContentType.Any, AlwaysJson(KotlinxSerializationConverter(ResponseJson)))
    }
    // Responses only: request bodies are decoded by the handlers (receiveStrict, the sync batch), which cap the
    // decompressed size and, for the batch, verify X-Device-Proof over the gzip bytes as sent (docs/24 s3.1, s8.3).
    install(Compression) { mode = io.ktor.server.plugins.compression.CompressionConfig.Mode.CompressResponse; gzip { minimumSize(1024) } }
    install(StatusPages) {
        exception<ApiProblem> { call, e -> call.respondProblem(e, ctx.clock) }
        // Ktor's own client-error exceptions are client errors, never a retryable 500 (phones bisect on 500, s4.7).
        exception<NotFoundException> { call, _ -> call.respondProblem(ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such resource"), ctx.clock) }
        exception<UnsupportedMediaTypeException> { call, _ -> call.respondProblem(ApiProblem(ProblemCode.ERR_UNSUPPORTED_MEDIA_TYPE), ctx.clock) }
        exception<PayloadTooLargeException> { call, _ -> call.respondProblem(ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE), ctx.clock) }
        exception<BadRequestException> { call, e ->
            val code = if (e.rootCause() is kotlinx.serialization.SerializationException) ProblemCode.ERR_MALFORMED_JSON else ProblemCode.ERR_VALIDATION
            call.respondProblem(ApiProblem(code, "the request is invalid"), ctx.clock)
        }
        exception<Throwable> { call, e ->
            // A value PostgreSQL cannot store as text (U+0000, SQLSTATE 22021 / 22P05) that reached it by a path other
            // than receiveStrict (a query or path parameter) is the client's error, never a retryable 500.
            val state = generateSequence(e) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()?.sqlState
            if (state == "22021" || state == "22P05") {
                return@exception call.respondProblem(
                    ApiProblem(ProblemCode.ERR_VALIDATION, "a text value holds a character that cannot be stored", errors = listOf(FieldError("", "invalid_character"))),
                    ctx.clock,
                )
            }
            // The database or the pool is unavailable for a while (outage, failover, pool exhausted, lock or statement
            // timeout, serialization conflict): 503 with a jittered Retry-After, never a 500 the phone bisects on (AUD-REL-02).
            if (isTransientDbFailure(e)) {
                errorLog.warn("database unavailable request_id=${call.attributes.getOrNull(RequestIdKey)} sqlstate=$state cause=${e.javaClass.simpleName}")
                return@exception call.respondProblem(serviceUnavailable("the service is briefly unavailable; retry later"), ctx.clock)
            }
            // A statement timeout (57014) is retryable for the client (AUD-PERF-02 item 3), but it is as often a slow
            // query to fix as an overload, so it is logged as an error with its stack to stay visible.
            if (state == STATEMENT_TIMEOUT) {
                errorLog.error("statement timeout request_id=${call.attributes.getOrNull(RequestIdKey)}", e)
                return@exception call.respondProblem(serviceUnavailable("the request took too long; retry later"), ctx.clock)
            }
            errorLog.error("unhandled error request_id=${call.attributes.getOrNull(RequestIdKey)}", e)
            call.respondProblem(ApiProblem(ProblemCode.ERR_INTERNAL, "unexpected server error"), ctx.clock)
        }
        // Bare statuses produced by Ktor itself (no route, wrong method, ...) become problems. A problem the code already
        // sent (ProblemCodeKey set) is never replaced, so its specific code, detail and context survive.
        status(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed) { call, _ ->
            if (!call.attributes.contains(ProblemCodeKey)) call.respondProblem(ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such resource"), ctx.clock)
        }
        status(HttpStatusCode.UnsupportedMediaType) { call, _ ->
            if (!call.attributes.contains(ProblemCodeKey)) call.respondProblem(ApiProblem(ProblemCode.ERR_UNSUPPORTED_MEDIA_TYPE), ctx.clock)
        }
        status(HttpStatusCode.PayloadTooLarge) { call, _ ->
            if (!call.attributes.contains(ProblemCodeKey)) call.respondProblem(ApiProblem(ProblemCode.ERR_PAYLOAD_TOO_LARGE), ctx.clock)
        }
        status(HttpStatusCode.NotAcceptable) { call, _ ->
            if (!call.attributes.contains(ProblemCodeKey)) call.respondProblem(ApiProblem(ProblemCode.ERR_VALIDATION, "Accept must allow application/json"), ctx.clock)
        }
    }
}

/** SQLSTATEs that mean "try again shortly", not a defect: connection (08), shutdown (57P01..03), too many connections,
 * serialization failure, deadlock, lock not available, read-only after a failover (docs/18 s4.3). A statement timeout
 * (57014) is answered 503 too, but separately, so it is logged as an error (StatusPages above). */
private val TRANSIENT_SQLSTATES = setOf("57P01", "57P02", "57P03", "53300", "40001", "40P01", "55P03", "25006")

/** True when [e] (or a cause) is a pool timeout, a lost or refused connection, or a transient SQLSTATE (AUD-REL-02). */
fun isTransientDbFailure(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(16).any { c ->
    c is java.sql.SQLTransientException || c is java.sql.SQLRecoverableException || c is java.net.ConnectException ||
        (c is java.sql.SQLException && c.sqlState?.let { it.startsWith("08") || it in TRANSIENT_SQLSTATES } == true)
}

private const val STATEMENT_TIMEOUT = "57014"

const val NIL_GENERATION = "00000000-0000-4000-8000-000000000000"

val ProblemContentType: ContentType = ContentType("application", "problem+json")

suspend fun ApplicationCall.respondProblem(e: ApiProblem, clock: AronClock = AronClock.SYSTEM) {
    attributes.put(ProblemCodeKey, e.code.wire)
    e.headers.forEach { (k, v) -> response.headers.append(k, v, safeOnly = false) }
    val body = Problem(
        type = ProblemTexts.type(e.code),
        title = ProblemTexts.title(e.code),
        status = e.status,
        detail = e.detail?.take(2000),
        instance = request.path(),
        code = e.code.wire,
        request_id = attributes.getOrNull(RequestIdKey) ?: UUID.randomUUID().toString(),
        retryable = ProblemTexts.retryable(e.code),
        retry_after_s = e.retryAfterS,
        server_time = clock.now().wire(),
        message_key = ProblemTexts.messageKey(e.code),
        errors = e.errors.ifEmpty { null },
        context = e.context.ifEmpty { null },
    )
    val text = ProblemJson.encodeToString(Problem.serializer(), body)
    respond(TextContent(text, ProblemContentType.withParameter("charset", "utf-8"), HttpStatusCode.fromValue(e.status)))
}

/** Problems omit absent optional members (the contract's Problem has no nullable members). */
private val ProblemJson: Json = Json { encodeDefaults = false; explicitNulls = false }


private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()

/** Serialises responses as application/json even when the client's Accept names another type. */
private class AlwaysJson(private val inner: ContentConverter) : ContentConverter {
    override suspend fun serialize(contentType: ContentType, charset: Charset, typeInfo: TypeInfo, value: Any?) =
        inner.serialize(ContentType.Application.Json, charset, typeInfo, value)

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? =
        inner.deserialize(charset, typeInfo, content)
}

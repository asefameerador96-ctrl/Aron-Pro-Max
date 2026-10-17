package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RoutingNode
import java.io.File
import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

/**
 * N-014: the world the contract and scope-leak harnesses drive through the PRODUCTION wiring: a migrated database with
 * the db lane's seed (db/seed), a throwaway SR and phone for the admin writes (suspend, reset, deactivate) so the callers
 * keep their sessions, the per-minute limits lifted and TOTP off for the web roles. Each tag in [mirrors] loads the seed
 * again under other codes (for `LKX`: division D-LKX, territory T-LKX, zone Z-LKX, routes and outlets LKX-*, users
 * lkx*): a complete division no scoped seed user may ever see, with its own SR (`lkxsr1001`, primary on LKX-SR-*).
 */
class ApiWorld(
    private val repoRoot: File,
    mirrors: List<String> = emptyList(),
    val clock: AronClock = AronClock { Instant.parse("2027-01-03T04:00:00Z") },
    extraRecordHandlers: List<com.aktcl.aron.backend.platform.RecordHandler> = emptyList(),
) : AutoCloseable {
    data class Caller(val user: String, val client: String, val device: String?)

    val fresh: FreshDb = FreshDb.create()
    val password = "throwaway-" + System.nanoTime()
    /** Resource ids by path segment (`outlets`, `routes`, ...) in the home world and, with the mirror, in the other. */
    val ids = HashMap<String, Long>()
    val otherIds = HashMap<String, Long>()
    val wiring: Wiring
    /** The in-memory blob store behind every blob port (SAS, photo writer, photo read): blob calls answer 2xx here. */
    val blobs = FakeBlobs()
    /** The key pair of the seeded SR phone ([SR_PHONE]): its public JWK replaces the seed placeholder, so the harness signs device proofs. */
    val phoneKey: java.security.KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    init {
        // The blob issuer reads its account from the process environment, not from Settings: with it set the harness
        // would call real Azure and its 503 baseline would go stale (checker finding, N-014).
        check(System.getenv("ARON_BLOB_ACCOUNT").isNullOrEmpty()) { "unset ARON_BLOB_ACCOUNT for the qa harness" }
        val seed = repoRoot.resolve("db/seed").listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }
        fresh.dataSource.connection.use { c ->
            seed.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
            for (tag in mirrors) listOf("01_geography_people.sql", "03_outlets_and_plans.sql").forEach { n -> c.createStatement().use { it.execute(mirrored(repoRoot.resolve("db/seed/$n").readText(), tag)) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            for ((k, v) in listOf("cfg.api.rl.device_per_min" to "100000", "cfg.api.rl.user_per_min" to "100000", "cfg.auth.mfa_required_roles" to "[]")) {
                h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT '$k', 'global', 0, '$v'::jsonb, now() - interval '1 day', max(config_version), 'qa harness' FROM app.cfg_version")
            }
            h.execute("INSERT INTO app.app_user (username, full_name, role, designation, employee_code, locale, home_zone_id, pilot, must_change_password) SELECT 'qa9001', 'QA Throwaway', role, designation, 'QA9001', locale, home_zone_id, false, false FROM app.app_user WHERE username = 'sr1001'")
            h.execute(
                "INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version) " +
                    "SELECT '$THROWAWAY_PHONE', flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, 'qa-throwaway-0009', attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version FROM app.device WHERE device_uuid = '$SR_PHONE'",
            )
            h.createUpdate("UPDATE app.device SET public_key_jwk = CAST(:jwk AS jsonb) WHERE device_uuid = '$SR_PHONE'").bind("jwk", publicJwk(phoneKey)).execute()
            fun load(into: HashMap<String, Long>, zone: String, outletPrefix: String, user: String, device: String?) {
                fun one(sql: String) = h.createQuery(sql).mapTo(Long::class.java).findOne().orElse(null)
                mapOf(
                    "outlets" to "SELECT min(id) FROM app.outlet WHERE code LIKE '$outletPrefix-%'",
                    "routes" to "SELECT min(r.id) FROM app.route r JOIN app.zone z ON z.id = r.zone_id WHERE z.code = '$zone'",
                    "users" to "SELECT id FROM app.app_user WHERE username = '$user'",
                    "zones" to "SELECT id FROM app.zone WHERE code = '$zone'",
                    "clusters" to "SELECT min(c.id) FROM app.cluster c JOIN app.zone z ON z.id = c.zone_id WHERE z.code = '$zone'",
                    "territories" to "SELECT territory_id FROM app.zone WHERE code = '$zone'",
                    "divisions" to "SELECT t.division_id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id WHERE z.code = '$zone'",
                    "skus" to "SELECT min(id) FROM app.sku",
                    "devices" to (device?.let { "SELECT id FROM app.device WHERE device_uuid = '$it'" } ?: "SELECT NULL::bigint WHERE false"),
                ).forEach { (k, sql) -> one(sql)?.let { into[k] = it } }
                into["zone"] = into["zones"] ?: 0
            }
            load(ids, "Z-MIR", "MIR", "qa9001", THROWAWAY_PHONE)
            mirrors.firstOrNull()?.let { tag -> load(otherIds, "Z-$tag", tag, "${tag.lowercase()}sr1001", null) }
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock, extraRecordHandlers, blobPorts = blobs.ports())
    }

    override fun close() { wiring.database?.close(); fresh.close() }

    suspend fun login(client: HttpClient, c: Caller): String? {
        val r = client.request("/v1/auth/login") {
            method = HttpMethod.Post; contentType(ContentType.Application.Json); header("X-App-Version", APP_VERSION)
            setBody("""{"username":"${c.user}","password":"$password","client":"${c.client}"${c.device?.let { ",\"device_uuid\":\"$it\"" } ?: ""}}""")
        }
        val text = r.bodyAsText()
        return ContractModel.json.readTree(text).path("access_token").takeIf { it.isTextual }?.asText().also {
            if (it == null) println("ApiWorld: no token for ${c.user}: ${r.status} ${text.take(200)}")
        }
    }

    /**
     * One call of [op] as [c]: path and query parameters that name a resource (`id` after its collection segment,
     * `*_id`, `node_id`) from [pathIds], other parameters and headers from the contract, a generated body whose
     * `device_uuid` is the caller's phone, gzipped when the operation requires `Content-Encoding`. [onInvalidBody] gets
     * the validation messages of a generated body the contract itself rejects; [bodyEdit] may change the body before
     * it is sent. [extraQuery] is appended as is.
     */
    suspend fun call(
        client: HttpClient, contract: ContractModel, op: ContractModel.Operation, c: Caller, token: String?,
        pathIds: Map<String, Long> = ids, extraQuery: String = "", onInvalidBody: (List<String>) -> Unit = {},
        includeOptionalIds: Boolean = false, bodyEdit: ((com.fasterxml.jackson.databind.JsonNode) -> Unit)? = null,
        query: Map<String, String> = emptyMap(),
        /** Path parameter values by name (uuids and ids an earlier answer created), before [pathIds] and samples. */
        pathValues: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        /** `X-Device-Proof` from the final method, path, sent bytes and JSON text (null: none); see [deviceProofOf]. */
        proof: ((method: String, path: String, sent: ByteArray, json: String?) -> String)? = null,
        /** A body sent as is (multipart) instead of the generated JSON sample. */
        rawBody: io.ktor.http.content.OutgoingContent.ByteArrayContent? = null,
    ): HttpResponse {
        val path = pathFor(contract, op, pathIds, pathValues)
        val query = (
            op.parameters.filter { it.where == "query" && it.required }.map { p -> p.name + "=" + URLEncoder.encode(query[p.name] ?: paramValue(p.name, pathIds) ?: contract.sampleText(p.schema, p.name), "UTF-8") } +
                listOfNotNull(extraQuery.ifEmpty { null })
            ).joinToString("&")
        val body = op.requestSchema?.let { s ->
            val sample = contract.sample(s, optional = if (includeOptionalIds) { n -> ID_FIELD.matches(n) } else null)
            contract.validate(s, sample).takeIf { it.isNotEmpty() }?.let(onInvalidBody)
            // The device_uuid of a body is the caller's phone (the server checks it against the token).
            if (c.device != null) (sample as? com.fasterxml.jackson.databind.node.ObjectNode)?.takeIf { it.has("device_uuid") }?.put("device_uuid", c.device)
            bodyEdit?.invoke(sample)
            ContractModel.json.writeValueAsString(sample)
        }
        val gzip = op.parameters.any { it.where == "header" && it.name == "Content-Encoding" && it.required }
        val sent: ByteArray = rawBody?.bytes() ?: body?.let { if (gzip) gz(it) else it.toByteArray() } ?: ByteArray(0)
        return client.request(path + if (query.isEmpty()) "" else "?$query") {
            method = HttpMethod.parse(op.method)
            header("X-App-Version", APP_VERSION)
            headers.forEach { (k, v) -> header(k, v) }
            proof?.let { header("X-Device-Proof", it(op.method, path, sent, body)) }
            if (token != null) header("Authorization", "Bearer $token")
            c.device?.let { header("X-Device-Id", it) }
            for (p in op.parameters.filter { it.where == "header" && it.required }) {
                when (p.name) {
                    "X-Device-Id" -> if (c.device == null) header(p.name, SR_PHONE)
                    "If-Match" -> header(p.name, "\"1\"")
                    "Content-Encoding" -> header(p.name, "gzip")
                    else -> header(p.name, contract.sampleText(p.schema, p.name))
                }
            }
            if (rawBody != null) setBody(rawBody)
            else if (body != null) {
                if (gzip) setBody(io.ktor.http.content.ByteArrayContent(gz(body), ContentType.Application.Json))
                else { contentType(ContentType.Application.Json); setBody(body) }
            }
        }
    }

    /** The concrete request path of [op] with its parameters filled from [pathIds] (or samples). */
    fun pathFor(contract: ContractModel, op: ContractModel.Operation, pathIds: Map<String, Long>, values: Map<String, String> = emptyMap()): String =
        op.path.replace(Regex("\\{([^}]*)}")) { m -> values[m.groupValues[1]] ?: pathValue(contract, op, m.groupValues[1], pathIds) }

    /** `X-Device-Proof` of the seeded SR phone: ES256 over `aron-proof-v1`, `device`, uuid, `METHOD path`, sha256 hex of the body, nonce bucket. */
    fun deviceProofOf(method: String, path: String, body: ByteArray): String =
        sign(listOf("aron-proof-v1", "device", SR_PHONE, "$method $path", com.aktcl.aron.backend.platform.DeviceProof.sha256Hex(body), bucket().toString()).joinToString("\n"))

    /** The `batch` proof of [device] (docs/24 s8.3): sha256 of the gzip body, `batch_uuid`, X-Batch-Attempt 1. */
    fun batchProof(device: String, gz: ByteArray, batchUuid: String): String =
        sign(listOf("aron-proof-v1", "batch", device, com.aktcl.aron.backend.platform.DeviceProof.sha256Hex(gz), batchUuid, "1").joinToString("\n"))

    /** [batchProof] of a gzip batch body, its `batch_uuid` read from the body. */
    fun batchProof(device: String, gz: ByteArray): String =
        batchProof(device, gz, ContractModel.json.readTree(java.util.zip.GZIPInputStream(gz.inputStream()).readBytes()).path("batch_uuid").asText())

    /** The nonce bucket of the harness clock (`floor(unix_s / 300)`). */
    fun bucket(): Long = com.aktcl.aron.backend.platform.DeviceProof.bucket(clock.now().epochSecond)

    /** base64url ES256 (P1363, 64 bytes) by [phoneKey]. */
    fun sign(message: String): String {
        val s = java.security.Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(phoneKey.private); update(message.toByteArray()) }.sign()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s)
    }

    /** A query or path value that names a resource of [pathIds] (or the clock's business date), else null. */
    private fun paramValue(name: String, pathIds: Map<String, Long>): String? = when (name) {
        "business_date", "date" -> BUSINESS_DATE
        "node_type" -> "zone"
        "radius_m" -> "100" // one of cfg.tso.periphery_radius_options_m (50, 100, 300), as the contract says
        "node_id" -> pathIds["zones"]?.toString()
        else -> PARAM_RESOURCE[name]?.let { pathIds[it]?.toString() }
    }

    private fun pathValue(contract: ContractModel, op: ContractModel.Operation, name: String, pathIds: Map<String, Long>): String {
        paramValue(name, pathIds)?.let { return it }
        if (name == "id") {
            val segs = op.path.split('/')
            pathIds[segs.getOrNull(segs.indexOf("{$name}") - 1)]?.let { return it.toString() }
        }
        val p = op.parameters.firstOrNull { it.where == "path" && it.name == name }
        return if (p == null) "1" else contract.sampleText(p.schema, name)
    }

    companion object {
        const val BUSINESS_DATE = "2027-01-03"
        /** Parameter names that carry a resource id, and the [ids] key of that resource. */
        val PARAM_RESOURCE = mapOf(
            "outlet_id" to "outlets", "route_id" to "routes", "zone_id" to "zones", "cluster_id" to "clusters", "territory_id" to "territories",
            "division_id" to "divisions", "user_id" to "users", "sr_user_id" to "users", "device_id" to "devices",
        )
        /** Body fields the scope probes fill even when optional (single ids and id lists). */
        val ID_FIELD = Regex("^[a-z_]*_ids?$")
        const val SR_PHONE = "00000000-0000-4000-8000-000000000001"
        const val THROWAWAY_PHONE = "00000000-0000-4000-8000-000000000009"
        const val APP_VERSION = "1.0.9+9"
        /** The tag of the scope-leak harness's other division; every code, name and username of it carries [MARK]. */
        const val OTHER = "LKX"
        const val MARK = "lkx"

        /**
         * The seed SQL rewritten for a mirrored division tagged [tag] (three upper-case letters): every code, name,
         * username and phone number moved, so the copy shares nothing with the original but the wing and the products.
         */
        fun mirrored(sql: String, tag: String): String {
            require(tag.matches(Regex("[A-Z]{3}")) && tag != "MIR" && tag != "DHK")
            val low = tag.lowercase(); val cap = tag.lowercase().replaceFirstChar { it.uppercase() }
            return sql
                .replace("'D-DHK'", "'D-$tag'").replace("Dhaka Division", "$cap Division").replace("'T-DHK-N'", "'T-$tag'").replace("Dhaka North", "$cap North")
                .replace("'H-MIR'", "'H-$tag'").replace("'Z-MIR'", "'Z-$tag'").replace("MIR-", "$tag-").replace("Mirpur", "${cap}pur").replace("Pallabi", "${cap}pallabi")
                .replace(Regex("'([a-z]+)1001'")) { "'$low${it.groupValues[1]}1001'" }.replace(Regex("'T-([A-Z]+)-1001'")) { "'T-$tag-${it.groupValues[1]}'" }
                .replace("(Dhaka)", "($cap)").replace("'017'", "'018'").replace("'Test ", "'$cap Test ")
        }

        /** Every route of a routing tree as (method, path template). */
        fun routes(root: RoutingNode): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            fun walk(n: RoutingNode, path: String) {
                var p = path
                when (val s = n.selector) {
                    is HttpMethodRouteSelector -> out += s.method.value to p.ifEmpty { "/" }
                    is PathSegmentConstantRouteSelector -> p += "/" + s.value
                    is PathSegmentParameterRouteSelector -> p += "/{" + s.name + "}"
                    is PathSegmentOptionalParameterRouteSelector -> p += "/{" + s.name + "}"
                    is PathSegmentWildcardRouteSelector -> p += "/*"
                    is PathSegmentTailcardRouteSelector -> p += "/{...}"
                    else -> {} // root, authenticated {}: RouteInventoryTest proves the walker sees every route
                }
                n.children.forEach { walk(it, p) }
            }
            walk(root, "")
            return out.distinct()
        }
    }
}

/**
 * In-memory blob storage for the harness: every SAS and read URL points at `https://blob.test/<path>`, written photo
 * bytes are kept by path. It lets the blob operations answer 2xx so their success bodies are validated too.
 */
class FakeBlobs {
    val stored = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun ports() = BlobPorts(
        object : com.aktcl.aron.backend.masterdata.BlobSasIssuer {
            override fun writeSas(blobPath: String, maxBytes: Long, expiresAt: Instant) = "https://blob.test/$blobPath?sp=cw&se=${expiresAt.epochSecond}"
            override fun readUrl(blobPath: String) = "https://blob.test/$blobPath"
        },
        com.aktcl.aron.backend.media.PhotoBlobWriter { path, bytes -> stored[path] = bytes },
        com.aktcl.aron.backend.media.PhotoReadIssuer { path, until -> "https://blob.test/$path?sp=r&se=${until.epochSecond}" },
    )
}

/** The public JWK (`kty` EC, `crv` P-256, base64url `x` and `y`) of an EC key pair. */
fun publicJwk(k: java.security.KeyPair): String {
    val w = (k.public as java.security.interfaces.ECPublicKey).w
    fun b64(v: java.math.BigInteger): String {
        val raw = v.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
    return """{"kty":"EC","crv":"P-256","x":"${b64(w.affineX)}","y":"${b64(w.affineY)}"}"""
}

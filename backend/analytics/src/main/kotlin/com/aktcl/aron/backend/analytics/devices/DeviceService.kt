package com.aktcl.aron.backend.analytics.devices

import com.aktcl.aron.backend.platform.ApiProblem
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AronPrincipal
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.backend.platform.JwtKeys
import com.aktcl.aron.backend.platform.Reach
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.wire
import com.aktcl.aron.contract.ProblemCode
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.util.Base64URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jdbi.v3.core.Handle
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** Where the enrolment gets its facts (set by the environment, never by the phone). */
class EnrolmentSettings(val apiBaseUrl: String, val env: String, val trust: AttestationTrust)

internal val JSON = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }

/**
 * Enrolment and device policy (N-031, docs/24 s8.7, s10): single-use expiring enrolment tokens, the device registry, key-attestation checks at enrolment, the
 * policy a phone applies offline, status reports with the compliance picture, directives, and the admin state changes. A token is the only credential of
 * `POST /devices/enrol`: it is stored as a SHA-256 and shown once.
 */
class DeviceService(
    private val db: Database, private val config: ServerConfig, private val keys: JwtKeys, private val settings: EnrolmentSettings,
    private val clock: AronClock = AronClock.SYSTEM, private val random: SecureRandom = SecureRandom(),
) {
    private val policy = DevicePolicyRenderer(config) { clock.now() }
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun b64u(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun ts(i: Instant): OffsetDateTime = OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC)
    private fun bool(key: String, d: Boolean) = runCatching { config.bool(key) }.getOrDefault(d)

    // ---------------- enrolment tokens ----------------

    fun createToken(p: AronPrincipal, req: EnrolmentTokenCreateRequest): EnrolmentTokenCreated {
        fun bad(f: String, w: String = "invalid_value"): Nothing = throw ApiProblem(ProblemCode.ERR_VALIDATION, "invalid $f", errors = listOf(FieldError("/$f", w)))
        if (req.flavour !in setOf("sr", "amo", "tso")) bad("flavour"); if (req.lockdown_level !in setOf("dev", "prod")) bad("lockdown_level")
        if (req.max_uses !in 1..500) bad("max_uses", "out_of_range"); if (req.expires_in_h !in 1..168) bad("expires_in_h", "out_of_range")
        if ((req.note?.length ?: 0) > 300) bad("note", "out_of_range")
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val secret = b64u(raw)
        val now = clock.now()
        return db.jdbi.inTransaction<EnrolmentTokenCreated, Exception> { h ->
            val release = (if (req.release_id != null) h.createQuery("SELECT * FROM app.app_release WHERE id = :i AND flavour = :f") else h.createQuery("SELECT * FROM app.app_release WHERE flavour = :f AND status = 'published' ORDER BY version_code DESC, id DESC LIMIT 1"))
                .also { q -> q.bind("f", req.flavour); if (req.release_id != null) q.bind("i", req.release_id) }
                .map { rs, _ -> Triple(rs.getLong("id"), rs.getString("download_url"), rs.getBytes("signing_cert_sha256")) }.findOne().orElse(null)
                ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no published release of this flavour to provision")
            val zoneCode = req.zone_id?.let { z -> h.createQuery("SELECT code FROM app.zone WHERE id = :z").bind("z", z).mapTo(String::class.java).findOne().orElse(null) ?: bad("zone_id") }
            val dto = h.createQuery(
                """
                INSERT INTO app.enrolment_token (token_sha256, token_prefix, flavour, lockdown_level, max_uses, zone_id, release_id, expires_at, created_by, note, created_at)
                VALUES (:h, :pre, :f, :l, :m, :z, :r, :e, :u, :n, :now) RETURNING id, created_at
                """,
            ).bind("h", sha256(secret.toByteArray())).bind("pre", secret.take(6)).bind("f", req.flavour).bind("l", req.lockdown_level).bind("m", req.max_uses).bind("z", req.zone_id)
                .bind("r", release.first).bind("e", ts(now.plus(Duration.ofHours(req.expires_in_h.toLong())))).bind("u", p.userId).bind("n", req.note).bind("now", ts(now))
                .map { rs, _ -> EnrolmentTokenDto(rs.getLong(1), secret.take(6), req.flavour, req.lockdown_level, req.max_uses, 0, req.zone_id, now.plus(Duration.ofHours(req.expires_in_h.toLong())).wire(), p.userId, rs.getObject(2, OffsetDateTime::class.java).toInstant().wire(), null, req.note) }.one()
            audit(h, p, "enrolment_token", dto.token_id.toString(), "create", null, buildJsonObject { put("flavour", req.flavour); put("lockdown_level", req.lockdown_level); put("max_uses", req.max_uses); put("prefix", dto.token_prefix) }, null)
            val qr = ProvisioningQrPayload(
                component = "com.aktcl.aron.${req.flavour}/com.aktcl.aron.dpc.AronDeviceAdminReceiver", downloadLocation = release.second, signatureChecksum = b64u(release.third),
                wifiSsid = req.wifi?.ssid, wifiSecurity = req.wifi?.security_type, wifiPassword = req.wifi?.password,
                extras = AdminExtras(secret, settings.apiBaseUrl, settings.env, req.flavour, req.lockdown_level, zoneCode),
            )
            EnrolmentTokenCreated(dto, secret, qr, JSON.encodeToString(ProvisioningQrPayload.serializer(), qr))
        }
    }

    fun listTokens(activeOnly: Boolean, limit: Int, after: Long?): EnrolmentTokenPage = db.readJdbi.withHandle<EnrolmentTokenPage, Exception> { h ->
        val where = buildList { if (activeOnly) add("revoked_at IS NULL AND expires_at > now() AND used_count < max_uses"); if (after != null) add("id < :after") }.joinToString(" AND ").ifEmpty { "true" }
        val q = h.createQuery("SELECT * FROM app.enrolment_token WHERE $where ORDER BY id DESC LIMIT :lim").bind("lim", limit + 1)
        if (after != null) q.bind("after", after)
        val rows = q.map { rs, _ -> tokenDto(rs) }.list()
        EnrolmentTokenPage(rows.take(limit), if (rows.size > limit) rows[limit - 1].token_id.toString() else null)
    }

    private fun tokenDto(rs: java.sql.ResultSet) = EnrolmentTokenDto(
        rs.getLong("id"), rs.getString("token_prefix"), rs.getString("flavour"), rs.getString("lockdown_level"), rs.getInt("max_uses"), rs.getInt("used_count"), rs.getObject("zone_id") as Long?,
        rs.getObject("expires_at", OffsetDateTime::class.java).toInstant().wire(), rs.getLong("created_by"), rs.getObject("created_at", OffsetDateTime::class.java).toInstant().wire(),
        rs.getObject("revoked_at", OffsetDateTime::class.java)?.toInstant()?.wire(), rs.getString("note"),
    )

    fun revokeToken(p: AronPrincipal, id: Long): EnrolmentTokenDto = db.jdbi.inTransaction<EnrolmentTokenDto, Exception> { h ->
        val ex = h.createQuery("SELECT * FROM app.enrolment_token WHERE id = :i FOR UPDATE").bind("i", id).map { rs, _ -> tokenDto(rs) }.findOne().orElse(null) ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such token")
        if (ex.revoked_at != null) return@inTransaction ex     // idempotent
        h.createUpdate("UPDATE app.enrolment_token SET revoked_at = now(), revoked_by = :u WHERE id = :i").bind("u", p.userId).bind("i", id).execute()
        audit(h, p, "enrolment_token", id.toString(), "revoke", null, null, null)
        h.createQuery("SELECT * FROM app.enrolment_token WHERE id = :i").bind("i", id).map { rs, _ -> tokenDto(rs) }.one()
    }

    // ---------------- enrolment ----------------

    fun enrol(req: EnrolDeviceRequest): EnrolDeviceResponse {
        fun fail(why: String): Nothing = throw ApiProblem(ProblemCode.ERR_ENROLMENT_ATTESTATION_FAILED, why)
        val now = clock.now()
        val uuid = runCatching { UUID.fromString(req.device_uuid) }.getOrNull()?.takeIf { it.toString() == req.device_uuid } ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad device_uuid", errors = listOf(FieldError("/device_uuid", "invalid_value")))
        if (!Regex("^[A-Za-z0-9_-]{43,64}$").matches(req.enrolment_token)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad enrolment_token", errors = listOf(FieldError("/enrolment_token", "invalid_value")))
        if (req.key_attestation_chain.size !in 1..KeyAttestation.MAX_CHAIN || req.key_attestation_chain.any { it.length > 8000 }) throw ApiProblem(ProblemCode.ERR_VALIDATION, "key_attestation_chain must have 1..6 certificates of at most 8000 characters", errors = listOf(FieldError("/key_attestation_chain", "out_of_range")))
        checkAppVersion(req.app_version, "/app_version")
        val certHex = req.app_signing_cert_sha256.lowercase().also { if (!Regex("^[0-9a-f]{64}$").matches(it)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad certificate digest", errors = listOf(FieldError("/app_signing_cert_sha256", "invalid_value"))) }
        val pub = runCatching { ECKey.Builder(Curve.P_256, Base64URL(req.public_key.x), Base64URL(req.public_key.y)).build() }.getOrNull()
            ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "public_key is not a point of P-256", errors = listOf(FieldError("/public_key", "invalid_value")))
        if (req.public_key.kty != "EC" || req.public_key.crv != "P-256") throw ApiProblem(ProblemCode.ERR_VALIDATION, "public_key must be EC P-256", errors = listOf(FieldError("/public_key", "invalid_value")))
        // RFC 7638 thumbprint over the canonical (decoded, re-encoded) coordinates, so padded or differently encoded copies of one key collide.
        val canonX = b64u(pub.x.decode()); val canonY = b64u(pub.y.decode())
        val thumb = b64u(sha256("""{"crv":"P-256","kty":"EC","x":"$canonX","y":"$canonY"}""".toByteArray()))
        return db.jdbi.inTransaction<EnrolDeviceResponse, Exception> { h ->
            val tok = h.createQuery("SELECT * FROM app.enrolment_token WHERE token_sha256 = :h FOR UPDATE").bind("h", sha256(req.enrolment_token.toByteArray()))
                .map { rs, _ -> TokenRow(rs.getLong("id"), rs.getString("flavour"), rs.getString("lockdown_level"), rs.getInt("max_uses"), rs.getInt("used_count"), rs.getObject("zone_id") as Long?,
                    rs.getObject("expires_at", OffsetDateTime::class.java).toInstant(), rs.getObject("revoked_at") != null) }.findOne().orElse(null)
                ?: throw ApiProblem(ProblemCode.ERR_ENROLMENT_TOKEN_INVALID, "unknown enrolment token")
            if (tok.revoked) throw ApiProblem(ProblemCode.ERR_ENROLMENT_TOKEN_INVALID, "enrolment token revoked")
            // The same phone repeating a successful enrolment (a lost response) gets the same device back and uses no further slot of the token.
            val existing = h.createQuery("SELECT id, public_key_thumbprint, enrolment_token_id, lockdown_level, trust_level, enrolled_at FROM app.device WHERE device_uuid = :u").bind("u", uuid)
                .map { rs, _ -> listOf(rs.getLong(1), rs.getString(2), rs.getObject(3), rs.getString(4), rs.getString(5), rs.getObject(6, OffsetDateTime::class.java)) }.findOne().orElse(null)
            if (existing != null) {
                val st = h.createQuery("SELECT status FROM app.device WHERE id = :i").bind("i", existing[0] as Long).mapTo(String::class.java).one()
                if (st == "revoked" || st == "replaced") throw ApiProblem(ProblemCode.ERR_DEVICE_REVOKED, "device revoked")
                if (existing[1] != thumb || existing[2] != tok.id) throw ApiProblem(ProblemCode.ERR_CONFLICT, "device already enrolled")
                return@inTransaction EnrolDeviceResponse(existing[0] as Long, req.device_uuid, (existing[5] as OffsetDateTime).toInstant().wire(), existing[3] as String, existing[4] as String, policy.render(existing[3] as String), now.wire())
            }
            if (h.createQuery("SELECT EXISTS (SELECT 1 FROM app.device WHERE public_key_thumbprint = :t)").bind("t", thumb).mapTo(Boolean::class.java).one()) throw ApiProblem(ProblemCode.ERR_CONFLICT, "this key is already enrolled")
            if (!now.isBefore(tok.expiresAt)) throw ApiProblem(ProblemCode.ERR_ENROLMENT_TOKEN_EXPIRED, "enrolment token expired")
            if (tok.used >= tok.maxUses) throw ApiProblem(ProblemCode.ERR_ENROLMENT_TOKEN_EXHAUSTED, "enrolment token used up")

            if (req.app_package != "com.aktcl.aron.${tok.flavour}") fail("package does not match the token's flavour")
            val prod = tok.lockdown == "prod"
            if (prod && !req.device_owner) fail("the app is not device owner")
            // Attestation: challenge = SHA-256(token), package and signer digest as claimed, key = the enrolment key, boot state verified in production, a trusted root in production.
            val facts = try {
                KeyAttestation.verify(req.key_attestation_chain, pub.toECPublicKey() as ECPublicKey, settings.trust, requireTrustedRoot = prod, nowMs = now.toEpochMilli())
            } catch (e: AttestationFailed) { fail(e.message ?: "attestation failed") }
            if (prod && settings.trust.trustedRootSha256.isEmpty()) fail("no attestation roots are configured for production")
            if (!facts.challenge.contentEquals(sha256(req.enrolment_token.toByteArray()))) fail("the attestation challenge is not this enrolment token")
            if (facts.packageName != req.app_package) fail("the attested package is not ${req.app_package}")
            if (certHex !in facts.signerDigestsSha256) fail("the attested signing certificate is not the one claimed")
            val knownCerts = h.createQuery("SELECT encode(signing_cert_sha256, 'hex') FROM app.app_release WHERE flavour = :f").bind("f", tok.flavour).mapTo(String::class.java).list()
            if (knownCerts.isNotEmpty() && certHex !in knownCerts) fail("the signing certificate belongs to no release of this app")
            if (prod && (facts.deviceLocked != true || facts.verifiedBootState != 0)) fail("verified boot is not intact")
            if (prod && bool("cfg.device.key_attestation_required", true) && !facts.hardwareBacked) fail("the key is not hardware backed")

            val trust = "normal"   // high needs a Play Integrity pass, which arrives with the first status report
            val info = JSON.encodeToString(DeviceInfoDto.serializer(), req.device_info)
            val deviceId = h.createQuery(
                """
                INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, integrity_verdict, hardware_backed_key, public_key_jwk, public_key_thumbprint,
                                        attestation_summary, app_signing_cert_sha256, enrolment_token_id, device_info, app_version, zone_id, last_contact_at)
                VALUES (:u, :f, :pkg, 'enrolled', :own, :l, :t, 'unevaluated', :hw, CAST(:jwk AS jsonb), :thumb, CAST(:att AS jsonb), decode(:cert, 'hex'), :tid, CAST(:info AS jsonb), :ver, :zone, now()) RETURNING id
                """,
            ).bind("u", uuid).bind("f", tok.flavour).bind("pkg", req.app_package).bind("own", req.device_owner).bind("l", tok.lockdown).bind("t", trust).bind("hw", facts.hardwareBacked)
                .bind("jwk", """{"kty":"EC","crv":"P-256","x":"$canonX","y":"$canonY"}""").bind("thumb", thumb)
                .bind("att", buildJsonObject { put("security_level", facts.securityLevel); put("chain_length", facts.chainLength); put("root_sha256", facts.rootSha256); put("verified_boot_state", facts.verifiedBootState); put("device_locked", facts.deviceLocked) }.toString())
                .bind("cert", certHex).bind("tid", tok.id).bind("info", info).bind("ver", req.app_version).bind("zone", tok.zone).mapTo(Long::class.java).one()
            h.createUpdate("UPDATE app.enrolment_token SET used_count = used_count + 1 WHERE id = :i").bind("i", tok.id).execute()
            val rendered = policy.render(tok.lockdown)
            storePolicy(h, deviceId, tok.lockdown, rendered, fetched = true)
            req.status?.let { storeStatus(h, deviceId, null, "enrolment", it, now) }
            audit(h, null, "device", deviceId.toString(), "enrol", null, buildJsonObject { put("device_uuid", req.device_uuid); put("flavour", tok.flavour); put("lockdown_level", tok.lockdown); put("token_id", tok.id) }, null)
            EnrolDeviceResponse(deviceId, req.device_uuid, now.wire(), tok.lockdown, trust, rendered, now.wire())
        }
    }

    private class TokenRow(val id: Long, val flavour: String, val lockdown: String, val maxUses: Int, val used: Int, val zone: Long?, val expiresAt: Instant, val revoked: Boolean)

    // ---------------- device-proof endpoints ----------------

    class DeviceRow(val id: Long, val uuid: String, val status: String, val flavour: String, val lockdown: String, val key: ECPublicKey, val deviceOwner: Boolean, val trust: String, val integrity: String, val integrityAt: Instant?)

    /** The enrolled phone behind `X-Device-Id`, its key, and a state check: a revoked or replaced phone is refused, a suspended one may still report and fetch policy. */
    fun deviceByUuid(uuid: String): DeviceRow? = db.jdbi.withHandle<DeviceRow?, Exception> { h ->
        h.createQuery("SELECT id, device_uuid::text, status, flavour, lockdown_level, public_key_jwk::text, device_owner, trust_level, integrity_verdict, integrity_checked_at FROM app.device WHERE device_uuid = CAST(:u AS uuid)")
            .bind("u", uuid).map { rs, _ ->
                val key = runCatching { ECKey.parse(rs.getString(6)).toECPublicKey() }.getOrNull() ?: return@map null
                DeviceRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), key, rs.getBoolean(7), rs.getString(8), rs.getString(9), rs.getObject(10, OffsetDateTime::class.java)?.toInstant())
            }.findOne().orElse(null)
    }

    fun requireUsable(d: DeviceRow) {
        if (d.status == "revoked" || d.status == "replaced") throw ApiProblem(ProblemCode.ERR_DEVICE_REVOKED, "device revoked")
    }

    fun createNonce(d: DeviceRow): DeviceNonce {
        val raw = ByteArray(32).also { random.nextBytes(it) }; val nonce = b64u(raw)
        val exp = clock.now().plus(Duration.ofMinutes(10))
        db.jdbi.useHandle<Exception> { it.createUpdate("INSERT INTO app.device_nonce (nonce_sha256, device_id, purpose, expires_at) VALUES (:h, :d, 'play_integrity', :e)").bind("h", sha256(nonce.toByteArray())).bind("d", d.id).bind("e", ts(exp)).execute() }
        return DeviceNonce(nonce, exp.wire())
    }

    /** The policy of this phone and its ETag (the policy version). */
    fun policyFor(d: DeviceRow): Pair<JsonObject, Long> {
        val rendered = policy.render(d.lockdown)
        db.jdbi.useHandle<Exception> { storePolicy(it, d.id, d.lockdown, rendered, fetched = true) }
        return rendered to policy.policyVersion()
    }

    private fun storePolicy(h: Handle, deviceId: Long, lockdown: String, rendered: JsonObject, fetched: Boolean) {
        val text = rendered.toString()
        // generated_at changes on every render, so the hash is over the policy without it: the same policy is the same row.
        val stable = JsonObject(rendered.filterKeys { it != "generated_at" }).toString()
        h.createUpdate(
            """
            INSERT INTO app.device_policy (device_id, policy_version, lockdown_level, policy, policy_sha256, fetched_at) VALUES (:d, :v, :l, CAST(:p AS jsonb), :h, CASE WHEN :f THEN now() END)
            ON CONFLICT (device_id, policy_version) DO UPDATE SET policy = excluded.policy, policy_sha256 = excluded.policy_sha256, fetched_at = coalesce(excluded.fetched_at, app.device_policy.fetched_at)
            """,
        ).bind("d", deviceId).bind("v", policy.policyVersion()).bind("l", lockdown).bind("p", text).bind("h", sha256(stable.toByteArray())).bind("f", fetched).execute()
    }

    /** A status report from the phone (online path): stored, the device row updated, trust re-derived, pending directives handed back once. */
    fun report(d: DeviceRow, r: DeviceStatusReportDto, userId: Long? = null): DeviceStatusAck {
        val now = clock.now()
        return db.jdbi.inTransaction<DeviceStatusAck, Exception> { h ->
            storeStatus(h, d.id, userId, r.trigger ?: "periodic", r, now)
            val trust = deriveTrust(h, d.id)
            if (r.trigger == "directive") h.createUpdate("UPDATE app.device_directive SET acked_at = now() WHERE device_id = :d AND delivered_at IS NOT NULL AND acked_at IS NULL").bind("d", d.id).execute()
            val directives = h.createQuery(
                "SELECT directive_id, type, params::text, created_at, expires_at, acked_at, sig FROM app.device_directive WHERE device_id = :d AND delivered_at IS NULL AND expires_at > :now ORDER BY created_at LIMIT 5",
            ).bind("d", d.id).bind("now", ts(now)).map { rs, _ -> directive(rs) }.list()
            if (directives.isNotEmpty()) h.createUpdate("UPDATE app.device_directive SET delivered_at = now() WHERE directive_id = ANY(:ids)").bindArray("ids", UUID::class.java, directives.map { UUID.fromString(it.directive_id) }).execute()
            DeviceStatusAck(d.id, trust, policy.policyVersion(), directives, now.wire())
        }
    }

    private fun directive(rs: java.sql.ResultSet) = DirectiveDto(
        rs.getString(1), rs.getString(2), rs.getString(3)?.let { Json.parseToJsonElement(it) as? JsonObject }, rs.getObject(4, OffsetDateTime::class.java).toInstant().wire(),
        rs.getObject(5, OffsetDateTime::class.java).toInstant().wire(), rs.getObject(6, OffsetDateTime::class.java)?.toInstant()?.wire(), rs.getString(7),
    )

    private fun checkAppVersion(v: String, pointer: String) {
        if (!Regex("^\\d{1,3}[.]\\d{1,3}[.]\\d{1,3}[+]\\d{1,10}$").matches(v)) throw ApiProblem(ProblemCode.ERR_VALIDATION, "app_version must be <versionName>+<versionCode>", errors = listOf(FieldError(pointer, "invalid_value")))
    }

    private val TRIGGERS = setOf("enrolment", "policy_applied", "check_in", "check_out", "boot", "integrity_change", "app_update", "periodic", "directive")

    private fun storeStatus(h: Handle, deviceId: Long, userId: Long?, trigger: String, r: DeviceStatusReportDto, now: Instant) {
        val reportedAt = runCatching { Instant.parse(r.reported_at) }.getOrNull() ?: throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad reported_at", errors = listOf(FieldError("/reported_at", "invalid_value")))
        checkAppVersion(r.app_version, "/app_version")
        if (trigger !in TRIGGERS) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad trigger", errors = listOf(FieldError("/trigger", "invalid_value")))
        if (r.lockdown_level_applied !in setOf("dev", "prod")) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad lockdown_level_applied", errors = listOf(FieldError("/lockdown_level_applied", "invalid_value")))
        if (r.battery_pct !in 0..100 || r.pending_rows < 0) throw ApiProblem(ProblemCode.ERR_VALIDATION, "out of range", errors = listOf(FieldError("/battery_pct", "out_of_range")))
        if (r.play_integrity != null && r.play_integrity_unavailable != null) throw ApiProblem(ProblemCode.ERR_VALIDATION, "play_integrity and play_integrity_unavailable are exclusive", errors = listOf(FieldError("/play_integrity_unavailable", "conflict")))
        val dhaka = now.atZone(java.time.ZoneId.of("Asia/Dhaka")).toLocalDate()
        // Play Integrity tokens are decoded server-side only; until the Google decoder is wired (docs/requests/backend-reports-play-integrity.md) the verdict stays unevaluated.
        val report = JSON.encodeToString(DeviceStatusReportDto.serializer(), r.copy(play_integrity = r.play_integrity?.copy(token = "[redacted]")))
        h.createUpdate(
            """
            INSERT INTO app.device_status_report (source, device_id, user_id, business_date, reported_at, config_version, trigger, app_version, device_owner, lockdown_level_applied, policy_version_applied, blocking_active,
                                                   location_enabled, dev_options_enabled, adb_enabled, auto_time_enabled, mock_location_apps, pending_rows, battery_pct, integrity_verdict, report)
            VALUES (:src, :d, :u, :bd, :ra, :cv, :tr, :av, :own, :ll, :pv, :ba, :le, :do, :adb, :at, :mock, :pr, :bat, 'unevaluated', CAST(:rep AS jsonb))
            """,
        ).bind("src", if (trigger == "enrolment") "enrolment" else "online").bind("d", deviceId).bind("u", userId).bind("bd", dhaka).bind("ra", ts(reportedAt)).bind("cv", policy.policyVersion())
            .bind("tr", trigger).bind("av", r.app_version).bind("own", r.device_owner).bind("ll", r.lockdown_level_applied).bind("pv", r.policy_version_applied).bind("ba", r.blocking_active)
            .bind("le", r.location_enabled).bind("do", r.dev_options_enabled).bind("adb", r.adb_enabled).bind("at", r.auto_time_enabled)
            .bind("mock", r.mock_location_apps.toTypedArray()).bind("pr", r.pending_rows).bind("bat", r.battery_pct.toShort()).bind("rep", report).execute()
        h.createUpdate(
            """
            UPDATE app.device SET device_owner = :own, app_version = :av, policy_version_applied = coalesce(:pv, policy_version_applied), pending_rows_reported = :pr, last_contact_at = now(), device_info = CAST(:info AS jsonb)
             WHERE id = :d
            """,
        ).bind("own", r.device_owner).bind("av", r.app_version).bind("pv", r.policy_version_applied).bind("pr", r.pending_rows).bind("info", JSON.encodeToString(DeviceInfoDto.serializer(), r.device_info)).bind("d", deviceId).execute()
        if (r.policy_version_applied != null) h.createUpdate("UPDATE app.device_policy SET applied_at = now() WHERE device_id = :d AND policy_version = :v AND applied_at IS NULL").bind("d", deviceId).bind("v", r.policy_version_applied).execute()
    }

    /** Trust from what the server holds: a phone that stopped being device owner, or reports mock-location apps or root hints, drops to `low`; a pass on Play Integrity with an intact device is `high`. */
    private fun deriveTrust(h: Handle, deviceId: Long): DeviceTrustDto {
        val row = h.createQuery("SELECT status, device_owner, lockdown_level, integrity_verdict, integrity_checked_at, trust_level, hardware_backed_key FROM app.device WHERE id = :d").bind("d", deviceId)
            .map { rs, _ -> listOf(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime::class.java)?.toInstant(), rs.getString(6), rs.getBoolean(7)) }.one()
        val last = h.createQuery("SELECT device_owner, mock_location_apps, report FROM app.device_status_report WHERE device_id = :d ORDER BY reported_at DESC, id DESC LIMIT 1").bind("d", deviceId)
            .map { rs, _ -> Triple(rs.getBoolean(1), (rs.getArray(2).array as Array<*>).size, Json.parseToJsonElement(rs.getString(3)).jsonObject["root_hints"]?.let { (it as? kotlinx.serialization.json.JsonArray)?.size } ?: 0) }.findOne().orElse(null)
        val prod = row[2] == "prod"
        val level = when {
            row[5] == "blocked" -> "blocked"
            last != null && prod && (!last.first || last.second > 0 || last.third > 0) -> "low"
            row[3] == "pass" && row[6] == true && (last?.first ?: (row[1] as Boolean)) -> "high"
            else -> "normal"
        }
        if (level != row[5]) h.createUpdate("UPDATE app.device SET trust_level = :t WHERE id = :d").bind("t", level).bind("d", deviceId).execute()
        return DeviceTrustDto(true, (last?.first ?: (row[1] as Boolean)), row[3] as String, (row[4] as Instant?)?.wire(), level)
    }

    // ---------------- admin views ----------------

    private fun deviceDto(h: Handle, rs: java.sql.ResultSet, withStatus: Boolean): DeviceDto {
        val id = rs.getLong("id")
        fun t(c: String) = rs.getObject(c, OffsetDateTime::class.java)?.toInstant()?.wire()
        val users = h.createQuery("SELECT u.id, u.username, b.bind_ordinal, b.bound_at, b.status FROM app.device_binding b JOIN app.app_user u ON u.id = b.user_id WHERE b.device_id = :d ORDER BY b.bind_ordinal, b.id LIMIT 10").bind("d", id)
            .map { r, _ -> BoundUser(r.getLong(1), r.getString(2), r.getInt(3), r.getObject(4, OffsetDateTime::class.java).toInstant().wire(), r.getString(5)) }.list()
        val last = if (!withStatus) null else h.createQuery("SELECT report FROM app.device_status_report WHERE device_id = :d ORDER BY reported_at DESC, id DESC LIMIT 1").bind("d", id).map { r, _ -> Json.parseToJsonElement(r.getString(1)) }.findOne().orElse(null)
        return DeviceDto(
            id, rs.getString("device_uuid"), rs.getString("flavour"), rs.getString("status"), rs.getBoolean("device_owner"), rs.getString("lockdown_level"), rs.getString("trust_level"), rs.getBoolean("hardware_backed_key"),
            rs.getString("integrity_verdict"), t("integrity_checked_at"), t("enrolled_at")!!, rs.getObject("enrolment_token_id") as Long?, rs.getString("device_info")?.let { JSON.decodeFromString(DeviceInfoDto.serializer(), it) },
            rs.getString("app_version"), rs.getObject("policy_version_applied") as Long?, rs.getObject("config_version_applied") as Long?, t("last_contact_at"), rs.getObject("pending_rows_reported") as Int?, users, last,
        )
    }

    private fun inReach(reach: Reach, zoneId: Long?) = reach.national || (zoneId != null && zoneId in reach.zoneIds)

    fun listDevices(reach: Reach, status: String?, trust: String?, flavour: String?, zoneId: Long?, search: String?, limit: Int, after: Long?): DevicePage = db.readJdbi.withHandle<DevicePage, Exception> { h ->
        if (zoneId != null && !(reach.national || zoneId in reach.zoneIds)) throw ApiProblem(ProblemCode.ERR_FORBIDDEN, "zone is outside your reach")
        val w = mutableListOf("true")
        if (!reach.national) w += "d.zone_id = ANY(:rz)"
        if (status != null) w += "d.status = :status"; if (trust != null) w += "d.trust_level = :trust"; if (flavour != null) w += "d.flavour = :flavour"; if (zoneId != null) w += "d.zone_id = :zone"
        if (search != null) w += "(d.device_info ->> 'model' ILIKE :q OR d.device_uuid::text ILIKE :q OR EXISTS (SELECT 1 FROM app.device_binding b JOIN app.app_user u ON u.id = b.user_id WHERE b.device_id = d.id AND u.username ILIKE :q))"
        if (after != null) w += "d.id < :after"
        val q = h.createQuery("SELECT d.* FROM app.device d WHERE ${w.joinToString(" AND ")} ORDER BY d.id DESC LIMIT :lim").bind("lim", limit + 1)
        if (!reach.national) q.bindArray("rz", Long::class.javaObjectType, reach.zoneIds.toList().ifEmpty { listOf(-1L) })
        status?.let { q.bind("status", it) }; trust?.let { q.bind("trust", it) }; flavour?.let { q.bind("flavour", it) }; zoneId?.let { q.bind("zone", it) }
        search?.let { q.bind("q", "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%") }; after?.let { q.bind("after", it) }
        val rows = q.map { rs, _ -> deviceDto(h, rs, withStatus = false) }.list()
        DevicePage(rows.take(limit), if (rows.size > limit) rows[limit - 1].device_id.toString() else null)
    }

    fun getDevice(reach: Reach, id: Long): DeviceDto = db.readJdbi.withHandle<DeviceDto, Exception> { h ->
        h.createQuery("SELECT * FROM app.device WHERE id = :i").bind("i", id).map { rs, _ -> if (inReach(reach, rs.getObject("zone_id") as Long?)) deviceDto(h, rs, true) else null }.findOne().orElse(null)
            ?: throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such device")
    }

    fun statusHistory(reach: Reach, id: Long, limit: Int, after: Long?): DeviceStatusPage {
        getDevice(reach, id)
        return db.readJdbi.withHandle<DeviceStatusPage, Exception> { h ->
            val rows = h.createQuery("SELECT id, report FROM app.device_status_report WHERE device_id = :d AND (CAST(:after AS bigint) IS NULL OR id < :after) ORDER BY id DESC LIMIT :lim").bind("d", id).bind("after", after).bind("lim", limit + 1)
                .map { rs, _ -> rs.getLong(1) to Json.parseToJsonElement(rs.getString(2)) }.list()
            DeviceStatusPage(rows.take(limit).map { it.second }, if (rows.size > limit) rows[limit - 1].first.toString() else null)
        }
    }

    /** suspend, revoke, reactivate. Revoking blocks login and sync-as-this-device at once; the phone keeps capturing locally and the upload grant lives on for the grace window (docs/24 s8.7). */
    fun changeState(p: AronPrincipal, reach: Reach, id: Long, req: DeviceStateChangeRequest, requestId: String?): DeviceDto {
        if (req.action !in setOf("suspend", "revoke", "reactivate", "mark_replaced")) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad action", errors = listOf(FieldError("/action", "invalid_value")))
        if (req.action == "mark_replaced") throw ApiProblem(ProblemCode.ERR_VALIDATION, "use POST /v1/admin/devices/{device_id}/replace to move a user's bindings to a new phone", errors = listOf(FieldError("/action", "not_supported")))
        if (req.reason.trim().length !in 10..500) throw ApiProblem(ProblemCode.ERR_VALIDATION, "reason must be 10..500 characters", errors = listOf(FieldError("/reason", "out_of_range")))
        db.jdbi.useTransaction<Exception> { h ->
            val cur = h.createQuery("SELECT status, zone_id FROM app.device WHERE id = :i FOR UPDATE").bind("i", id).map { rs, _ -> rs.getString(1) to (rs.getObject(2) as Long?) }.findOne().orElse(null)
            if (cur == null || !inReach(reach, cur.second)) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such device")
            val target = when (req.action) { "suspend" -> "suspended"; "revoke" -> "revoked"; else -> "reactivated" }
            val next = when {
                req.action == "suspend" && cur.first in setOf("enrolled", "active") -> "suspended"
                req.action == "revoke" && cur.first in setOf("enrolled", "active", "suspended") -> "revoked"
                req.action == "reactivate" && cur.first == "suspended" -> h.createQuery("SELECT EXISTS (SELECT 1 FROM app.device_binding WHERE device_id = :i AND status = 'active')").bind("i", id).mapTo(Boolean::class.java).one().let { if (it) "active" else "enrolled" }
                cur.first == target || (req.action == "revoke" && cur.first == "revoked") || (req.action == "suspend" && cur.first == "suspended") -> return@useTransaction   // the same decision again changes nothing
                else -> throw ApiProblem(ProblemCode.ERR_REQUEST_STATE, "a device in state ${cur.first} cannot be ${target}")
            }
            h.createUpdate("UPDATE app.device SET status = :s, status_changed_at = now(), status_reason = :r WHERE id = :i").bind("s", next).bind("r", req.reason.trim().take(500)).bind("i", id).execute()
            audit(h, p, "device", id.toString(), req.action, buildJsonObject { put("status", cur.first) }, buildJsonObject { put("status", next) }, req.reason.trim(), requestId)
        }
        return getDevice(reach, id)
    }

    fun createDirective(p: AronPrincipal, reach: Reach, id: Long, req: DirectiveCreateRequest, requestId: String?): DirectiveDto {
        if (req.type !in setOf("send_status", "redownload_bundle", "upload_support_bundle", "resend_from")) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad type", errors = listOf(FieldError("/type", "invalid_value")))
        if (req.ttl_h !in 1..72) throw ApiProblem(ProblemCode.ERR_VALIDATION, "bad ttl_h", errors = listOf(FieldError("/ttl_h", "out_of_range")))
        if (req.type == "resend_from" && runCatching { java.time.LocalDate.parse(req.resend_from) }.isFailure) throw ApiProblem(ProblemCode.ERR_VALIDATION, "resend_from needs a date", errors = listOf(FieldError("/resend_from", "required")))
        val now = clock.now(); val dirId = UUID.randomUUID(); val exp = now.plus(Duration.ofHours(req.ttl_h.toLong()))
        val params = if (req.type == "resend_from") buildJsonObject { put("resend_from", req.resend_from!!) } else JsonObject(emptyMap())
        return db.jdbi.inTransaction<DirectiveDto, Exception> { h ->
            val dev = h.createQuery("SELECT device_uuid::text, zone_id FROM app.device WHERE id = :i").bind("i", id).map { rs, _ -> rs.getString(1) to (rs.getObject(2) as Long?) }.findOne().orElse(null)
            if (dev == null || !inReach(reach, dev.second)) throw ApiProblem(ProblemCode.ERR_NOT_FOUND, "no such device")
            // The signature binds the directive to this phone: a copy sent to another device does not verify there.
            val signed = listOf("aron-directive-v1", dirId.toString(), dev.first, req.type, now.wire(), exp.wire(), params.toString()).joinToString("\n")
            val sig = keys.signer.sign(JWSHeader(JWSAlgorithm.ES256), signed.toByteArray()).toString()
            h.createUpdate("INSERT INTO app.device_directive (directive_id, device_id, type, params, sig, created_by, created_at, expires_at) VALUES (:i, :d, :t, CAST(:p AS jsonb), :s, :u, :c, :e)")
                .bind("i", dirId).bind("d", id).bind("t", req.type).bind("p", params.toString()).bind("s", sig).bind("u", p.userId).bind("c", ts(now)).bind("e", ts(exp)).execute()
            audit(h, p, "device_directive", dirId.toString(), "create", null, buildJsonObject { put("device_id", id); put("type", req.type) }, null, requestId)
            DirectiveDto(dirId.toString(), req.type, params, now.wire(), exp.wire(), null, sig)
        }
    }

    fun listDirectives(reach: Reach, id: Long): DirectiveList {
        getDevice(reach, id)
        return db.readJdbi.withHandle<DirectiveList, Exception> { h ->
            DirectiveList(h.createQuery("SELECT directive_id, type, params::text, created_at, expires_at, acked_at, sig FROM app.device_directive WHERE device_id = :d ORDER BY created_at DESC LIMIT 100").bind("d", id).map { rs, _ -> directive(rs) }.list())
        }
    }

    private fun audit(h: Handle, p: AronPrincipal?, entity: String, entityId: String, action: String, before: JsonElement?, after: JsonElement?, reason: String?, requestId: String? = null) {
        h.createUpdate(
            "INSERT INTO app.audit_log (actor_user_id, actor_username, actor_role, via, entity, entity_id, action, before, after, reason, request_id) VALUES (:u, :n, :r, :via, :e, :id, :a, CAST(:b AS jsonb), CAST(:af AS jsonb), :reason, CAST(:rid AS uuid))",
        ).bind("u", p?.userId).bind("n", p?.username?.take(40)).bind("r", p?.role?.wire).bind("via", if (p == null) "device" else "api").bind("e", entity).bind("id", entityId.take(64)).bind("a", action)
            .bind("b", before?.toString()).bind("af", after?.toString()).bind("reason", reason?.take(500)).bind("rid", requestId?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }).execute()
    }
}

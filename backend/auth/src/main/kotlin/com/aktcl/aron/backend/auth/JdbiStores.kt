package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.contract.Role
import org.jdbi.v3.core.Handle
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

// SQL of backend:auth against the db lane's tables (V0002: app_user, refresh_family, refresh_token, auth_lockout).
// Bind parameters only (docs/24 s2.5).

internal fun ResultSet.instant(col: String): Instant? = getObject(col, OffsetDateTime::class.java)?.toInstant()
internal fun Instant.odt(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
private val HEX = HexFormat.of()

/** `app_user` reads, plus a short per-replica cache of `scope_version` for the bearer guard. */
class JdbiUserStore(private val db: Database, private val clock: AronClock = AronClock.SYSTEM, private val svCacheMs: Long = 10_000) :
    UserStore, ScopeVersionLookup {
    private val cols = "id, username, full_name, role, status, locale, designation, password_hash, scope_version, must_change_password"

    private fun map(rs: ResultSet) = UserRecord(
        id = rs.getLong("id"), username = rs.getString("username"), fullName = rs.getString("full_name"),
        role = Role.valueOf(rs.getString("role")), status = rs.getString("status"), locale = rs.getString("locale"),
        designation = rs.getString("designation"), passwordHash = rs.getString("password_hash"),
        scopeVersion = rs.getLong("scope_version"), mustChangePassword = rs.getBoolean("must_change_password"),
    )

    override fun findByUsername(username: String): UserRecord? = db.jdbi.withHandle<UserRecord?, Exception> { h ->
        h.createQuery("SELECT $cols FROM app.app_user WHERE lower(username) = lower(:u)").bind("u", username)
            .map { rs, _ -> map(rs) }.findOne().orElse(null)
    }

    override fun findById(id: Long): UserRecord? = db.jdbi.withHandle<UserRecord?, Exception> { h ->
        h.createQuery("SELECT $cols FROM app.app_user WHERE id = :id").bind("id", id).map { rs, _ -> map(rs) }.findOne().orElse(null)
    }

    private data class Sv(val value: Long?, val at: Long)
    private val svCache = ConcurrentHashMap<Long, Sv>()

    override fun current(userId: Long): Long? {
        val now = clock.now().toEpochMilli()
        svCache[userId]?.let { if (now - it.at < svCacheMs) return it.value }
        val v = db.jdbi.withHandle<Long?, Exception> { h ->
            h.createQuery("SELECT scope_version FROM app.app_user WHERE id = :id").bind("id", userId).mapTo(Long::class.java).findOne().orElse(null)
        }
        if (svCache.size > 100_000) svCache.clear()
        svCache[userId] = Sv(v, now)
        return v
    }
}

/** Refresh families and hashed tokens; rotation is atomic (the unused-token update and the child insert commit together). */
class JdbiRefreshStore(private val db: Database) : RefreshStore {
    override fun createFamily(family: NewFamily, tokenHash: String, tokenExpiresAt: Instant): Long = db.jdbi.inTransaction<Long, Exception> { h ->
        // The upload grant has no absolute end (idle only); the column is NOT NULL, so it carries a far horizon.
        val absolute = family.absoluteExpiresAt ?: family.createdAt.plus(Duration.ofDays(UPLOAD_HORIZON_DAYS))
        val id = h.createQuery(
            """INSERT INTO app.refresh_family (user_id, device_id, client, grant_kind, created_at, sliding_expires_at, absolute_expires_at)
               VALUES (:u, :d, :c, :g, :at, :sl, :abs) RETURNING id""",
        ).bind("u", family.userId).bind("d", family.deviceId).bind("c", clientOf(family.flavour)).bind("g", family.grant.wire)
            .bind("at", family.createdAt.odt()).bind("sl", tokenExpiresAt.odt()).bind("abs", absolute.odt())
            .mapTo(Long::class.java).one()
        insertToken(h, id, tokenHash, family.createdAt, tokenExpiresAt)
        id
    }

    private fun insertToken(h: Handle, familyId: Long, hash: String, at: Instant, expires: Instant): Long =
        h.createQuery("INSERT INTO app.refresh_token (family_id, token_sha256, created_at, expires_at) VALUES (:f, :h, :at, :e) RETURNING id")
            .bind("f", familyId).bind("h", HEX.parseHex(hash)).bind("at", at.odt()).bind("e", expires.odt()).mapTo(Long::class.java).one()

    override fun findToken(hash: String): TokenView? = db.jdbi.withHandle<TokenView?, Exception> { h ->
        h.createQuery(
            """SELECT t.expires_at, t.used_at, encode(c.token_sha256, 'hex') AS child,
                      f.id AS fid, f.user_id, f.device_id, f.client, f.grant_kind, f.absolute_expires_at, f.revoked_at, f.revoke_reason
               FROM app.refresh_token t
               JOIN app.refresh_family f ON f.id = t.family_id
               LEFT JOIN app.refresh_token c ON c.id = t.replaced_by_id
               WHERE t.token_sha256 = :h""",
        ).bind("h", HEX.parseHex(hash)).map { rs, _ ->
            val grant = if (rs.getString("grant_kind") == "upload") Grant.UPLOAD else Grant.FULL
            val abs = rs.instant("absolute_expires_at")
            TokenView(
                hash = hash,
                family = FamilyView(
                    id = rs.getLong("fid"), userId = rs.getLong("user_id"), deviceId = rs.getObject("device_id") as Long?,
                    grant = grant, flavour = flavourOf(rs.getString("client")),
                    absoluteExpiresAt = if (grant == Grant.UPLOAD) null else abs,
                    revokedAt = rs.instant("revoked_at"), revokeReason = rs.getString("revoke_reason"),
                ),
                expiresAt = rs.instant("expires_at")!!, usedAt = rs.instant("used_at"), replacedByHash = rs.getString("child"),
            )
        }.findOne().orElse(null)
    }

    override fun rotate(hash: String, at: Instant, childHash: String, childExpiresAt: Instant): Boolean = try {
        db.jdbi.inTransaction<Boolean, Exception> { h ->
            val familyId = h.createQuery("SELECT family_id FROM app.refresh_token WHERE token_sha256 = :h AND used_at IS NULL FOR UPDATE")
                .bind("h", HEX.parseHex(hash)).mapTo(Long::class.java).findOne().orElse(null) ?: return@inTransaction false
            val child = insertToken(h, familyId, childHash, at, childExpiresAt)
            h.createUpdate("UPDATE app.refresh_token SET used_at = :at, replaced_by_id = :c WHERE token_sha256 = :h")
                .bind("at", at.odt()).bind("c", child).bind("h", HEX.parseHex(hash)).execute()
            h.createUpdate("UPDATE app.refresh_family SET last_used_at = :at, sliding_expires_at = :sl WHERE id = :f")
                .bind("at", at.odt()).bind("sl", childExpiresAt.odt()).bind("f", familyId).execute()
            true
        }
    } catch (e: org.jdbi.v3.core.statement.UnableToExecuteStatementException) {
        // A concurrent rotation of the same token won (unique child hash): the caller applies the replay rule.
        if ((e.cause as? java.sql.SQLException)?.sqlState == "23505") false else throw e
    }

    override fun revokeFamily(familyId: Long, at: Instant, reason: String) {
        db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.refresh_family SET revoked_at = :at, revoke_reason = :r WHERE id = :f AND revoked_at IS NULL")
                .bind("at", at.odt()).bind("r", reason).bind("f", familyId).execute()
        }
    }

    companion object {
        const val UPLOAD_HORIZON_DAYS = 3650L
        fun clientOf(flavour: String) = if (flavour == "web") "web" else "app_$flavour"
        fun flavourOf(client: String) = client.removePrefix("app_")
    }
}

/** Lockout counters in `app.auth_lockout`, shared by every replica (fixed window from `window_started_at`). */
class JdbiLockoutStore(private val db: Database) : LockoutStore {
    override fun lockedUntil(key: String, now: Instant): Instant? = db.jdbi.withHandle<Instant?, Exception> { h ->
        h.createQuery("SELECT locked_until FROM app.auth_lockout WHERE lock_key = :k AND locked_until > :now")
            .bind("k", key).bind("now", now.odt()).map { rs, _ -> rs.instant("locked_until") }.findOne().orElse(null)
    }

    override fun recordFailure(key: String, now: Instant, window: Duration): Int = db.jdbi.withHandle<Int, Exception> { h ->
        h.createQuery(
            """INSERT INTO app.auth_lockout AS l (lock_key, failures, window_started_at, updated_at) VALUES (:k, 1, :now, :now)
               ON CONFLICT (lock_key) DO UPDATE SET
                 failures = CASE WHEN l.window_started_at <= :start THEN 1 ELSE l.failures + 1 END,
                 window_started_at = CASE WHEN l.window_started_at <= :start THEN :now ELSE l.window_started_at END,
                 updated_at = :now
               RETURNING failures""",
        ).bind("k", key).bind("now", now.odt()).bind("start", now.minus(window).odt()).mapTo(Int::class.java).one()
    }

    override fun lock(key: String, now: Instant, base: Duration): Instant = db.jdbi.withHandle<Instant, Exception> { h ->
        h.createQuery(
            """UPDATE app.auth_lockout SET
                 locked_until = :now + make_interval(secs => least(:base * power(2, least(lock_count, 10)), 86400)),
                 lock_count = lock_count + 1, failures = 0, updated_at = :now
               WHERE lock_key = :k RETURNING locked_until""",
        ).bind("k", key).bind("now", now.odt()).bind("base", base.seconds.toDouble()).map { rs, _ -> rs.instant("locked_until")!! }.one()
    }

    override fun reset(key: String) {
        db.jdbi.useHandle<Exception> { h -> h.createUpdate("DELETE FROM app.auth_lockout WHERE lock_key = :k").bind("k", key).execute() }
    }
}

/** Until the device tables land (N-007, V0010) no phone is known; `cfg.device.require_enrolled` decides the rest. */
object NoDevices : DeviceStore {
    override fun findByUuid(uuid: String): DeviceRecord? = null
    override fun bindOrdinal(userId: Long, deviceId: Long): Int? = null
}

package com.aktcl.aron.backend.auth

import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.FieldError
import com.aktcl.aron.contract.Role
import java.time.Instant

/**
 * Password policy of POST /v1/auth/change-password (F-SYS-004, docs/21 s4 table "Web policy" and "Field roles",
 * docs/09 Credentials): web roles at least `cfg.auth.password_min_len` (12) with lower case, upper case and a digit;
 * field roles (SR, AMO, TSO) at least 8 and not on the deny-list. History (not one of the last 10) and the 24 h minimum
 * age are checked by [LoginService.changePassword] because they need the stored hashes.
 */
object PasswordPolicy {
    const val HISTORY_DEPTH = 10
    const val MIN_AGE_HOURS = 24L
    val FIELD_ROLES = setOf(Role.SR, Role.AMO, Role.TSO)

    /**
     * The words a field password may not be or contain (case-insensitive): the product and company names and the
     * most common passwords. REQUEST: docs/requests/backend-core-password-history.md asks db for the full
     * top-10,000 list as reference data; until then this short list holds.
     */
    private val DENY = setOf(
        "aktcl", "aron", "apsis", "password", "passw0rd", "p@ssw0rd", "12345678", "123456789", "1234567890", "87654321",
        "11111111", "00000000", "12341234", "11223344", "qwertyui", "qwerty123", "asdfghjk", "abcd1234", "abc12345", "iloveyou",
        "admin123", "welcome1", "letmein1", "bangladesh", "dhaka123", "sunshine", "princess", "football", "baseball", "superman",
        "trustno1", "changeme", "default1", "zxcvbnm1", "1q2w3e4r", "q1w2e3r4", "aaaaaaaa", "computer", "internet", "monkey12",
    )

    /** Field errors of [password] for [role] and [username]; empty when the password is acceptable. */
    fun violations(password: String, role: Role, username: String, minLen: Int, denylistEnabled: Boolean = true): List<FieldError> {
        val p = "/new_password"
        val out = ArrayList<FieldError>()
        if (password.codePointCount(0, password.length) < minLen) out += FieldError(p, "too_short", "at least $minLen characters")
        if (role !in FIELD_ROLES) {
            if (password.none { it.isLowerCase() }) out += FieldError(p, "missing_lower", "a lower-case letter")
            if (password.none { it.isUpperCase() }) out += FieldError(p, "missing_upper", "an upper-case letter")
            if (password.none { it.isDigit() }) out += FieldError(p, "missing_digit", "a digit")
        } else if (denylistEnabled) {
            val l = password.lowercase()
            if (l in DENY || DENY.any { it.length <= 5 && it in l } || (username.length >= 3 && username.lowercase() in l)) {
                out += FieldError(p, "too_common", "a common word, the product name or the username")
            }
        }
        if (password.isBlank() || password.trim() != password) out += FieldError(p, "invalid_value", "no leading or trailing spaces")
        return out
    }
}

/** Stored password state of one user (row-locked in [PasswordStore.change]). */
data class PasswordState(val currentHash: String?, val changedAt: Instant?, val mustChange: Boolean, val history: List<String>)

interface PasswordStore {
    /** The current hash, the last change, the temporary flag and up to [depth] earlier hashes, newest first. */
    fun state(userId: Long, depth: Int): PasswordState?

    /**
     * In one transaction: re-checks that the hash is still [expectedHash] (a concurrent change wins once), stores
     * [newHash], clears the temporary flag, keeps the old hash in the history and revokes the user's full-grant refresh
     * families except the caller phone's own ([keepDeviceId]); upload grants survive (D24-57). False when the hash moved.
     */
    fun change(userId: Long, expectedHash: String?, newHash: String, at: Instant, keepDeviceId: Long?): Boolean
}

class JdbiPasswordStore(private val db: Database) : PasswordStore {
    /** REQUEST: docs/requests/backend-core-password-history.md (db). Until app.password_history exists only the current hash counts. */
    private fun hasHistory(h: org.jdbi.v3.core.Handle): Boolean =
        h.createQuery("SELECT to_regclass('app.password_history') IS NOT NULL").mapTo(Boolean::class.java).one()

    override fun state(userId: Long, depth: Int): PasswordState? = db.jdbi.withHandle<PasswordState?, Exception> { h ->
        val row = h.createQuery("SELECT password_hash, password_changed_at, must_change_password FROM app.app_user WHERE id = :u").bind("u", userId)
            .map { rs, _ -> Triple(rs.getString(1), rs.instant("password_changed_at"), rs.getBoolean(3)) }.findOne().orElse(null) ?: return@withHandle null
        val history = if (!hasHistory(h)) emptyList() else
            h.createQuery("SELECT password_hash FROM app.password_history WHERE user_id = :u ORDER BY changed_at DESC, id DESC LIMIT :n")
                .bind("u", userId).bind("n", depth).mapTo(String::class.java).list()
        PasswordState(row.first, row.second, row.third, history)
    }

    override fun change(userId: Long, expectedHash: String?, newHash: String, at: Instant, keepDeviceId: Long?): Boolean =
        db.jdbi.inTransaction<Boolean, Exception> { h ->
            val current = h.createQuery("SELECT password_hash FROM app.app_user WHERE id = :u FOR UPDATE").bind("u", userId)
                .mapTo(String::class.java).findOne().orElse(null)
            if (current != expectedHash) return@inTransaction false
            h.createUpdate("UPDATE app.app_user SET password_hash = :p, password_changed_at = :at, must_change_password = false WHERE id = :u")
                .bind("p", newHash).bind("at", at.odt()).bind("u", userId).execute()
            if (current != null && hasHistory(h)) {
                h.createUpdate("INSERT INTO app.password_history (user_id, password_hash, changed_at) VALUES (:u, :p, :at)")
                    .bind("u", userId).bind("p", current).bind("at", at.odt()).execute()
            }
            h.createUpdate(
                """
                UPDATE app.refresh_family SET revoked_at = :at, revoke_reason = 'password_changed'
                WHERE user_id = :u AND grant_kind = 'full' AND revoked_at IS NULL
                  AND (CAST(:keep AS bigint) IS NULL OR device_id IS DISTINCT FROM CAST(:keep AS bigint))
                """.trimIndent(),
            ).bind("at", at.odt()).bind("u", userId).bind("keep", keepDeviceId).execute()
            true
        }
}

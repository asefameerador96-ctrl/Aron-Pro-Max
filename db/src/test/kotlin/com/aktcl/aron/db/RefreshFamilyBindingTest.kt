package com.aktcl.aron.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V0013: a phone refresh family is always bound to a device row or a device_uuid; web families need neither.
 * V0072/V0073: refresh_family.amr (docs/requests/backend-core-refresh-family-amr.md).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefreshFamilyBindingTest {
    private lateinit var db: TestDatabase

    @BeforeAll
    fun setUp() {
        db = TestPostgres.createDatabase().migrated()
        db.connect().use { it.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('sr0001', 'SR', 'SR')") }
    }

    @AfterAll
    fun tearDown() = db.close()

    private fun family(client: String, deviceUuid: String?) =
        "INSERT INTO app.refresh_family (user_id, client, grant_kind, sliding_expires_at, absolute_expires_at, device_uuid) " +
            "SELECT id, '$client', 'full', now() + interval '30 days', now() + interval '90 days', " +
            (deviceUuid?.let { "'$it'" } ?: "NULL") + " FROM app.app_user WHERE username = 'sr0001'"

    @Test
    fun aLegacyUnboundPhoneFamilyIsRevokedByTheMigrationAndStaysUpdatable() = TestPostgres.createDatabase().use { old ->
        org.flywaydb.core.Flyway.configure().configuration(old.flyway().configuration).target("12").load().migrate()
        old.connect().use { c ->
            c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('sr0002', 'SR', 'SR')")
            c.exec(
                "INSERT INTO app.refresh_family (user_id, client, grant_kind, sliding_expires_at, absolute_expires_at) " +
                    "SELECT id, 'app_sr', 'full', now() + interval '30 days', now() + interval '90 days' FROM app.app_user WHERE username = 'sr0002'",
            )
        }
        old.flyway().migrate()
        old.connect().use { c ->
            assertEquals("device_revoked", c.scalar("SELECT revoke_reason FROM app.refresh_family"))
            c.exec("UPDATE app.refresh_family SET last_used_at = now()")               // still updatable
            assertEquals("t", c.scalar("SELECT convalidated FROM pg_constraint WHERE conname = 'refresh_family_phone_bound'"))
        }
    }

    @Test
    fun anUnboundPhoneFamilyIsRefused() = db.connect().use { c ->
        c.autoCommit = false
        try {
            c.exec(family("web", null))
            c.exec(family("app_sr", "6f1c2b0e-8d1a-4c5e-9f3a-2b7d4e6a8c10"))
            c.exec("SAVEPOINT s")
            assertEquals("23514", assertFailsWith<SQLException> { c.exec(family("app_sr", null)) }.sqlState)
            c.exec("ROLLBACK TO SAVEPOINT s")
            assertEquals("23514", assertFailsWith<SQLException> { c.exec("UPDATE app.refresh_family SET device_uuid = NULL WHERE client = 'app_sr'") }.sqlState)
        } finally {
            c.rollback()
        }
    }

    @Test
    fun anExistingFamilyGetsPwdAndAnMfaFamilyKeepsMfa() = TestPostgres.createDatabase().use { old ->
        org.flywaydb.core.Flyway.configure().configuration(old.flyway().configuration).target("71").load().migrate()
        old.connect().use { c ->
            c.exec("INSERT INTO app.app_user (username, full_name, role) VALUES ('web0001', 'Web', 'SR')")
            c.exec(
                "INSERT INTO app.refresh_family (user_id, client, grant_kind, sliding_expires_at, absolute_expires_at) " +
                    "SELECT id, 'web', 'full', now() + interval '1 day', now() + interval '7 days' FROM app.app_user WHERE username = 'web0001'",
            )
        }
        old.flyway().migrate()
        old.connect().use { c ->
            assertEquals("{pwd}", c.scalar("SELECT amr::text FROM app.refresh_family"))
            c.exec("UPDATE app.refresh_family SET amr = '{pwd,mfa}'")
            assertEquals("{pwd,mfa}", c.scalar("SELECT amr::text FROM app.refresh_family"))
            for (bad in listOf("{mfa}", "{}", "{pwd,otp}")) {
                assertEquals("23514", assertFailsWith<SQLException>(bad) { c.exec("UPDATE app.refresh_family SET amr = '$bad'") }.sqlState)
            }
            assertEquals("t", c.scalar("SELECT convalidated FROM pg_constraint WHERE conname = 'refresh_family_amr_known'"))
            assertEquals("t|t|t", c.scalar("SELECT concat_ws('|', has_column_privilege('auth_rw', 'app.refresh_family', 'amr', 'SELECT'), has_column_privilege('auth_rw', 'app.refresh_family', 'amr', 'INSERT'), has_column_privilege('auth_rw', 'app.refresh_family', 'amr', 'UPDATE'))"))
        }
    }
}

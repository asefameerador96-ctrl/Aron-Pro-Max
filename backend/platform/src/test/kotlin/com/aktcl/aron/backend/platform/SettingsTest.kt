package com.aktcl.aron.backend.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SettingsTest {
    @Test
    fun missingSigningKeyFailsWithTheVariableName() {
        val e = assertFailsWith<SettingsException> { Settings.load(mapOf("ARON_ROLE" to "api")) }
        assertContains(e.message!!, "ARON_JWT_SIGNING_KEY")
    }

    @Test
    fun migrateRoleNeedsNoSigningKeyButProdNeedsADatabaseUrl() {
        assertEquals(ServerRole.MIGRATE, Settings.load(mapOf("ARON_ROLE" to "migrate")).role)
        assertFailsWith<SettingsException> { Settings.load(mapOf("ARON_ROLE" to "migrate", "ARON_ENV" to "prod")) }
    }

    @Test
    fun secretsComeFromEnvOrFileAndAreNeverPrinted() {
        val f = File.createTempFile("aron-secret", ".txt").apply { writeText("s3cr3t-value\n"); deleteOnExit() }
        val s = Settings.load(mapOf("ARON_ROLE" to "migrate", "ARON_DB_PASSWORD_FILE" to f.absolutePath))
        assertEquals("s3cr3t-value", s.dbPassword!!.reveal())
        assertFalse(s.toString().contains("s3cr3t-value"))
        val e = Settings.load(mapOf("ARON_ROLE" to "migrate", "ARON_DB_PASSWORD" to "from-env"))
        assertEquals("from-env", e.dbPassword!!.reveal())
    }

    @Test
    fun badValuesAreRefused() {
        assertFailsWith<SettingsException> { Settings.load(mapOf("ARON_ROLE" to "web")) }
        assertFailsWith<SettingsException> { Settings.load(mapOf("ARON_ROLE" to "migrate", "PORT" to "-1")) }
    }
}

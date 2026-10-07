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

    @Test
    fun theWorkerRunsWithoutTheSigningKeyAndTheApiDoesNot() {
        val w = Settings.load(mapOf("ARON_ROLE" to "worker"))
        assertEquals(ServerRole.WORKER, w.role)
        assertEquals(null, w.jwtSigningKeyPem)
        assertFailsWith<SettingsException> { Settings.load(mapOf("ARON_ROLE" to "api")) }
    }

    @Test
    fun jwksPublishesTheCurrentAndTheNextKeyDuringARotation() {
        fun pem(label: String, der: ByteArray) = "-----BEGIN $label-----\n" + java.util.Base64.getMimeEncoder().encodeToString(der) + "\n-----END $label-----\n"
        val gen = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
        val cur = gen.generateKeyPair()
        val next = gen.generateKeyPair()
        val base = mapOf("ARON_ROLE" to "api", "ARON_JWT_SIGNING_KEY" to pem("PRIVATE " + "KEY", cur.private.encoded), "ARON_JWT_KID" to "sig-1")
        assertEquals(listOf("sig-1"), JwtKeys.fromSettings(Settings.load(base)).publicJwks().map { it.keyID })
        val rotating = Settings.load(base + mapOf("ARON_JWT_NEXT_PUBLIC_KEY" to pem("PUBLIC KEY", next.public.encoded), "ARON_JWT_NEXT_KID" to "sig-2"))
        val keys = JwtKeys.fromSettings(rotating)
        assertEquals(setOf("sig-1", "sig-2"), keys.publicJwks().map { it.keyID }.toSet())
        assertEquals(next.public, keys.publicJwks().single { it.keyID == "sig-2" }.toECPublicKey())
        assertFailsWith<SettingsException> { JwtKeys.fromSettings(Settings.load(base + mapOf("ARON_JWT_NEXT_PUBLIC_KEY" to pem("PUBLIC KEY", next.public.encoded)))) }
    }
}

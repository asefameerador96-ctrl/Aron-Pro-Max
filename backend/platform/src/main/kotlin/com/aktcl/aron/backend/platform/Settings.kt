package com.aktcl.aron.backend.platform

import java.io.File

/** A secret value: never printed, never logged (docs/24 s13.6). */
class Secret(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = "Secret(***)"
}

enum class ServerRole { API, WORKER, MIGRATE }
enum class ServerEnv { DEV, PROD }

class SettingsException(message: String) : RuntimeException(message)

/**
 * Server settings from the environment (docs/24 s6.4). In Azure, secrets arrive as Container Apps secrets that are
 * Key Vault references (managed identity), exposed to the process as environment variables; a mounted secret file
 * named by `<NAME>_FILE` is accepted as well. Locally the same variables are set by hand; non-secret values have
 * local fallbacks. Secrets have no fallback: a missing secret fails at startup with the variable name, never a value.
 */
data class Settings(
    val role: ServerRole,
    val env: ServerEnv,
    val port: Int,
    val build: String,
    val dbUrl: String,
    val dbUser: String?,
    val dbPassword: Secret?,
    val dbReadUrl: String?,
    val dbPoolMax: Int,
    val dbReadPoolMax: Int,
    /** ES256 private key, PKCS#8 PEM (Key Vault secret aron-jwt-signing-key). Null only where the role needs none. */
    val jwtSigningKeyPem: Secret?,
    val jwtKid: String,
    /** Optional public key (SPKI PEM) of the next signing key, published in JWKS 24 h before it signs (s8.2). */
    val jwtNextPublicKeyPem: String?,
    val jwtNextKid: String?,
    /** Concurrent Argon2id verifications per replica (memory guard for the login storm). */
    val hashConcurrency: Int,
    val hashQueueMax: Int,
) {
    companion object {
        fun load(env: Map<String, String> = System.getenv()): Settings {
            val src = SettingSource(env)
            val role = when (val r = src.get("ARON_ROLE") ?: "api") {
                "api" -> ServerRole.API
                "worker" -> ServerRole.WORKER
                "migrate" -> ServerRole.MIGRATE
                else -> throw SettingsException("ARON_ROLE must be api, worker or migrate (was '$r')")
            }
            val serverEnv = when (val e = src.get("ARON_ENV") ?: "dev") {
                "dev" -> ServerEnv.DEV
                "prod" -> ServerEnv.PROD
                else -> throw SettingsException("ARON_ENV must be dev or prod (was '$e')")
            }
            val prod = serverEnv == ServerEnv.PROD
            val dbUrl = src.get("ARON_DB_URL")
                ?: if (prod) throw SettingsException("ARON_DB_URL is required in prod") else "jdbc:postgresql://localhost:5432/aron"
            val jwtKey = src.secret("ARON_JWT_SIGNING_KEY")
            if (jwtKey == null && role != ServerRole.MIGRATE) {
                throw SettingsException(
                    "ARON_JWT_SIGNING_KEY (or ARON_JWT_SIGNING_KEY_FILE) is required for role ${role.name.lowercase()}: " +
                        "an ES256 PKCS#8 PEM from Key Vault secret aron-jwt-signing-key. Locally: " +
                        "openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out jwt.pem; " +
                        "export ARON_JWT_SIGNING_KEY_FILE=jwt.pem (never commit it)",
                )
            }
            return Settings(
                role = role,
                env = serverEnv,
                port = src.int("PORT", 8080),
                build = src.get("ARON_BUILD") ?: "dev",
                dbUrl = dbUrl,
                dbUser = src.get("ARON_DB_USER"),
                dbPassword = src.secret("ARON_DB_PASSWORD"),
                dbReadUrl = src.get("ARON_DB_READ_URL"),
                dbPoolMax = src.int("ARON_DB_POOL_MAX", 10),
                dbReadPoolMax = src.int("ARON_DB_READ_POOL_MAX", 10),
                jwtSigningKeyPem = jwtKey,
                jwtKid = src.get("ARON_JWT_KID") ?: "sig-1",
                jwtNextPublicKeyPem = src.get("ARON_JWT_NEXT_PUBLIC_KEY"),
                jwtNextKid = src.get("ARON_JWT_NEXT_KID"),
                hashConcurrency = src.int("ARON_HASH_CONCURRENCY", 4),
                hashQueueMax = src.int("ARON_HASH_QUEUE_MAX", 32),
            )
        }
    }
}

private class SettingSource(private val env: Map<String, String>) {
    fun get(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

    fun secret(name: String): Secret? {
        get(name)?.let { return Secret(it) }
        val path = get("${name}_FILE") ?: return null
        val f = File(path)
        if (!f.isFile) throw SettingsException("${name}_FILE points to a missing file")
        return Secret(f.readText().trim())
    }

    fun int(name: String, default: Int): Int {
        val raw = get(name) ?: return default
        return raw.toIntOrNull()?.takeIf { it > 0 } ?: throw SettingsException("$name must be a positive integer")
    }
}

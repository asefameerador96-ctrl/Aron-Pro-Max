package com.aktcl.aron.core.session

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Encrypted per-user session files under the app's no-backup directory (allowBackup is false as well):
 * `profiles/<sha256(username)>.bin` (profile and offline verifier), `tokens/u<user_id>.bin` and `active.bin`.
 * Every write goes to a synced temp file and is renamed, so a kill or power loss mid-write leaves the previous version.
 */
class SessionStore(private val root: File, private val cipher: SecretCipher) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun profileByUsername(username: String): UserProfile? = read(profileFile(username), UserProfile.serializer())

    fun profileByUserId(userId: Long): UserProfile? =
        File(root, "profiles").listFiles { f -> f.name.endsWith(".bin") }.orEmpty().asSequence()
            .mapNotNull { read(it, UserProfile.serializer()) }
            .firstOrNull { it.userId == userId }

    fun saveProfile(profile: UserProfile) = write(profileFile(profile.username), UserProfile.serializer(), profile)

    fun tokens(userId: Long): StoredTokens = read(tokenFile(userId), StoredTokens.serializer()) ?: StoredTokens()

    fun saveTokens(userId: Long, tokens: StoredTokens) = write(tokenFile(userId), StoredTokens.serializer(), tokens)

    fun active(): ActiveSession? = read(File(root, "active.bin"), ActiveSession.serializer())

    fun saveActive(active: ActiveSession?) {
        val f = File(root, "active.bin")
        if (active == null) f.delete() else write(f, ActiveSession.serializer(), active)
    }

    private fun profileFile(username: String) = File(File(root, "profiles"), sha256(normalize(username)) + ".bin")
    private fun tokenFile(userId: Long) = File(File(root, "tokens"), "u$userId.bin")

    private fun <T> read(file: File, serializer: KSerializer<T>): T? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(serializer, cipher.decrypt(file.readBytes()).toString(Charsets.UTF_8)) }.getOrNull()
    }

    @Synchronized
    private fun <T> write(file: File, serializer: KSerializer<T>, value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(cipher.encrypt(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)))
            out.fd.sync() // survive power loss, not only a kill: an unreadable token file would lose the upload grant
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot replace ${file.name}" }
        }
    }

    companion object {
        /** Usernames are case-insensitive (contract `Username`). */
        fun normalize(username: String): String = username.trim().lowercase()

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

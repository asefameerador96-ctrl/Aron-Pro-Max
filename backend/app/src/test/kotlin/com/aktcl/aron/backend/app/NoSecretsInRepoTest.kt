package com.aktcl.aron.backend.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** N-009 acceptance: no secret is in the repository (docs/24 s13.6). Scans every tracked-looking text file. */
class NoSecretsInRepoTest {
    private val patterns = listOf(
        Regex("-----BEGIN (EC |RSA |ENCRYPTED |OPENSSH )?PRIVATE KEY-----"),
        Regex("Account" + "Key=[A-Za-z0-9+/=]{20,}"),
        Regex("SharedAccess" + "Signature=sig="),
        Regex("\"private_key\"\\s*:\\s*\"-----BEGIN"),
        Regex("Instrumentation" + "Key=[0-9a-f]{8}-"),
    )
    private val skipDirs = setOf(".git", "build", ".gradle", "node_modules", ".next", ".idea", ".kotlin")

    @Test
    fun noPrivateKeysOrConnectionSecrets() {
        val root = File(System.getProperty("aron.repoRoot") ?: error("aron.repoRoot not set"))
        val hits = root.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile && it.length() < 2_000_000 && it.extension !in setOf("png", "jpg", "jpeg", "pdf", "jar", "apk", "webp", "xlsx", "zip") }
            .flatMap { f -> f.readText().lineSequence().withIndex().filter { (_, l) -> patterns.any { it.containsMatchIn(l) } }.map { "${f.relativeTo(root)}:${it.index + 1}" } }
            .toList()
        assertTrue(hits.isEmpty(), "secret-like content found: $hits")
    }
}

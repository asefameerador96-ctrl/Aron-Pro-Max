package com.aktcl.aron.core.geo.integrity

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootHintsTest {
    private val pkg = "com.aktcl.aron.sr"

    private class Probe(
        val files: Set<String> = emptySet(),
        override val buildTags: String? = "release-keys",
        val props: Map<String, String> = mapOf("ro.debuggable" to "0", "ro.secure" to "1"),
        val packages: Set<String> = emptySet(),
        val mountText: String = "/dev/block/dm-0 / ext4 ro 0 0",
        override val userId: Int = 0,
        override val dataDir: String = "/data/user/0/com.aktcl.aron.sr",
        val throwing: Boolean = false,
    ) : RootProbe {
        override fun exists(path: String) = if (throwing) throw SecurityException() else path in files
        override fun systemProperty(name: String) = if (throwing) throw SecurityException() else props[name]
        override fun installed(packageName: String) = if (throwing) throw SecurityException() else packageName in packages
        override fun mounts() = if (throwing) throw SecurityException() else mountText
    }

    @Test fun aCleanPhoneHasNoHints() = assertEquals(emptyList<String>(), RootHints.evaluate(Probe(), pkg))

    @Test fun eachHintIsDetected() {
        val p = Probe(
            files = setOf("/system/xbin/su"), buildTags = "test-keys", props = mapOf("ro.debuggable" to "1", "ro.secure" to "0"),
            packages = setOf("com.topjohnwu.magisk", "org.lsposed.manager", "com.lbe.parallel.intl"),
            mountText = "magisk /system/bin tmpfs rw 0 0", userId = 999, dataDir = "/data/user/999/com.aktcl.aron.sr",
        )
        assertEquals(
            listOf("su_binary", "test_keys", "ro_debuggable", "ro_secure_off", "root_app", "hook_framework", "root_mount",
                "clone_app_installed", "secondary_user", "foreign_data_dir"),
            RootHints.evaluate(p, pkg),
        )
    }

    @Test fun cloneAndWorkProfileCopiesAreFlagged() {
        assertEquals(listOf("secondary_user", "foreign_data_dir"), RootHints.evaluate(Probe(userId = 95, dataDir = "/data/user/95/com.aktcl.aron.sr"), pkg))
        assertEquals(listOf("foreign_data_dir"), RootHints.evaluate(Probe(dataDir = "/data/data/com.lbe.parallel.intl/parallel/0/com.aktcl.aron.sr"), pkg))
        assertEquals(emptyList<String>(), RootHints.evaluate(Probe(dataDir = "/data/data/com.aktcl.aron.sr"), pkg))
    }

    @Test fun aProbeThatThrowsNeverBreaksTheRead() {
        assertEquals(listOf("foreign_data_dir").filter { false }, RootHints.evaluate(Probe(throwing = true), pkg))
    }

    @Test fun theTrackerReportsEachChangeOnce() {
        val t = IntegritySignalsTracker(Files.createTempDirectory("sig").toFile())
        val clean = IntegritySignals(false, false, true, true, emptyList(), emptyList())
        assertTrue(t.changed(clean))
        assertFalse(t.changed(clean))
        assertTrue(t.changed(clean.copy(adbEnabled = true)))
        assertFalse(t.changed(clean.copy(adbEnabled = true)))
        assertFalse(t.changed(clean.copy(adbEnabled = true, mockLocationApps = listOf()).copy(rootHints = listOf())))
        assertTrue(t.changed(clean.copy(adbEnabled = true, rootHints = listOf("su_binary"))))
    }
}

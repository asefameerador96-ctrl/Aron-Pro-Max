package com.aktcl.aron.core.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabaseKeysTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aUserKeepsOneKeyStoredOnlyWrapped() {
        val cipher = JvmAesCipher()
        val keys = DatabaseKeys(tmp.root, cipher)
        val a = keys.passphrase(334001)
        assertEquals(32, a.size)
        assertArrayEquals(a, DatabaseKeys(tmp.root, cipher).passphrase(334001)) // survives a relaunch
        assertFalse(a.contentEquals(keys.passphrase(334002))) // users never share a key
        assertFalse(File(tmp.root, "dbkey-u334001.bin").readBytes().toList().windowed(32).any { it == a.toList() })
    }

    @Test fun aKeyThatCannotBeUnwrappedIsAnErrorNotAReplacement() {
        DatabaseKeys(tmp.root, JvmAesCipher()).passphrase(1)
        val before = File(tmp.root, "dbkey-u1.bin").readBytes()
        assertThrows(Exception::class.java) { DatabaseKeys(tmp.root, JvmAesCipher()).passphrase(1) }
        assertArrayEquals(before, File(tmp.root, "dbkey-u1.bin").readBytes())
    }
}

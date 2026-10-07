package com.aktcl.aron.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The scanner judges shipped source only: a literal in src/main is flagged, the same literal in any test source set is not. */
class ScannerSourceSetsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val literal = "package x\n@Composable fun S() {\n    Text(\"Shop closed today\")\n}\n"

    private fun write(androidRoot: File, set: String) {
        File(androidRoot, "feature-x/src/$set/kotlin/x").apply { mkdirs() }.resolve("S.kt").writeText(literal)
    }

    @Test fun aLiteralInMainIsFlaggedButNotInAnyTestSourceSet() {
        val root = tmp.newFolder("android")
        listOf("test", "testContract", "testDebug", "testFixtures", "androidTest", "sharedTest").forEach { write(root, it) }
        assertTrue("test source sets must not be scanned", HardcodedStringScanner.scanTree(root).isEmpty())
        write(root, "main")
        val v = HardcodedStringScanner.scanTree(root)
        assertEquals(1, v.size)
        assertTrue(v.single().file.contains("src/main/"))
    }

    @Test fun debugAndReleaseAndFlavourSetsShip() {
        val root = tmp.newFolder("android")
        listOf("debug", "release", "full").forEach { write(root, it) }
        assertEquals(3, HardcodedStringScanner.scanTree(root).size)
    }

    @Test fun testSourceSetNames() {
        listOf("test", "testContract", "testDebug", "androidTest", "testFixtures", "sharedTest", "integrationTest").forEach { assertTrue(it, HardcodedStringScanner.isTestSourceSet(it)) }
        listOf("main", "debug", "release", "full", "dev", "contract").forEach { assertFalse(it, HardcodedStringScanner.isTestSourceSet(it)) }
    }

    @Test fun theIgnoreMarkerStillWorks() {
        val root = tmp.newFolder("android")
        File(root, "feature-x/src/main/kotlin/x").apply { mkdirs() }.resolve("S.kt")
            .writeText("package x\n@Composable fun S() {\n    Text(\"Shop closed today\") // i18n-ignore: technical label\n}\n")
        assertTrue(HardcodedStringScanner.scanTree(root).isEmpty())
    }
}

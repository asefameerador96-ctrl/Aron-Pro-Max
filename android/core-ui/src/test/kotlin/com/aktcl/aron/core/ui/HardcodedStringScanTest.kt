package com.aktcl.aron.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HardcodedStringScanTest {

    /** The gate itself: fails the build when any app or feature module (or core-ui) shows text that is not a resource. */
    @Test
    fun noHardcodedUserVisibleStringsInTheAppAndFeatureModules() {
        val root = File(System.getProperty("aron.androidRoot") ?: error("aron.androidRoot not set"))
        val violations = HardcodedStringScanner.scanTree(root) { it.startsWith("app-") || it.startsWith("feature-") || it == "core-ui" }
        assertTrue(
            "User-visible text must live in res/values/strings.xml and res/values-bn/strings.xml (docs/24 s5.6):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun detectsTheUsualMistakes() {
        val src = """
            package x
            @Composable fun S() {
                Text("Log in")
                Text(text = "Username")
                OutlinedTextField(value = v, onValueChange = {}, label = { Text("Password") })
                Text(
                    "Multi line"
                )
                Icon(Icons.Default.Close, contentDescription = "Close")
                Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                val bn = "লগইন"
                snackbar.showSnackbar("Synced")
                AlertDialog(title = "Delete?")
            }
        """.trimIndent()
        val v = HardcodedStringScanner.scanKotlin("S.kt", src)
        assertEquals(v.joinToString("\n"), listOf(3, 4, 5, 6, 9, 10, 11, 12, 13), v.map { it.line }.sorted())
        assertTrue(v.any { it.rule == "bangla-literal" && it.line == 11 })
        assertEquals(1, HardcodedStringScanner.scanKotlin("T.kt", "val t = Text(\"Total: ${'$'}count\")").size)
    }

    @Test
    fun allowsResourcesTagsLogsCommentsAndOptOuts() {
        val src = """
            package x
            // Text("a comment is fine")
            /* label = "also fine" */
            @Composable fun S() {
                Text(stringResource(R.string.auth_login_title))
                Text(text = localizedDigits(stringResource(R.string.auth_version, versionName)))
                Modifier.testTag("login_username")
                Log.d("Aron", "debug text")
                val versionName = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
                Text("ARON") // i18n-ignore: brand name
                val url = "https://api.aron-dev.invalid"
                Text("${'$'}count")
                Text("${'$'}{user.name}")
            }
        """.trimIndent()
        val v = HardcodedStringScanner.scanKotlin("S.kt", src)
        assertTrue(v.joinToString("\n"), v.isEmpty())
    }

    @Test
    fun xmlLiteralsAndMissingTranslationsAreCaught() {
        val xml = """<TextView android:text="Hello" /><TextView android:text="@string/ok" /><application android:label="${'$'}{appLabel}" />"""
        assertEquals(1, HardcodedStringScanner.scanXml("l.xml", xml).size)
        val en = """<resources><string name="a">A</string><string name="b" translatable="false">B</string><plurals name="c"></plurals></resources>"""
        assertEquals(setOf("a", "c"), HardcodedStringScanner.translatableNames(en))
    }
}

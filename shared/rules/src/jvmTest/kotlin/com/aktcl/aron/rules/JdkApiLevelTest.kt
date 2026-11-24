package com.aktcl.aron.rules

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B2 of the lead review (D-TKPI-10): `shared:rules` is linked into the Android apps (minSdk 26, docs/23 s10) but compiled for the JVM with `-Xjdk-release=17`, so
 * nothing in the build stops it from calling a JDK member Android has only later (BigInteger.TWO is Java 9 and reached Android at API 33; BigInteger.longValueExact
 * only at API 31): the first call on an older field phone throws NoSuchFieldError or NoSuchMethodError, and lane CI stays green because the server JVM has them.
 * This is the animal-sniffer-style guard that fits a source tree this small: in `commonMain` (a) the only `java.*` classes that may be imported are the allow-listed
 * ones below (all present since API 1 or 26), and (b) a deny-list of members that are newer than API 26 must not appear. Add a class to the allow-list only after
 * checking its Android "since API level" (developer.android.com/reference).
 */
class JdkApiLevelTest {
    private val root = File(System.getProperty("user.dir"), "src/commonMain")
    private val allowedImports = setOf("java.math.BigInteger", "java.math.BigDecimal", "java.math.RoundingMode", "java.math.MathContext")

    /** member pattern -> why it is refused (Android level it arrived). */
    private val denied = mapOf(
        Regex("""\bBigInteger\.(TWO)\b""") to "BigInteger.TWO: Java 9, Android API 33 (use BigInteger.valueOf(2))",
        Regex("""\.(long|int|short|byte)ValueExact\s*\(""") to "BigInteger.xValueExact: Android API 31 (check bitLength and throw yourself)",
        Regex("""\.sqrt\s*\(\s*\)""") to "BigInteger.sqrt: Java 9, Android API 33",
        Regex("""\b(List|Set|Map)\.(of|copyOf)\s*\(""") to "List/Set/Map.of: Java 9, Android API 30 (use listOf/setOf/mapOf)",
        Regex("""\bObjects\.requireNonNullElse""") to "Objects.requireNonNullElse: Java 9, Android API 33",
        Regex("""\bMath\.(clamp|ceilDiv|ceilMod|unsignedMultiplyHigh)\s*\(""") to "Math.clamp/ceilDiv/ceilMod: Java 18 to 21, not on Android 8 to 12",
    )

    private fun sources() = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun theSourceTreeIsFound() {
        assertEquals(true, sources().isNotEmpty(), "no commonMain sources under ${root.absolutePath}")
    }

    @Test
    fun onlyAllowListedJavaClassesAreImportedInCommonMain() {
        val bad = sources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> Regex("""^\s*import\s+(java\.[\w.]+)""").find(l)?.groupValues?.get(1)?.takeIf { it !in allowedImports }?.let { "${f.name}:${i + 1} imports $it" } }
        }
        assertEquals(emptyList(), bad, "a java.* class outside the allow-list (check its Android API level first)")
    }

    @Test
    fun noJdkMemberNewerThanAndroidApi26IsCalledInCommonMain() {
        val bad = sources().flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l ->
                val t = l.trimStart()
                val code = if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) "" else l.substringBefore("//")
                denied.entries.firstOrNull { (re, _) -> re.containsMatchIn(code) }?.let { "${f.name}:${i + 1} ${it.value}" }
            }
        }
        assertEquals(emptyList(), bad)
    }

    @Test
    fun theGuardCatchesTheKnownBadMember() {
        // the guard itself: the deny-list matches the exact token that crashed the lead's review scenario
        val line = "val two = BigInteger.TWO"
        assertEquals(true, denied.keys.any { it.containsMatchIn(line) })
        assertEquals(true, denied.keys.any { it.containsMatchIn("x.longValueExact()") })
    }
}

package com.aktcl.aron.core.ui

import java.io.File

/**
 * F-SYS-018 gate: no user-visible text in code (docs/24 s5.6, docs/26 s5). Android lint's HardcodedText only sees XML,
 * so this scans the Kotlin sources of the app and feature modules (and core-ui) for:
 *  1. any string literal with Bengali script (Bangla belongs in values-bn/strings.xml);
 *  2. a literal with a letter handed to a UI sink: `Text("...")`, `text = "..."`, `label =`, `title =`, `placeholder =`,
 *     `contentDescription =`, `message =`, `Toast.makeText(.., "..")`, `setTitle("..")`, `setText("..")`,
 *     `showSnackbar("..")`;
 *  3. XML layouts and manifests with a literal `android:text`, `android:hint`, `android:label` or `android:title`;
 *  4. a string in `values/strings.xml` without its Bangla twin in `values-bn/strings.xml` (unless translatable="false").
 * A line may opt out with `// i18n-ignore: <reason>` (for example a test tag or a log message).
 */
object HardcodedStringScanner {
    data class Violation(val file: String, val line: Int, val rule: String, val text: String) {
        override fun toString(): String = "$file:$line [$rule] $text"
    }

    private val bengali = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*[\\u0980-\\u09FF](?:[^\"\\\\\\n]|\\\\.)*\"")
    private const val LIT = "\"(?:[^\"\\\\\\n]|\\\\.)*\""
    private val sinks = listOf(
        Regex("\\bText\\(\\s*$LIT"),
        Regex("\\bText\\(\\s*text\\s*=\\s*$LIT"),
        Regex("\\b(text|label|title|placeholder|contentDescription|message|subtitle|confirmText|dismissText|supportingText)\\s*=\\s*$LIT"),
        Regex("Toast\\.makeText\\([^,]+,\\s*$LIT"),
        Regex("\\b(setTitle|setText|setMessage|showSnackbar|setContentTitle|setContentText)\\(\\s*$LIT"),
    )
    private val xmlLiteral = Regex("android:(text|hint|label|title|contentDescription)=\"(?!@)(?!\\$\\{)[^\"]*[A-Za-z\\u0980-\\u09FF][^\"]*\"")
    private val stringName = Regex("<(string|plurals|string-array)\\s+name=\"([^\"]+)\"([^>]*)>")

    fun scanKotlin(path: String, source: String): List<Violation> {
        val out = mutableListOf<Violation>()
        val lines = source.lines()
        val ignored = lines.withIndex().filter { it.value.contains("i18n-ignore") }.map { it.index + 1 }.toSet()
        val stripped = stripComments(source)
        fun lineOf(offset: Int) = stripped.substring(0, offset).count { it == '\n' } + 1
        bengali.findAll(stripped).forEach { m ->
            val ln = lineOf(m.range.first)
            if (ln !in ignored) out += Violation(path, ln, "bangla-literal", m.value)
        }
        sinks.forEach { r ->
            r.findAll(stripped).filter { hasVisibleText(it.value.substring(it.value.indexOf('"'))) }.forEach { m ->
                val ln = lineOf(m.range.first)
                if (ln !in ignored && out.none { it.line == ln && it.rule == "ui-literal" }) out += Violation(path, ln, "ui-literal", m.value.take(120))
            }
        }
        return out
    }

    /** True when the literal has a letter outside string-template expressions (`"${'$'}count"` alone is not text). */
    private fun hasVisibleText(literal: String): Boolean =
        literal.replace(Regex("\\$\\{[^}]*}"), "").replace(Regex("\\$[A-Za-z_][A-Za-z0-9_]*"), "")
            .any { it.isLetter() }

    fun scanXml(path: String, source: String): List<Violation> =
        source.lines().withIndex().flatMap { (i, line) ->
            xmlLiteral.findAll(line).map { Violation(path, i + 1, "xml-literal", it.value) }.toList()
        }

    /** Names defined in a strings.xml that are translatable. */
    fun translatableNames(source: String): Set<String> =
        stringName.findAll(source).filter { !it.groupValues[3].contains("translatable=\"false\"") }.map { it.groupValues[2] }.toSet()

    fun allNames(source: String): Set<String> = stringName.findAll(source).map { it.groupValues[2] }.toSet()

    /** Scans every module of [androidRoot] whose name matches [modules]. */
    fun scanTree(androidRoot: File, modules: (String) -> Boolean): List<Violation> {
        val out = mutableListOf<Violation>()
        androidRoot.listFiles().orEmpty().filter { it.isDirectory && modules(it.name) }.sortedBy { it.name }.forEach { module ->
            val main = File(module, "src/main")
            main.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(androidRoot).path
                when {
                    f.extension == "kt" -> out += scanKotlin(rel, f.readText())
                    f.extension == "xml" && (f.name == "AndroidManifest.xml" || f.parentFile.name.startsWith("layout") || f.parentFile.name.startsWith("menu")) ->
                        out += scanXml(rel, f.readText())
                }
            }
            val en = File(main, "res/values/strings.xml")
            val bn = File(main, "res/values-bn/strings.xml")
            if (en.isFile) {
                val missing = translatableNames(en.readText()) - (if (bn.isFile) allNames(bn.readText()) else emptySet())
                missing.forEach { out += Violation(en.relativeTo(androidRoot).path, 0, "missing-bangla", it) }
            }
        }
        return out
    }

    /** Removes // and /* */ comments but keeps string literals and line breaks, so offsets map to the same lines. */
    internal fun stripComments(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        var inString = false
        var raw = false
        while (i < s.length) {
            val c = s[i]
            if (!inString && s.startsWith("\"\"\"", i)) { raw = !raw; b.append("\"\"\""); i += 3; continue }
            if (raw) { b.append(c); i++; continue }
            if (inString) {
                b.append(c)
                if (c == '\\' && i + 1 < s.length) { b.append(s[i + 1]); i += 2; continue }
                if (c == '"' || c == '\n') inString = false
                i++
                continue
            }
            when {
                c == '"' -> { inString = true; b.append(c); i++ }
                s.startsWith("//", i) -> { while (i < s.length && s[i] != '\n') i++ }
                s.startsWith("/*", i) -> {
                    val end = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    s.substring(i, end).forEach { ch -> if (ch == '\n') b.append('\n') }
                    i = end
                }
                else -> { b.append(c); i++ }
            }
        }
        return b.toString()
    }
}

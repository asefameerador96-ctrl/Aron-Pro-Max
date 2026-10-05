package com.aktcl.aron.core.ui

import java.io.File

/**
 * F-SYS-018 gate: no user-visible text in code (docs/24 s5.6, docs/26 s5). Android lint's HardcodedText only sees XML, so
 * this scans every non-test source set of the app, feature, core-ui and dpc modules and fails the build on:
 *  1. any string literal that reads like text for a person: Bengali script, two words, a capitalised word, or a word
 *     after a space or bracket (`"Close"`, `"Shop closed"`, `" sticks"`, `"(offline)"`), in any position: arguments,
 *     constants, lists, state copies, raw strings, Java files;
 *  2. XML layouts, menus and manifests with a literal `android:text`, `hint`, `label`, `title` or `contentDescription`;
 *  3. a string, plurals or string-array in any `res/values/` file without its Bangla twin in `res/values-bn/`
 *     (unless `translatable="false"`).
 * Identifiers pass: keys, tags, codes, URLs, formats (`"aron_ui"`, `"home-"`, `"HH:mm"`, `"%02x"`). Developer-only text
 * passes on lines with `Log.x(`, `require`/`check`/`error`/`throw`, `testTag(`, `@SerialName(`, `@SuppressLint(`,
 * `Regex(` and time-zone ids. Anything else needs the explicit marker `// i18n-ignore: <reason>` on its line.
 */
object HardcodedStringScanner {
    data class Violation(val file: String, val line: Int, val rule: String, val text: String) {
        override fun toString(): String = "$file:$line [$rule] $text"
    }

    private val literal = Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\\\n]|\\\\.)*\"")
    private val template = Regex("\\$\\{[^}]*}|\\$[A-Za-z_][A-Za-z0-9_]*")
    private val prose = Regex("[A-Za-z]{2,}[,.:;?!]?\\s+[A-Za-z]|^\\s*[A-Z][a-z]+\\b(?!\\.)|[\\s(][a-z]{3,}|[\\u0980-\\u09FF]")
    private val optOut = Regex("//\\s*i18n-ignore:\\s*\\S")
    private val devLine = Regex(
        "@SuppressLint\\(|\\bLog\\.[dviwe]\\(|testTag\\(|@SerialName\\(|\\bRegex\\(|TimeZone\\.getTimeZone\\(|ZoneId\\.of\\(|" +
            "\\b(require|check|requireNotNull|checkNotNull|error|assert|TODO)\\s*\\(|\\bthrow\\s",
    )
    private val xmlLiteral = Regex("android:(text|hint|label|title|contentDescription)=\"(?!@)(?!\\$\\{)[^\"]*[A-Za-z\\u0980-\\u09FF][^\"]*\"")
    private val resourceTag = Regex("<(string|plurals|string-array)\\b([^>]*)>")
    private val nameAttr = Regex("\\bname=\"([^\"]+)\"")

    fun scanKotlin(path: String, source: String): List<Violation> {
        val lines = source.lines()
        val stripped = stripComments(source)
        val strippedLines = stripped.lines()
        val out = mutableListOf<Violation>()
        literal.findAll(stripped).forEach { m ->
            val ln = stripped.substring(0, m.range.first).count { it == '\n' } + 1
            val raw = m.value.startsWith("\"\"\"")
            val body = (if (raw) m.value.substring(3, m.value.length - 3) else m.value.substring(1, m.value.length - 1)).replace(template, " ")
            if (!prose.containsMatchIn(body)) return@forEach
            val original = lines.getOrElse(ln - 1) { "" }
            if (optOut.containsMatchIn(original)) return@forEach
            // Developer-only text: the statement on this line or the line before (a require { } block, a wrapped Log call).
            val context = strippedLines.getOrElse(ln - 2) { "" } + "\n" + strippedLines.getOrElse(ln - 1) { "" }
            if (devLine.containsMatchIn(context)) return@forEach
            val rule = if (body.any { it in '\u0980'..'\u09FF' }) "bangla-literal" else "ui-literal"
            out += Violation(path, ln, rule, m.value.take(120).replace("\n", "\\n"))
        }
        return out
    }

    fun scanXml(path: String, source: String): List<Violation> =
        source.lines().withIndex().flatMap { (i, line) ->
            xmlLiteral.findAll(line).map { Violation(path, i + 1, "xml-literal", it.value) }.toList()
        }

    /** Names defined in a values file that are translatable. */
    fun translatableNames(source: String): Set<String> = resourceTag.findAll(source)
        .filter { !it.groupValues[2].contains("translatable=\"false\"") }
        .mapNotNull { nameAttr.find(it.groupValues[2])?.groupValues?.get(1) }.toSet()

    fun allNames(source: String): Set<String> =
        resourceTag.findAll(source).mapNotNull { nameAttr.find(it.groupValues[2])?.groupValues?.get(1) }.toSet()

    /** The source sets that ship (main, debug, release, flavours); tests may hold any literal they like. */
    private fun shippedSourceSets(module: File): List<File> =
        File(module, "src").listFiles().orEmpty().filter { it.isDirectory && it.name !in setOf("test", "androidTest", "testFixtures") }

    /** Scans every module of [androidRoot] whose name matches [modules]. */
    fun scanTree(androidRoot: File, modules: (String) -> Boolean): List<Violation> {
        val out = mutableListOf<Violation>()
        androidRoot.listFiles().orEmpty().filter { it.isDirectory && modules(it.name) }.sortedBy { it.name }.forEach { module ->
            for (set in shippedSourceSets(module)) {
                set.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(androidRoot).path
                    when {
                        f.extension == "kt" || f.extension == "java" -> out += scanKotlin(rel, f.readText())
                        f.extension == "xml" && (f.name == "AndroidManifest.xml" || f.parentFile.name.startsWith("layout") || f.parentFile.name.startsWith("menu")) ->
                            out += scanXml(rel, f.readText())
                    }
                }
                val en = File(set, "res/values").listFiles { f -> f.extension == "xml" }.orEmpty()
                val bnNames = File(set, "res/values-bn").listFiles { f -> f.extension == "xml" }.orEmpty().flatMap { allNames(it.readText()) }.toSet()
                en.forEach { file ->
                    (translatableNames(file.readText()) - bnNames).forEach { out += Violation(file.relativeTo(androidRoot).path, 0, "missing-bangla", it) }
                }
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

package com.aktcl.aron.core.ui

import java.io.File

/**
 * F-SYS-018 gate: no user-visible text in code (docs/24 s5.6, docs/26 s5). Android lint's HardcodedText sees only XML, so
 * this tokenises every shipped source set (not test/androidTest) of app-*, feature-*, core-ui, core-printing, core-sync
 * and dpc, and judges each string literal by the call it sits in:
 *  - inside a UI sink (`Text`, `BasicText`, `AnnotatedString`, `append`, `Icon`/`Image` descriptions, toasts, snackbars,
 *    notification titles, or a `text =`/`label =`/`title =`/`contentDescription =`... argument), any letter fails;
 *  - inside a developer call (`Log.*`, `Timber.*`, `require`/`check`/`error` and their lambdas, `*Exception(`, `testTag`,
 *    `Regex`, date-pattern and time-zone factories, HTTP headers, `.toMediaType()`, any annotation), it passes;
 *  - anywhere else it fails when it reads like text for a person: Bengali script (escapes decoded), two words, a symbol
 *    then a word, a word after a space or bracket, or a lone capitalised word (unless assigned to a TAG/KEY/ID-like name);
 *    SQL, MIME types, time-zone ids and header names pass.
 * XML layouts, menus, `res/xml` and manifests fail on literal text attributes; every translatable string, plurals or
 * string-array of `res/values/` needs its twin in `res/values-bn/`. A line opts out only with `// i18n-ignore: <reason>`.
 */
object HardcodedStringScanner {
    data class Violation(val file: String, val line: Int, val rule: String, val text: String) {
        override fun toString(): String = "$file:$line [$rule] $text"
    }

    /** One string literal: its raw source text, its body with escapes decoded and templates blanked, and where it is. */
    internal class Literal(val start: Int, val end: Int, val line: Int, val source: String, val body: String)

    private val uiSinks = setOf(
        "Text", "BasicText", "AnnotatedString", "append", "Icon", "Image", "showSnackbar", "makeText", "setTitle", "setText",
        "setMessage", "setContentTitle", "setContentText", "setTicker", "setSubText",
    )
    private val uiArgs = setOf(
        "text", "label", "title", "subtitle", "placeholder", "contentDescription", "message", "confirmText", "dismissText",
        "supportingText", "tooltip", "headline", "description",
    )
    private val devCalls = setOf(
        "require", "check", "requireNotNull", "checkNotNull", "error", "assert", "TODO", "println", "testTag", "Regex",
        "ofPattern", "SimpleDateFormat", "DateTimeFormatter", "getTimeZone", "forLanguageTag", "header", "addHeader",
        "removeHeader", "getColumnIndex", "getColumnIndexOrThrow", "getSharedPreferences", "getString", "putString",
        "getBoolean", "putBoolean", "getLong", "putLong", "getInt", "putInt", "remove", "contains", "loadLibrary",
    )
    private val logReceivers = setOf("Log", "Timber")
    private val receiverDev = Regex("^\\s*\\.\\s*(toMediaType|toMediaTypeOrNull|toRegex|toUri|toHttpUrl|toHttpUrlOrNull|toPattern)\\b")
    private val identifierName = Regex("(?i).*(TAG|KEY|ID|NAME|ACTION|ROUTE|TYPE|MIME|PREF|CHANNEL|AUTHORITY|SCHEME|EXTRA|PATH|URL|HEADER)$")
    private val argBefore = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*$")
    private val valBefore = Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*:\\s*[A-Za-z0-9_.<>?]+)?\\s*=\\s*$")

    private val twoWords = Regex("\\p{L}{2,}[,.:;?!]?\\s+\\p{L}")
    private val symbolThenWord = Regex("^\\s*[•·✓✔✗✘★☆→←▶►–—]+\\s*\\p{L}")
    private val wordAfterSpace = Regex("[\\s(][a-z]{3,}")
    private val loneCapitalised = Regex("^\\s*[A-Z][a-z]+[.!?]?\\s*$")
    private val sql = Regex("^\\s*(SELECT|INSERT|UPDATE|DELETE|WITH|CREATE|PRAGMA|DROP|ALTER|REPLACE)\\b")
    private val mime = Regex("^[a-z]+/[a-z0-9.+-]+(;\\s*[a-z-]+=[^;]+)*$")
    private val timeZoneId = Regex("^[A-Z][a-z]+/[A-Za-z_]+$")
    private val headerName = Regex("^[A-Z][a-z]+(-[A-Z][a-z]+)+$")
    private val optOut = Regex("//\\s*i18n-ignore:\\s*\\S")

    private val xmlLiteral = Regex(
        "(android|app):(text|hint|label|title|subtitle|contentDescription|description|summary|tooltipText|prompt|" +
            "shortcutShortLabel|shortcutLongLabel)=\"(?!@)(?!\\$\\{)[^\"]*[A-Za-z\\u0980-\\u09FF][^\"]*\"",
    )
    private val resourceTag = Regex("<(string|plurals|string-array)\\b([^>]*)>")
    private val nameAttr = Regex("\\bname=\"([^\"]+)\"")

    fun scanKotlin(path: String, source: String): List<Violation> {
        val (literals, mask) = tokenize(source)
        val lines = source.lines()
        val out = mutableListOf<Violation>()
        for (lit in literals) {
            if (optOut.containsMatchIn(lines.getOrElse(lit.line - 1) { "" })) continue
            val verdict = judge(lit, mask) ?: continue
            out += Violation(path, lit.line, verdict, lit.source.take(120).replace("\n", "\\n"))
        }
        return out
    }

    /** Returns the rule broken, or null when the literal is fine. */
    private fun judge(lit: Literal, mask: String): String? {
        val body = lit.body
        if (body.none { it.isLetter() }) return null
        if (receiverDev.containsMatchIn(mask.substring(lit.end))) return null
        val before = mask.substring(0, lit.start)
        val call = enclosingCall(mask, lit.start)
        val arg = argBefore.find(before.takeLast(80))?.groupValues?.get(1)
        val bengali = body.any { it in '\u0980'..'\u09FF' }
        if (call != null && isDevCall(call)) return null
        if ((call != null && call.substringAfterLast('.') in uiSinks) || arg in uiArgs) {
            return if (bengali) "bangla-literal" else "ui-literal"
        }
        if (bengali) return "bangla-literal"
        if (sql.containsMatchIn(body) || mime.matches(body) || timeZoneId.matches(body) || headerName.matches(body)) return null
        if (twoWords.containsMatchIn(body) || symbolThenWord.containsMatchIn(body) || wordAfterSpace.containsMatchIn(body)) return "ui-literal"
        if (loneCapitalised.matches(body)) {
            val name = valBefore.find(before.takeLast(120))?.groupValues?.get(1) ?: arg
            return if (name != null && identifierName.matches(name)) null else "ui-literal"
        }
        return null
    }

    private fun isDevCall(qualified: String): Boolean {
        if (qualified.startsWith("@")) return true // annotations: @Query, @Preview, @Named, @Deprecated, @SerialName, ...
        val last = qualified.substringAfterLast('.')
        val receiver = qualified.substringBeforeLast('.', "")
        if (receiver.substringAfterLast('.') in logReceivers) return true
        if (last.endsWith("Exception") || last.endsWith("Error")) return true
        if (last == "of" && (receiver.endsWith("ZoneId") || receiver.endsWith("ZoneOffset"))) return true
        return last in devCalls
    }

    /**
     * The qualified name of the call (or annotation, or lambda owner) whose bracket encloses [offset]: walks back over
     * balanced brackets in the comment- and string-free [mask]. For a `{` it is the call the lambda belongs to
     * (`require(x) { "..." }` gives `require`). Null at top level or inside a plain block.
     */
    internal fun enclosingCall(mask: String, offset: Int): String? {
        var depth = 0
        var i = offset - 1
        while (i >= 0) {
            when (mask[i]) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> if (depth > 0) depth-- else return nameBefore(mask, i)
            }
            i--
        }
        return null
    }

    private fun nameBefore(mask: String, opener: Int): String? {
        if (mask[opener] == '[') return "["
        var j = opener - 1
        while (j >= 0 && mask[j].isWhitespace()) j--
        if (j < 0) return null
        if (mask[opener] == '{' && mask[j] == ')') {
            // a trailing lambda: find the matching '(' of the call it follows
            var depth = 0
            while (j >= 0) {
                if (mask[j] == ')') depth++ else if (mask[j] == '(') { depth--; if (depth == 0) break }
                j--
            }
            return if (j > 0) nameBefore(mask, j) else null
        }
        val end = j + 1
        while (j >= 0 && (mask[j].isLetterOrDigit() || mask[j] == '_' || mask[j] == '.' || mask[j] == '@')) j--
        val name = mask.substring(j + 1, end).trim('.')
        return name.ifEmpty { null }
    }

    /**
     * Splits Kotlin (or Java) source into string literals and a mask of the code with comments and literal contents
     * blanked (quotes, brackets and line breaks kept), so brackets can be matched without being fooled by text.
     */
    internal fun tokenize(s: String): Pair<List<Literal>, String> {
        val mask = StringBuilder(s.length)
        val literals = mutableListOf<Literal>()
        var i = 0
        var line = 1
        fun blank(c: Char) = if (c == '\n') '\n' else ' '
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') { mask.append(' '); i++ }
                s.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < s.length) {
                        if (s.startsWith("/*", i)) { depth++; mask.append("  "); i += 2; continue }
                        if (s.startsWith("*/", i)) { depth--; mask.append("  "); i += 2; if (depth == 0) break; continue }
                        if (s[i] == '\n') line++
                        mask.append(blank(s[i])); i++
                    }
                }
                c == '\'' -> {
                    // char literal: 'x', '\'', '\u0041', '"'
                    val close = if (i + 1 < s.length && s[i + 1] == '\\') s.indexOf('\'', i + 3) else s.indexOf('\'', i + 2)
                    val end = if (close < 0) i + 1 else close + 1
                    mask.append('\''); repeat(end - i - 2) { mask.append(' ') }; if (end - i >= 2) mask.append('\'')
                    i = end
                }
                s.startsWith("\"\"\"", i) -> {
                    val close = s.indexOf("\"\"\"", i + 3).let { if (it < 0) s.length - 3 else it }
                    val text = s.substring(i + 3, close)
                    literals += Literal(i, close + 3, line, s.substring(i, close + 3), normalize(text, raw = true))
                    mask.append("\"\"\""); text.forEach { mask.append(blank(it)) }; mask.append("\"\"\"")
                    line += text.count { it == '\n' }
                    i = close + 3
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < s.length && s[j] != '"' && s[j] != '\n') { if (s[j] == '\\') j++; j++ }
                    val end = minOf(j, s.length - 1)
                    val text = s.substring(i + 1, end)
                    literals += Literal(i, end + 1, line, s.substring(i, end + 1), normalize(text, raw = false))
                    mask.append('"'); text.forEach { mask.append(blank(it)) }; mask.append('"')
                    i = end + 1
                }
                else -> { if (c == '\n') line++; mask.append(c); i++ }
            }
        }
        return literals to mask.toString()
    }

    /** Decodes escapes (\uXXXX, \n, \t, \", \\, \$) and blanks string templates, so only literal text remains. */
    private fun normalize(text: String, raw: Boolean): String {
        val withoutTemplates = text.replace(Regex("\\$\\{[^}]*}|\\$[A-Za-z_][A-Za-z0-9_]*"), " ")
        if (raw) return withoutTemplates
        val b = StringBuilder()
        var i = 0
        while (i < withoutTemplates.length) {
            val c = withoutTemplates[i]
            if (c == '\\' && i + 1 < withoutTemplates.length) {
                val n = withoutTemplates[i + 1]
                when (n) {
                    'u' -> {
                        val hex = withoutTemplates.substring(i + 2, minOf(i + 6, withoutTemplates.length))
                        hex.toIntOrNull(16)?.let { b.append(it.toChar()) }
                        i += 6; continue
                    }
                    'n', 't', 'r', 'b' -> b.append(' ')
                    else -> b.append(n)
                }
                i += 2
                continue
            }
            b.append(c); i++
        }
        return b.toString()
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

    /**
     * The source sets that ship (main, debug, release, flavours). Anything that is a test source set never ships and may
     * hold any literal: test, testDebug, testContract, testFixtures, androidTest, sharedTest, ...
     */
    fun isTestSourceSet(name: String): Boolean =
        name.startsWith("test") || name.startsWith("androidTest") || name.startsWith("sharedTest") || name.endsWith("Test") || name.endsWith("Fixtures")

    private fun shippedSourceSets(module: File): List<File> =
        File(module, "src").listFiles().orEmpty().filter { it.isDirectory && !isTestSourceSet(it.name) }

    /** The modules whose code can show text to a field user. */
    fun isScannedModule(name: String): Boolean =
        name.startsWith("app-") || name.startsWith("feature-") || name in setOf("core-ui", "core-printing", "core-sync", "dpc")

    /** Scans every module of [androidRoot] whose name matches [modules]. */
    fun scanTree(androidRoot: File, modules: (String) -> Boolean = ::isScannedModule): List<Violation> {
        val out = mutableListOf<Violation>()
        androidRoot.listFiles().orEmpty().filter { it.isDirectory && modules(it.name) }.sortedBy { it.name }.forEach { module ->
            for (set in shippedSourceSets(module)) {
                set.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(androidRoot).path
                    val dir = f.parentFile.name
                    when {
                        f.extension == "kt" || f.extension == "java" -> out += scanKotlin(rel, f.readText())
                        f.extension == "xml" && (f.name == "AndroidManifest.xml" || dir.startsWith("layout") || dir.startsWith("menu") || dir.startsWith("xml")) ->
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
}

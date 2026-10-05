package com.aktcl.aron.core.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** Second independent re-check of F-SYS-018: every miss must be caught and realistic Day-2 non-UI code must pass. */
class RecheckScannerTest {
    private fun scan(src: String) = HardcodedStringScanner.scanKotlin("R.kt", "package x\n@Composable fun S() {\n$src\n}")
    private fun assertCaught(src: String) = assertTrue("not caught:\n$src", scan(src).isNotEmpty())
    private fun assertClean(src: String) = scan(src).let { assertTrue("false positive:\n" + it.joinToString("\n"), it.isEmpty()) }

    // ---- misses ----
    @Test fun miss_testTagOnPreviousLine() = assertCaught("Button(onClick = onRetry, modifier = Modifier.testTag(\"retry\")) {\n    Text(\"Try again\")\n}")
    @Test fun miss_testTagSameLine() = assertCaught("Text(\"Shop closed\", modifier = Modifier.testTag(\"closed\"))")
    @Test fun miss_requireNotNullOnPreviousLine() = assertCaught("val outlet = requireNotNull(state.outlet)\nText(\"Visit started\")")
    @Test fun miss_checkOnPreviousLine() = assertCaught("check(rows.isNotEmpty())\nval title = \"No outlets today\"")
    @Test fun miss_bulletPrefix() = assertCaught("Text(\"• Pending\")")
    @Test fun miss_checkmarkPrefix() = assertCaught("Text(\"✓ Synced\")")
    @Test fun miss_allCapsButton() = assertCaught("TextButton(onClick = {}) { Text(\"OK\") }")
    @Test fun miss_banglaAsUnicodeEscapes() = assertCaught("Text(\"\\u09B8\\u0982\\u09AF\\u09CB\\u0997 \\u09A8\\u09C7\\u0987\")")
    @Test fun miss_escapedNewlineLowercase() = assertCaught("Text(\"outlet\\nclosed\")")
    @Test fun miss_abbreviationWithDot() = assertCaught("Text(\"Tk.\")")
    @Test fun miss_charLiteralQuoteBeforeText() = assertCaught("if (c == '\"') Text(\"Shop closed\")")

    // ---- false positives on Day-2 non-UI code ----
    @Test fun fp_roomQuery() = assertClean("@Query(\"SELECT * FROM outbox WHERE state = :state ORDER BY created_at\")")
    @Test fun fp_logTagConstant() = assertClean("private const val TAG = \"Sync\"")
    @Test fun fp_timber() = assertClean("Timber.d(\"sync started for %s\", id)")
    @Test fun fp_mimeWithCharset() = assertClean("val type = \"application/json; charset=utf-8\".toMediaType()")
    @Test fun fp_dateFormatWithSpace() = assertClean("val fmt = DateTimeFormatter.ofPattern(\"d MMM yyyy\", locale)")
    @Test fun fp_dateFormatWeekday() = assertClean("val fmt = SimpleDateFormat(\"EEEE, d MMMM\", locale)")
    @Test fun fp_zoneConstant() = assertClean("val DHAKA = \"Asia/Dhaka\"")
    @Test fun fp_exceptionWithoutThrow() = assertClean("return Result.failure(IllegalStateException(\"outbox row not found\"))")
    @Test fun fp_requireBlockWrapped() = assertClean("require(qty > 0) {\n    val s = 1\n    \"quantity must be positive\"\n}")
    @Test fun fp_preview() = assertClean("@Preview(name = \"Dark mode\", locale = \"bn\")")
    @Test fun fp_previewSingleWord() = assertClean("@Preview(name = \"Bangla\")")
    @Test fun fp_deprecated() = assertClean("@Deprecated(\"use visitFlow instead\")")
    @Test fun fp_headerName() = assertClean("request.header(\"Accept-Language\", tag)")
    @Test fun fp_named() = assertClean("@Named(\"Upload\") val client: OkHttpClient")

    // ---- expected to pass already (reported as OK) ----
    @Test fun ok_workName() = assertClean("WorkManager.getInstance(ctx).enqueueUniqueWork(\"aron-sync\", ExistingWorkPolicy.KEEP, req)")
    @Test fun ok_permission() = assertClean("val p = \"android.permission.ACCESS_FINE_LOCATION\"")
    @Test fun ok_intentAction() = assertClean("val a = \"com.aktcl.aron.action.SYNC_NOW\"")
    @Test fun ok_route() = assertClean("composable(\"home/{userId}\") { }")
    @Test fun ok_jsonKey() = assertClean("obj[\"route_id\"]?.jsonPrimitive")
    @Test fun ok_mime() = assertClean("val m = \"image/jpeg\"")
    @Test fun ok_isoPattern() = assertClean("val f = SimpleDateFormat(\"yyyy-MM-dd'T'HH:mm:ss\", Locale.US)")
    @Test fun ok_requireInline() = assertClean("require(x > 0) { \"quantity must be positive\" }")
    @Test fun ok_checkWrapped() = assertClean("check(ok) {\n    \"bad state of the outbox\"\n}")
}

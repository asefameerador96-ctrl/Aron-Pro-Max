package com.aktcl.aron.feature.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.dao.ReferenceDao
import com.aktcl.aron.core.database.entity.SyncMetaEntity
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors

/** What the lock-out screen says: which close keeps failing and the short stable code the rep can read out. No payload, no personal data. */
data class DayCloseFailure(val kind: String, val businessDate: String, val code: String)

/**
 * D-AGATE-22 (item 5, the lead's approved design): a LOCAL counter of failed attempts to close a forgotten day ([KIND_DAY]) or an earlier open call ([KIND_CALL]), one row per
 * (kind, date) in `sync_meta`, reset when the close succeeds. After [LIMIT] failures the app shows a plain full-screen "the day could not be closed on this phone" with the short
 * code and a Try again button (see [DayCloseFailedScreen]); the check-in is never silently off. The stored value is `count|code`; the code is the step plus the exception CLASS name
 * (never a message: it can carry SQL or a payload).
 */
class CloseFailures(private val dao: ReferenceDao) {
    /** One more failed attempt at closing [kind] of [date] with [t]; returns the new count. */
    suspend fun failed(kind: String, date: String, t: Throwable): Int {
        val key = key(kind, date)
        val n = (dao.meta(key)?.substringBefore('|')?.toIntOrNull() ?: 0) + 1
        dao.putMeta(SyncMetaEntity(key, "$n|${codeOf(kind, t)}"))
        return n
    }

    /** The close of [kind] of [date] succeeded (or is no longer needed): its counter goes. */
    suspend fun succeeded(kind: String, date: String) = dao.deleteMeta(key(kind, date))

    /**
     * The failure that has now reached [LIMIT], the oldest date first, or null. [stillOpen] says whether (kind, date) still needs closing: a counter whose day was closed by another
     * path (or whose call is gone) is deleted here and never shown.
     */
    suspend fun lockedOut(stillOpen: suspend (kind: String, date: String) -> Boolean): DayCloseFailure? {
        val rows = dao.metaWithPrefix(PREFIX).mapNotNull { r ->
            val parts = r.key.removePrefix(PREFIX).split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val n = r.value.substringBefore('|').toIntOrNull() ?: return@mapNotNull null
            Triple(DayCloseFailure(parts[0], parts[1], r.value.substringAfter('|', "")), n, r.key)
        }
        var out: DayCloseFailure? = null
        for ((f, n, key) in rows.sortedBy { it.first.businessDate }) {
            if (!stillOpen(f.kind, f.businessDate)) { dao.deleteMeta(key); continue }
            if (n >= LIMIT && out == null) out = f
        }
        return out
    }

    companion object {
        const val LIMIT = 3
        const val KIND_DAY = "day"
        const val KIND_CALL = "call"
        private const val PREFIX = "day_close_fail:"
        private fun key(kind: String, date: String) = "$PREFIX$kind:$date"

        /** `E-CLOSE-DAY/SQLiteDiskIOException`: the step and the exception class, nothing else. */
        fun codeOf(kind: String, t: Throwable): String = "E-CLOSE-" + kind.uppercase() + "/" + t.javaClass.name.substringAfterLast('.').take(48)
    }
}

/** Test tags of [DayCloseFailedScreen]. */
object DayCloseFailedTags {
    const val ROOT = "day_close_failed"
    const val CODE = "day_close_failed_code"
    const val RETRY = "day_close_failed_retry"
}

/**
 * D-AGATE-22 (item 5): shown instead of the app while a forgotten day or an earlier call keeps refusing to close ([CloseFailures.LIMIT] failures in a row). Plain words, the short
 * code, one button: [onTryAgain] runs the day's resume once. It is a dead end only for as long as the close fails; the next success removes it.
 */
@Composable
fun DayCloseFailedScreen(code: String, busy: Boolean, onTryAgain: () -> Unit, modifier: Modifier = Modifier) {
    AronScreen(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen).testTag(DayCloseFailedTags.ROOT),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AronEmptyState(stringResource(R.string.att_lockout_title), hint = stringResource(R.string.att_lockout_body), icon = AronIcons.Lock)
            Text(stringResource(R.string.att_lockout_code, code), Modifier.testTag(DayCloseFailedTags.CODE), color = LocalAronColors.current.textPrimary)
            AronPrimaryButton(stringResource(R.string.att_lockout_retry), onTryAgain, Modifier.fillMaxWidth().testTag(DayCloseFailedTags.RETRY), enabled = !busy)
        }
    }
}

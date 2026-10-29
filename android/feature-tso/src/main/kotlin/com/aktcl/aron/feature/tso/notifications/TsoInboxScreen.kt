package com.aktcl.aron.feature.tso.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.aron.NightButton
import com.aktcl.aron.core.ui.aron.NightButtonStyle
import com.aktcl.aron.core.ui.aron.NightRowCard
import com.aktcl.aron.core.ui.aron.nightRoles
import com.aktcl.aron.core.ui.aron.nightText
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.tso.CardLoad
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.TsoDashboards
import com.aktcl.aron.feature.tso.TsoFailure
import com.aktcl.aron.feature.tso.TsoLoading
import com.aktcl.aron.feature.tso.TsoNightNote
import com.aktcl.aron.feature.tso.uiLocale

/** Test tags of the inbox. */
object TsoInboxTags {
    const val LIST = "tso_inbox_list"
    const val AS_OF = "tso_inbox_as_of"
    const val OLDER = "tso_inbox_older"
    const val PENDING = "tso_inbox_pending"
    fun row(id: Long) = "tso_inbox_row_$id"
    fun dot(id: Long) = "tso_inbox_dot_$id"
}

/**
 * The bell's inbox (D-TSO-MAN-26; no screenshot, an improvement in the TSO look): newest first, a teal dot on the rows that were
 * unread when the list was read, the title and body in the app's language (English when the producer gave no Bangla), and the age
 * ("2h ago"). [load] is the first page (fresh or the kept copy, with "as of"), [older] the pages read with "Show older" ([onOlder],
 * null when there is none). Opening the screen marks everything up to the newest id read (the host); [pendingRead] says the read is
 * kept on the phone until the server confirms it. A row with a screen of its own opens it ([onOpen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TsoInboxContent(
    load: CardLoad<TsoInboxPage>?,
    older: List<TsoNotification>,
    nowMs: Long,
    refreshing: Boolean,
    pendingRead: Boolean,
    onRefresh: () -> Unit,
    onOpen: (TsoInboxTarget) -> Unit,
    onOlder: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val n = nightRoles()
    PullToRefreshBox(refreshing, onRefresh, modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag(TsoInboxTags.LIST),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            val v = load?.value
            when {
                load == null -> TsoLoading(Modifier.heightIn(min = 200.dp))
                v == null -> TsoNightNote(stringResource(if (load.failure == TsoFailure.REFUSED) R.string.tso_dash_card_refused else R.string.tso_dash_card_offline))
                else -> {
                    TsoDashboards.asOfClock(v.asOf)?.let { c ->
                        val clock = localizedDigits(c)
                        Text(
                            if (load.kept) stringResource(R.string.tso_dash_kept_copy, clock) else stringResource(R.string.tso_dash_as_of, clock),
                            Modifier.fillMaxWidth().testTag(TsoInboxTags.AS_OF), style = nightText(13.sp, if (load.kept) n.warning else n.inkMuted, align = TextAlign.End),
                        )
                    }
                    if (pendingRead) Text(stringResource(R.string.tso_chrome_inbox_pending), Modifier.fillMaxWidth().testTag(TsoInboxTags.PENDING), style = nightText(13.sp, n.inkMuted))
                    val rows = (v.items + older).distinctBy { it.id }
                    if (rows.isEmpty()) TsoNightNote(stringResource(R.string.tso_chrome_inbox_empty))
                    rows.forEach { item -> InboxRow(item, nowMs, onOpen) }
                    if (onOlder != null) NightButton(stringResource(R.string.tso_chrome_inbox_older), onOlder, Modifier.testTag(TsoInboxTags.OLDER), style = NightButtonStyle.Blue)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InboxRow(item: TsoNotification, nowMs: Long, onOpen: (TsoInboxTarget) -> Unit) {
    val n = nightRoles()
    val bangla = LocalAppLanguage.current == AppLanguage.BN
    val title = item.title(bangla)
    val body = item.body(bangla)
    val age = TsoInboxes.age(item, nowMs, uiLocale())
    val target = TsoInboxes.target(item)
    val newLabel = stringResource(R.string.tso_chrome_inbox_new)
    val described = stringResource(R.string.tso_chrome_inbox_row_desc, if (item.unread) "$newLabel: $title" else title, body, age)
    NightRowCard(
        Modifier.semantics(mergeDescendants = true) { contentDescription = described }.testTag(TsoInboxTags.row(item.id)),
        onClick = target?.let { t -> { onOpen(t) } },
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 7.dp).size(10.dp)) {
                if (item.unread) Box(Modifier.fillMaxSize().clip(CircleShape).background(n.teal).testTag(TsoInboxTags.dot(item.id)))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = nightText(17.sp, n.ink, bold = item.unread, semibold = !item.unread))
                if (body.isNotBlank()) Text(body, style = nightText(15.sp, n.inkMuted))
                if (age.isNotEmpty()) Text(age, style = nightText(13.sp, n.inkMuted))
            }
        }
    }
}

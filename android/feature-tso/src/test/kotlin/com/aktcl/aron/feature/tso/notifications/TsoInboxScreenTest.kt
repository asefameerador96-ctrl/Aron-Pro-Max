package com.aktcl.aron.feature.tso.notifications

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.feature.tso.CardLoad
import com.aktcl.aron.feature.tso.TsoFailure
import com.aktcl.aron.feature.tso.TsoLoginTheme
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.a11yProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The inbox screen (D-TSO-MAN-26): rows newest first, the dot on new rows, the app's language, a row opens its screen, offline states. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp")
class TsoInboxScreenTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val now = java.time.Instant.parse("2026-10-29T10:00:00Z").toEpochMilli()
    private fun n(id: Long, refType: String, refId: String, read: Boolean, bn: String? = null) =
        TsoNotification(id, "ticket_updated", refType, refId, "Title $id", bn, "Body $id", null, "2026-10-29T08:00:00Z", "2026-10-29", if (read) "2026-10-29T09:00:00Z" else null)
    private val page = TsoInboxPage(
        "2026-10-29T09:30:00Z", 1,
        listOf(n(3, "support_ticket", "6f1c0b9e-1111-4c4c-9a9a-000000000003", read = false, bn = "টিকিট আপডেট"), n(2, "announcement", "x", read = true)), null,
    )

    private fun show(load: CardLoad<TsoInboxPage>?, language: AppLanguage = AppLanguage.EN, pending: Boolean = false, onOpen: (TsoInboxTarget) -> Unit = {}, older: (() -> Unit)? = null, olderRows: List<TsoNotification> = emptyList()) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides ctx) {
                TsoLoginTheme(language, loggedOut = false) { TsoInboxContent(load, olderRows, now, false, pending, {}, onOpen, older) }
            }
        }
    }

    @Test fun rowsTheNewDotAndARowOpensItsTicket() {
        val opened = mutableListOf<TsoInboxTarget>()
        show(CardLoad(page), onOpen = { opened += it })
        assertEquals(emptyList<String>(), compose.a11yProblems())
        compose.onNodeWithTag(TsoInboxTags.dot(3), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TsoInboxTags.dot(2), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Title 3", substring = true).assertExists()
        compose.onNodeWithTag(TsoInboxTags.row(2)).assertHasNoClickAction() // no screen of its own
        compose.onNodeWithTag(TsoInboxTags.row(3)).assertHasClickAction().performClick()
        assertEquals(listOf(TsoInboxTarget(TsoScreen.TICKET_DETAIL, "6f1c0b9e-1111-4c4c-9a9a-000000000003")), opened)
        compose.onNodeWithTag(TsoInboxTags.AS_OF).assertExists()
        compose.onNodeWithTag(TsoInboxTags.OLDER).assertDoesNotExist()
    }

    @Test fun banglaTextsAndThePendingReadLine() {
        show(CardLoad(page, kept = true, failure = TsoFailure.OFFLINE), language = AppLanguage.BN, pending = true, older = {})
        compose.onNodeWithText("টিকিট আপডেট", substring = true).assertExists()
        compose.onNodeWithText("Title 2", substring = true).assertExists() // no Bangla text from the producer: the English one
        compose.onNodeWithTag(TsoInboxTags.PENDING).assertExists()
        compose.onNodeWithTag(TsoInboxTags.OLDER).assertExists()
    }

    @Test fun emptyAndOfflineWithoutACopy() {
        show(CardLoad(page.copy(unreadCount = 0, items = emptyList())))
        compose.onNodeWithText("No notifications yet.").assertExists()
    }

    @Test fun offlineWithoutACopySaysSo() {
        show(CardLoad(null, failure = TsoFailure.OFFLINE))
        compose.onNodeWithText("Not available offline yet. Pull down to try again.").assertExists()
    }

    /** tso-manual A1 fix round: the list is lazy and keyed by id; "Show older" pages only add rows, the screen composes what it shows. */
    @Test fun aLongInboxIsLazyAndAnOlderPageRepeatingARowShowsItOnce() {
        val first = (200L downTo 151L).map { n(it, "announcement", "x", read = true) }
        val olderRows = (151L downTo 2L).map { n(it, "announcement", "x", read = true) } // 151 repeats the first page's last row
        show(CardLoad(page.copy(items = first, nextBeforeId = 1)), older = {}, olderRows = olderRows)
        compose.onNodeWithTag(TsoInboxTags.row(200)).assertExists()
        compose.onNodeWithTag(TsoInboxTags.row(2)).assertDoesNotExist() // not composed until it is on screen
        compose.onNodeWithTag(TsoInboxTags.LIST).performScrollToNode(hasTestTag(TsoInboxTags.OLDER))
        compose.onNodeWithTag(TsoInboxTags.row(2)).assertExists()
        compose.onNodeWithTag(TsoInboxTags.OLDER).assertExists()
        compose.onNodeWithTag(TsoInboxTags.LIST).performScrollToNode(hasTestTag(TsoInboxTags.row(151)))
        compose.onAllNodesWithTag(TsoInboxTags.row(151)).assertCountEquals(1)
    }
}

package com.aktcl.aron.feature.tso.feedback

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private val cats = listOf(FeedbackCategory("suggestion", "পরামর্শ", "Suggestion", 1), FeedbackCategory("complaint", null, "Complaint", 2))

private class Q(var fail: Boolean = false) : FeedbackQueue {
    val sent = mutableListOf<Triple<String, String, FeedbackPayload>>()
    override suspend fun submit(uuid: String, createdAt: String, payload: FeedbackPayload): Boolean { if (fail) error("db"); sent += Triple(uuid, createdAt, payload); return true }
}

private class Photos : FeedbackPhotos {
    val taken = mutableListOf<String>(); val dropped = mutableListOf<String>()
    override suspend fun take(photoUuid: String): String? = photoUuid.also { taken += it }
    override suspend fun discard(photoUuid: String) { dropped += photoUuid }
}

private fun item(u: String, at: String, sent: Boolean = true, st: FeedbackStatus = FeedbackStatus.NEW) = FeedbackItem(u, "suggestion", "t$u", "d", at, st, sent)

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FeedbackLogicTest {
    @Test fun validation() {
        fun v(f: FeedbackForm) = validateFeedback(f, cats)
        assertTrue(v(FeedbackForm("suggestion", "t", "d")).isEmpty())
        assertEquals(listOf(FeedbackError.CATEGORY), v(FeedbackForm(null, "t", "d")))
        assertEquals(listOf(FeedbackError.CATEGORY), v(FeedbackForm("praise", "t", "d")))
        assertEquals(listOf(FeedbackError.TITLE_EMPTY, FeedbackError.DESCRIPTION_EMPTY), v(FeedbackForm("suggestion", " ", "")))
        assertEquals(listOf(FeedbackError.TITLE_LONG), v(FeedbackForm("suggestion", "ক".repeat(121), "d")))
        assertTrue(v(FeedbackForm("suggestion", "ক".repeat(120), "ক".repeat(2000))).isEmpty())
        assertEquals(listOf(FeedbackError.DESCRIPTION_LONG), v(FeedbackForm("suggestion", "t", "x".repeat(2001))))
    }

    @Test fun queuesOnceWithTrimmedTextPhotoAndSameUuidOnRetry() = runBlocking {
        val q = Q(fail = true); val ph = Photos()
        var n = 0
        val s = FeedbackFormState(q, cats, { "2026-10-17T10:00:00Z" }, ph) { "u${n++}" }
        s.edit { it.copy(categoryCode = "complaint", title = " Late ", description = " stock ") }
        s.takePhoto()
        s.submit(); assertEquals(FeedbackResult.Failed, s.result.value)
        q.fail = false
        s.submit(); s.submit()
        assertEquals(1, q.sent.size)
        assertEquals("u0", q.sent[0].first)
        assertEquals(FeedbackPayload("complaint", "Late", "stock", "u1"), q.sent[0].third)
    }

    @Test fun replacingAPhotoDiscardsTheOldOne() = runBlocking {
        val ph = Photos(); var n = 0
        val s = FeedbackFormState(Q(), cats, { "x" }, ph) { "u${n++}" }
        s.takePhoto(); s.takePhoto(); s.removePhoto()
        assertEquals(listOf("u1", "u2"), ph.taken); assertEquals(listOf("u1", "u2"), ph.dropped); assertNull(s.form.value.photoUuid)
    }

    @Test fun noCategoriesBlocksSaving() = runBlocking {
        val q = Q(); val s = FeedbackFormState(q, emptyList(), { "x" })
        s.edit { it.copy(title = "a", description = "b") }; s.submit()
        assertEquals(FeedbackResult.NoCategories, s.result.value); assertTrue(q.sent.isEmpty())
    }

    @Test fun codeListParsingKeepsOnlyFeedbackCategoriesSortedAndDropsBadRows() {
        val json = """[{"list_key":"channel","items":[{"code":"gt","label_en":"GT","sort":1}]},{"list_key":"feedback_category","items":[
            {"code":"b","label_en":"B","label_bn":null,"sort":2},{"code":"a","label_en":"A","label_bn":"এ","sort":1},{"label_en":"no code"}]}]"""
        val c = CodeLists.feedbackCategories(json)
        assertEquals(listOf("a", "b"), c.map { it.code }); assertEquals("এ", c[0].label(true)); assertEquals("B", c[1].label(true))
        assertTrue(CodeLists.feedbackCategories(null).isEmpty()); assertTrue(CodeLists.feedbackCategories("{").isEmpty())
    }

    @Test fun jsonDropsUnknownStatusAndRoundTrips() {
        val page = """{"items":[{"feedback_uuid":"a","user_id":1,"category_code":"suggestion","title":"T","description":"D","photo_uuid":null,"created_at":"2026-10-01T00:00:00Z","status":"resolved"},
            {"feedback_uuid":"b","category_code":"x","title":"T","description":"D","created_at":"z","status":"weird"}],"next_cursor":null}"""
        val p = FeedbackJson.parsePage(page)!!
        assertEquals(listOf("a"), p.items.map { it.feedbackUuid }); assertEquals(FeedbackStatus.RESOLVED, p.items[0].status)
        assertEquals(p.items, FeedbackJson.parsePage(FeedbackJson.toCache(p.items + item("c", "2026-10-02", sent = false)))!!.items)
        assertNull(FeedbackJson.parsePage("<html>"))
        assertEquals(false, FeedbackJson.parseUnsent(FeedbackJson.unsentToJson(item("z", "2026-10-03", sent = false)))!!.sent)
    }

    @Test fun mergeIsNewestFirstAndUnique() {
        val m = mergeFeedback(listOf(item("a", "2026-10-01"), item("a", "2026-10-01")), listOf(item("a", "2026-10-01", sent = false), item("b", "2026-10-05", sent = false)))
        assertEquals(listOf("b", "a"), m.map { it.feedbackUuid }); assertTrue(m.first { it.feedbackUuid == "a" }.sent)
    }

    private class Src(val cached: FeedbackSource.Cached? = null, val fresh: FeedbackSource.Cached? = null, val unsent: List<FeedbackItem> = emptyList()) : FeedbackSource {
        override suspend fun categories() = cats
        override suspend fun cached() = cached
        override suspend fun unsent() = unsent
        override suspend fun refresh() = fresh
    }

    @Test fun listStates() = runBlocking {
        FeedbackListState(Src(fresh = FeedbackSource.Cached(emptyList(), "10:00"))).also { it.load() }.ui.value.let { assertEquals(FeedbackListUi.Empty, it) }
        FeedbackListState(Src()).also { it.load() }.ui.value.let { assertEquals(FeedbackListUi.Error, it) }
        val off = FeedbackListState(Src(cached = FeedbackSource.Cached(listOf(item("a", "2026-10-01")), "09:00"))).also { it.load() }.ui.value as FeedbackListUi.Content
        assertTrue(off.offlineCached)
        val onlyLocal = FeedbackListState(Src(unsent = listOf(item("a", "2026-10-01", sent = false)))).also { it.load() }.ui.value as FeedbackListUi.Content
        assertEquals(false, onlyLocal.items.single().sent)
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FeedbackScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit): Context {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(language) { content() } } }
        compose.waitForIdle(); return context
    }

    @Test fun listShowsStatusCategoryAndNotSentYet() {
        val src = object : FeedbackSource {
            override suspend fun categories() = cats
            override suspend fun cached(): FeedbackSource.Cached? = null
            override suspend fun unsent() = listOf(item("u", "2026-10-17T09:00:00Z", sent = false))
            override suspend fun refresh() = FeedbackSource.Cached(listOf(item("a", "2026-10-01T00:00:00Z", st = FeedbackStatus.IN_PROGRESS)), "10:42")
        }
        show(AppLanguage.EN) { FeedbackListScreen(FeedbackListState(src), cats, onBack = {}, onNew = {}) }
        compose.onNodeWithTag(FeedbackTags.card("u")).assertExists(); compose.onNodeWithTag(FeedbackTags.unsent("u")).assertExists()
        compose.onNodeWithTag(FeedbackTags.LIST).performScrollToNode(hasTestTag(FeedbackTags.card("a")))
        compose.onNodeWithText("In progress").assertExists(); compose.onNodeWithText("Suggestion  ·  2026-10-01").assertExists()
    }

    @Test fun banglaListUsesBanglaStatusAndCategoryAndDigits() {
        val src = object : FeedbackSource {
            override suspend fun categories() = cats
            override suspend fun cached(): FeedbackSource.Cached? = null
            override suspend fun unsent() = emptyList<FeedbackItem>()
            override suspend fun refresh() = FeedbackSource.Cached(listOf(item("a", "2026-10-01T00:00:00Z", st = FeedbackStatus.RESOLVED)), "10:42")
        }
        show(AppLanguage.BN) { FeedbackListScreen(FeedbackListState(src), cats, onBack = {}, onNew = {}) }
        compose.onNodeWithText("সমাধান হয়েছে").assertExists(); compose.onNodeWithText("পরামর্শ  ·  ২০২৬-১০-০১").assertExists()
    }

    @Test fun formShowsErrorsThenSavesOffline() {
        val q = Q()
        val state = FeedbackFormState(q, cats, { "2026-10-17T10:00:00Z" })
        show(AppLanguage.EN) { FeedbackFormScreen(state, onBack = {}) }
        compose.onNodeWithTag(FeedbackTags.SEND).performClickOnPrimary()
        compose.waitForIdle()
        compose.onAllNodesWithTag(FeedbackTags.ERRORS).assertCountEquals(3) // category, title, description
        assertTrue(q.sent.isEmpty())
    }

    @Test fun noCategoriesShowsTheWarning() {
        show(AppLanguage.EN) { FeedbackFormScreen(FeedbackFormState(Q(), emptyList(), { "x" }), onBack = {}) }
        compose.onNodeWithTag(FeedbackTags.NO_CATEGORIES).assertExists()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickOnPrimary() = performClick()

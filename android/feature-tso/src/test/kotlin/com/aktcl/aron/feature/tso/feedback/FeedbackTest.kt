package com.aktcl.aron.feature.tso.feedback

import com.aktcl.aron.feature.tso.a11yProblems
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
        org.junit.Assert.assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
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
        org.junit.Assert.assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun noCategoriesShowsTheWarning() {
        show(AppLanguage.EN) { FeedbackFormScreen(FeedbackFormState(Q(), emptyList(), { "x" }), onBack = {}) }
        compose.onNodeWithTag(FeedbackTags.NO_CATEGORIES).assertExists()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickOnPrimary() = performScrollTo().performClick() // Save sits under the form (screenshot 43), below the fold of a small test screen

/** tso-manual A3 step 2 (D-TSO-MAN-36, screenshot 43): categories as a list, one gallery picture, back after Save, the business date. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FeedbackTest {
    @get:Rule val compose = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun show(language: AppLanguage, content: @androidx.compose.runtime.Composable () -> Unit) {
        val context = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        compose.setContent { CompositionLocalProvider(LocalContext provides context) { AronTheme(language) { content() } } }
        compose.waitForIdle()
    }

    /** The two categories of the code list after V0131 (D-197 superseded): suggestion and feedback. */
    private val two = CodeLists.feedbackCategories("""[{"list_key":"feedback_category","items":[{"code":"feedback","label_en":"Feedback","label_bn":null,"sort":2},{"code":"suggestion","label_en":"Suggestion","label_bn":null,"sort":1}]}]""")

    private class Gallery(val outcome: (Int) -> GalleryPick = { GalleryPick.OK }) : FeedbackPhotos {
        val imported = mutableListOf<Triple<String, String, String>>(); val dropped = mutableListOf<String>()
        override suspend fun take(photoUuid: String): String? = error("the feedback form offers no camera")
        override suspend fun discard(photoUuid: String) { dropped += photoUuid }
        override suspend fun fromGallery(photoUuid: String, feedbackUuid: String, uri: android.net.Uri): GalleryPick {
            imported += Triple(photoUuid, feedbackUuid, uri.toString()); return outcome(imported.size)
        }
    }

    private val pic = android.net.Uri.parse("content://media/picker/0/1")

    @Test fun categoriesComeFromTheCodeListInOrder() {
        assertEquals(listOf("suggestion", "feedback"), two.map { it.code })
    }

    @Test fun theMaxImagesConfigIsBoundedToOne() {
        assertEquals(1, feedbackMaxImages(null)); assertEquals(1, feedbackMaxImages(5)); assertEquals(0, feedbackMaxImages(0)); assertEquals(0, feedbackMaxImages(-2))
    }

    @Test fun oneGalleryPictureIsAddedAReplacementDropsTheFirstAndSaveSendsIt() = runBlocking {
        val g = Gallery(); var n = 0; val q = Q()
        val s = FeedbackFormState(q, two, { "2026-10-17T10:00:00Z" }, g, 1) { "u${n++}" }
        s.addFromGallery(pic); assertEquals("u1", s.form.value.photoUuid)
        s.addFromGallery(pic); assertEquals("u2", s.form.value.photoUuid); assertEquals(listOf("u1"), g.dropped)
        assertEquals(listOf("u0", "u0"), g.imported.map { it.second }) // stored for this form's feedback uuid, claimed only by Save
        s.edit { it.copy(categoryCode = "feedback", title = "Map", description = "Show the zone names") }; s.submit()
        assertEquals(FeedbackPayload("feedback", "Map", "Show the zone names", "u2"), q.sent.single().third)
    }

    @Test fun aRefusedPictureLeavesTheFormAsItWasAndSaysWhy() = runBlocking {
        val g = Gallery { if (it == 1) GalleryPick.OK else GalleryPick.TOO_LARGE }; var n = 0
        val s = FeedbackFormState(Q(), two, { "x" }, g, 1) { "u${n++}" }
        s.addFromGallery(pic); s.addFromGallery(pic)
        assertEquals("u1", s.form.value.photoUuid); assertEquals(GalleryPick.TOO_LARGE, s.galleryError.value); assertTrue(g.dropped.isEmpty())
    }

    @Test fun noPictureWhenTheConfigSaysZero() = runBlocking {
        val g = Gallery(); val s = FeedbackFormState(Q(), two, { "x" }, g, 0)
        s.addFromGallery(pic); assertNull(s.form.value.photoUuid); assertTrue(g.imported.isEmpty())
        show(AppLanguage.EN) { FeedbackFormScreen(s, onBack = {}, photosEnabled = true) }
        compose.onNodeWithTag(FeedbackTags.PHOTO).assertDoesNotExist()
    }

    @Test fun theFormShowsTheCategoriesAsAListAndBrowseGalleryOnly() {
        val s = FeedbackFormState(Q(), two, { "x" }, Gallery(), 1)
        show(AppLanguage.EN) { FeedbackFormScreen(s, onBack = {}, photosEnabled = true) }
        for (t in listOf("Create New Feedback", "Feedback Category", "Suggestion", "Feedback", "Enter title", "Descriptions", "Enter description", "Browse Gallery", "Save")) {
            compose.onNode(androidx.compose.ui.test.hasText(t), useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithTag(FeedbackTags.category("feedback")).performClick(); compose.waitForIdle()
        assertEquals("feedback", s.form.value.categoryCode)
        org.junit.Assert.assertEquals("accessibility: " + compose.a11yProblems(), emptyList<String>(), compose.a11yProblems())
    }

    @Test fun banglaFormWords() {
        val s = FeedbackFormState(Q(), two, { "x" }, Gallery(), 1)
        show(AppLanguage.BN) { FeedbackFormScreen(s, onBack = {}, photosEnabled = true) }
        for (t in listOf("নতুন মতামত লিখুন", "মতামতের ধরন", "শিরোনাম লিখুন", "বিবরণ", "গ্যালারি থেকে ছবি")) compose.onNode(androidx.compose.ui.test.hasText(t), useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Suggestion").assertExists() // label_bn null: English until AKTCL supplies it (s7.1 Q5)
    }

    @Test fun saveGoesBackAndTheNextVisitIsAFreshForm() {
        val q = Q(); var backs = 0
        val nav = object : com.aktcl.aron.feature.tso.TsoNav {
            override fun open(screen: com.aktcl.aron.feature.tso.TsoScreen, arg: String?) = Unit
            override fun back() { backs++ }
            override fun home() = Unit
        }
        val src = object : FeedbackSource {
            override suspend fun categories() = two
            override suspend fun cached(): FeedbackSource.Cached? = null
            override suspend fun unsent() = emptyList<FeedbackItem>()
            override suspend fun refresh(): FeedbackSource.Cached? = null
            override suspend fun maxImages() = 1
        }
        val visible = androidx.compose.runtime.mutableStateOf(true)
        show(AppLanguage.EN) { if (visible.value) FeedbackNewEntry(src, q, Gallery(), "fb1", { "2026-10-17T10:00:00Z" }, nav) }
        compose.onNodeWithTag(FeedbackTags.category("suggestion")).performClick()
        compose.onNodeWithTag(FeedbackTags.TITLE).performTextInput("Faster sync")
        compose.onNodeWithTag(FeedbackTags.DESCRIPTION).performTextInput("Sync is slow on 2G")
        compose.onNodeWithTag(FeedbackTags.SEND).performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(1, q.sent.size); assertEquals(1, backs)
        // the drawer opens the form again: a fresh form with a new uuid, nothing typed
        compose.runOnIdle { visible.value = false }; compose.waitForIdle()
        compose.runOnIdle { visible.value = true }; compose.waitForIdle()
        compose.onNodeWithTag(FeedbackTags.QUEUED).assertDoesNotExist()
        compose.onNodeWithTag(FeedbackTags.category("suggestion")).performClick()
        compose.onNodeWithTag(FeedbackTags.TITLE).performTextInput("Second")
        compose.onNodeWithTag(FeedbackTags.DESCRIPTION).performTextInput("d")
        compose.onNodeWithTag(FeedbackTags.SEND).performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(2, q.sent.map { it.first }.distinct().size); assertEquals("Second", q.sent.last().third.title)
    }

    @Test fun theListPrintsTheBusinessDate() {
        val page = """{"items":[{"feedback_uuid":"a","category_code":"suggestion","title":"T","description":"D","photo_uuid":null,"created_at":"2026-10-16T20:30:00Z","business_date":"2026-10-17","status":"new"}],"next_cursor":null}"""
        val items = FeedbackJson.parsePage(page)!!.items
        assertEquals("2026-10-17", items.single().shownDate) // 02:30 Dhaka on the 17th, created on the 16th in UTC
        assertEquals(items, FeedbackJson.parsePage(FeedbackJson.toCache(items))!!.items)
        assertEquals("2026-10-01", item("b", "2026-10-01T00:00:00Z").shownDate) // an older row without it: the created date
        val src = object : FeedbackSource {
            override suspend fun categories() = two
            override suspend fun cached(): FeedbackSource.Cached? = null
            override suspend fun unsent() = emptyList<FeedbackItem>()
            override suspend fun refresh() = FeedbackSource.Cached(items, "10:42")
        }
        show(AppLanguage.EN) { FeedbackListScreen(FeedbackListState(src), two, onBack = {}, onNew = {}) }
        compose.onNodeWithText("Suggestion  ·  2026-10-17").assertExists()
        compose.onNodeWithText("Add new feedback").assertExists()
    }
}

class FeedbackToastTest {
    @Test fun theToastIsConsumedOnce() = runBlocking {
        val s = FeedbackFormState(Q(), cats, { "x" })
        s.edit { it.copy(categoryCode = "suggestion", title = "a", description = "b") }
        assertNull(s.consumeToast())
        s.submit()
        assertEquals(FeedbackResult.Queued, s.consumeToast())
        assertNull(s.consumeToast()) // a recreated screen does not toast again
    }
}

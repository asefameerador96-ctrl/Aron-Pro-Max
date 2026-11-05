package com.aktcl.aron.feature.tso.feedback

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Contract FeedbackPayload bounds. */
const val MAX_TITLE = 120
const val MAX_DESCRIPTION = 2000

enum class FeedbackStatus(val wire: String) {
    NEW("new"), IN_PROGRESS("in_progress"), RESOLVED("resolved"), CLOSED("closed");
}

data class FeedbackCategory(val code: String, val labelBn: String?, val labelEn: String, val sort: Int) {
    fun label(bangla: Boolean): String = if (bangla && !labelBn.isNullOrBlank()) labelBn else labelEn
}

/**
 * One feedback as the list shows it ([sent] false = still in the outbox: "not sent yet"). [businessDate] is the Dhaka
 * business date of the feedback (contract 1.10.0, D-TSO-MAN-36); the list shows it, else the date of [createdAt].
 */
data class FeedbackItem(
    val feedbackUuid: String, val categoryCode: String, val title: String, val description: String,
    val createdAt: String, val status: FeedbackStatus, val sent: Boolean = true, val hasPhoto: Boolean = false,
    val businessDate: String? = null,
) {
    /** The date the card prints: the business date when the server or the phone gave one. */
    val shownDate: String get() = businessDate ?: createdAt.take(10)
}

data class FeedbackForm(val categoryCode: String? = null, val title: String = "", val description: String = "", val photoUuid: String? = null)
data class FeedbackPayload(val categoryCode: String, val title: String, val description: String, val photoUuid: String?)

enum class FeedbackError { CATEGORY, TITLE_EMPTY, TITLE_LONG, DESCRIPTION_EMPTY, DESCRIPTION_LONG }

fun validateFeedback(f: FeedbackForm, categories: List<FeedbackCategory>): List<FeedbackError> {
    val e = mutableListOf<FeedbackError>()
    if (f.categoryCode == null || categories.none { it.code == f.categoryCode }) e += FeedbackError.CATEGORY
    val t = f.title.trim()
    if (t.isEmpty()) e += FeedbackError.TITLE_EMPTY else if (t.length > MAX_TITLE) e += FeedbackError.TITLE_LONG
    val d = f.description.trim()
    if (d.isEmpty()) e += FeedbackError.DESCRIPTION_EMPTY else if (d.length > MAX_DESCRIPTION) e += FeedbackError.DESCRIPTION_LONG
    return e
}

fun feedbackPayload(f: FeedbackForm) = FeedbackPayload(checkNotNull(f.categoryCode), f.title.trim(), f.description.trim(), f.photoUuid)

/** Newest first, a uuid once (the server row wins over the local unsent one). */
fun mergeFeedback(server: List<FeedbackItem>, local: List<FeedbackItem>): List<FeedbackItem> {
    val seen = server.map { it.feedbackUuid }.toSet()
    return (server + local.filter { it.feedbackUuid !in seen }).distinctBy { it.feedbackUuid }
        .sortedWith(compareByDescending<FeedbackItem> { it.createdAt }.thenBy { it.feedbackUuid })
}

/** Reads `feedback_category` out of the bundle section `code_lists` (an array of `{list_key, items[]}`); bad rows are dropped. */
object CodeLists {
    fun feedbackCategories(sectionJson: String?): List<FeedbackCategory> = runCatching {
        val arr = JSONArray(sectionJson ?: return emptyList())
        (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("list_key") == "feedback_category" }
            ?.getJSONArray("items")?.let { items ->
                (0 until items.length()).mapNotNull { i ->
                    runCatching {
                        val o = items.getJSONObject(i)
                        FeedbackCategory(o.getString("code"), if (o.isNull("label_bn")) null else o.optString("label_bn").ifBlank { null }, o.getString("label_en"), o.optInt("sort", 0))
                    }.getOrNull()
                }.sortedWith(compareBy<FeedbackCategory> { it.sort }.thenBy { it.code })
            } ?: emptyList()
    }.getOrDefault(emptyList())
}

object FeedbackJson {
    data class Page(val items: List<FeedbackItem>, val nextCursor: String?)

    private fun str(o: JSONObject, k: String): String { require(!o.isNull(k)) { "$k null" }; return o.getString(k) }

    private fun item(o: JSONObject) = FeedbackItem(
        str(o, "feedback_uuid"), str(o, "category_code"), str(o, "title"), str(o, "description"), str(o, "created_at"),
        status = checkNotNull(FeedbackStatus.entries.firstOrNull { it.wire == (if (o.isNull("status")) "new" else str(o, "status")) }) { "unknown status" },
        hasPhoto = o.has("photo_uuid") && !o.isNull("photo_uuid") && o.optString("photo_uuid").isNotEmpty(),
        businessDate = if (!o.has("business_date") || o.isNull("business_date")) null else o.optString("business_date").takeIf { it.length == 10 },
    )

    fun parsePage(body: String): Page? = runCatching {
        val o = JSONObject(body); val a = o.getJSONArray("items")
        Page((0 until a.length()).mapNotNull { runCatching { item(a.getJSONObject(it)) }.getOrNull() }, if (o.isNull("next_cursor")) null else o.optString("next_cursor").ifEmpty { null })
    }.getOrNull()

    private fun obj(i: FeedbackItem) = JSONObject().put("feedback_uuid", i.feedbackUuid).put("category_code", i.categoryCode).put("title", i.title)
        .put("description", i.description).put("created_at", i.createdAt).put("status", i.status.wire).put("photo_uuid", if (i.hasPhoto) "x" else JSONObject.NULL)
        .put("business_date", i.businessDate ?: JSONObject.NULL)

    fun toCache(items: List<FeedbackItem>): String =
        JSONObject().put("items", JSONArray(items.filter { it.sent }.map(::obj))).put("next_cursor", JSONObject.NULL).toString()

    fun unsentToJson(i: FeedbackItem): String = obj(i).toString()
    fun parseUnsent(t: String): FeedbackItem? = runCatching { item(JSONObject(t)).copy(sent = false) }.getOrNull()
}

/** Where the categories come from (the login snapshot, offline) and where the list comes from. */
interface FeedbackSource {
    suspend fun categories(): List<FeedbackCategory>
    suspend fun cached(): Cached?
    suspend fun unsent(): List<FeedbackItem>
    suspend fun refresh(): Cached?
    /** `cfg.feedback.max_images` of the bundle, 0..1 (R-F-06: the record holds one photo_uuid); 1 when the bundle has none. 0 hides Browse Gallery. */
    suspend fun maxImages(): Int = 1
    data class Cached(val items: List<FeedbackItem>, val asOf: String)
}

/** The bound of `cfg.feedback.max_images`: one picture at most, whatever the config says (R-F-06). */
fun feedbackMaxImages(cfg: Int?): Int = cfg?.coerceIn(0, 1) ?: 1

fun interface FeedbackQueue { suspend fun submit(uuid: String, createdAt: String, payload: FeedbackPayload): Boolean }

/**
 * The compressed photo of a feedback (F-SYS-010 media queue): [take] returns the queued photo uuid or null (cancelled or
 * failed; the camera layer shows the reason), [discard] drops a photo the rep removed before sending.
 */
interface FeedbackPhotos {
    suspend fun take(photoUuid: String): String?
    suspend fun discard(photoUuid: String)

    /**
     * D-TSO-MAN-36 "Browse Gallery": the picture the rep picked in the system photo picker, read once and stored compressed
     * under [photoUuid] through A2's gallery import (same budget as the camera, EXIF dropped, no storage permission). It is
     * NOT claimed yet: Save claims it for the feedback [feedbackUuid] with the record, so a removed picture or an abandoned
     * form never uploads.
     */
    suspend fun fromGallery(photoUuid: String, feedbackUuid: String, uri: android.net.Uri): GalleryPick = GalleryPick.UNREADABLE
}

/** The outcome of a gallery pick; the screen words every refusal and the form stays as it was. */
enum class GalleryPick { OK, NO_SPACE, TOO_LARGE, UNREADABLE, NO_PICKER }

sealed interface FeedbackResult {
    data object Editing : FeedbackResult
    data object Queued : FeedbackResult
    data class Invalid(val errors: List<FeedbackError>) : FeedbackResult
    data object Failed : FeedbackResult
    data object NoCategories : FeedbackResult
}

/** One uuid per form: a double tap or a retry after a failure never queues twice; nothing waits for the network. */
class FeedbackFormState(
    private val queue: FeedbackQueue, private val categories: List<FeedbackCategory>, private val nowIso: () -> String,
    private val photos: FeedbackPhotos? = null,
    /** `cfg.feedback.max_images` ([feedbackMaxImages]): 0 hides Browse Gallery, 1 allows one picture. */
    maxImages: Int = 1,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) {
    val maxImages: Int = feedbackMaxImages(maxImages)
    private val _galleryError = MutableStateFlow<GalleryPick?>(null)
    /** Why the last gallery pick was not added (null when it was, or nothing was picked). */
    val galleryError: StateFlow<GalleryPick?> = _galleryError.asStateFlow()
    private var uuid = newUuid()
    private var busy = false
    private val _form = MutableStateFlow(FeedbackForm())
    val form: StateFlow<FeedbackForm> = _form.asStateFlow()
    private val _result = MutableStateFlow<FeedbackResult>(if (categories.isEmpty()) FeedbackResult.NoCategories else FeedbackResult.Editing)
    val result: StateFlow<FeedbackResult> = _result.asStateFlow()
    val categoryList: List<FeedbackCategory> get() = categories

    fun edit(f: (FeedbackForm) -> FeedbackForm) {
        if (_result.value is FeedbackResult.Queued || _result.value is FeedbackResult.NoCategories) return
        _form.value = f(_form.value); _result.value = FeedbackResult.Editing
    }

    suspend fun takePhoto() {
        val p = photos ?: return
        _form.value.photoUuid?.let { p.discard(it) }
        val id = newUuid()
        _form.value = _form.value.copy(photoUuid = p.take(id))
    }

    /**
     * The picked picture ([uri]) becomes the form's one picture (a second pick replaces the first, which is dropped). It is
     * stored on the phone at once and claimed for this feedback only by Save. Nothing happens after Save or when the config
     * allows no picture.
     */
    suspend fun addFromGallery(uri: android.net.Uri) {
        val p = photos ?: return
        if (maxImages <= 0 || _result.value is FeedbackResult.Queued) return
        val id = newUuid()
        val r = runCatching { p.fromGallery(id, uuid, uri) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; GalleryPick.UNREADABLE }
        if (r != GalleryPick.OK) { _galleryError.value = r; return }
        _form.value.photoUuid?.let { old -> runCatching { p.discard(old) } }
        _galleryError.value = null
        _form.value = _form.value.copy(photoUuid = id)
        if (_result.value is FeedbackResult.Invalid) _result.value = FeedbackResult.Editing
    }

    /** The phone has no picture picker at all (very old system): said once on the form. */
    fun galleryUnavailable() { _galleryError.value = GalleryPick.NO_PICKER }

    suspend fun removePhoto() {
        val p = photos ?: return
        _form.value.photoUuid?.let { runCatching { p.discard(it) } }
        _form.value = _form.value.copy(photoUuid = null)
    }

    suspend fun submit() {
        if (busy || _result.value is FeedbackResult.Queued || _result.value is FeedbackResult.NoCategories) return
        val errors = validateFeedback(_form.value, categories)
        if (errors.isNotEmpty()) { _result.value = FeedbackResult.Invalid(errors); return }
        busy = true
        try {
            queue.submit(uuid, nowIso(), feedbackPayload(_form.value)); _result.value = FeedbackResult.Queued
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { _result.value = FeedbackResult.Failed } finally { busy = false }
    }

    private var toasted: FeedbackResult? = null

    /** The Queued or Failed result once, for the toast: a recreation of the screen does not toast it again. */
    fun consumeToast(): FeedbackResult? {
        val r = _result.value
        if ((r is FeedbackResult.Queued || r is FeedbackResult.Failed) && toasted != r) { toasted = r; return r }
        return null
    }

    fun startNew() { uuid = newUuid(); _form.value = FeedbackForm(); _result.value = FeedbackResult.Editing; toasted = null }
}

/** Holds the category list once loaded (null = still loading) across recreation. */
class CategoryHolder {
    private val state = androidx.compose.runtime.mutableStateOf<List<FeedbackCategory>?>(null)
    var list: List<FeedbackCategory>?
        get() = state.value
        set(v) { state.value = v }
}

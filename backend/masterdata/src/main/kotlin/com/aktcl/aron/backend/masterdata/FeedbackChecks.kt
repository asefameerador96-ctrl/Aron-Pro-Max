package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.IngestRecord
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.RecordRefusal
import com.aktcl.aron.contract.RecordOutcomeCode
import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.jdbi.v3.core.Handle
import java.time.LocalDate

/**
 * The rules of a TSO feedback (tso-manual B3, D-TSO-MAN-36, FB-11; R-F-06), shared by `POST /v1/feedback` and the
 * `feedback` sync record ([FeedbackChecks]), in this order after the author's role (TSO only; else 403 on the POST and
 * `scope_out_of_reach` on sync):
 * 1. the category is a `feedback_category` code valid on the business date (suggestion or feedback; unless the list is
 *    not seeded): 400 `unknown_code`, sync `schema_invalid` (final);
 * 2. one gallery image at most, and none while `cfg.feedback.max_images` is 0 (bounded 0..1: the record holds one
 *    `photo_uuid`, R-F-06): 400 `not_allowed` on `photo_uuid`, sync `schema_invalid` (final).
 * Title and description lengths are the contract's (the POST checks them, ingest's shape check does on sync).
 */
object FeedbackRules {
    fun maxImages(h: Handle): Int = HandleConfig.int(h, "cfg.feedback.max_images", 1).coerceIn(0, 1)

    fun check(h: Handle, categoryCode: String, hasPhoto: Boolean, businessDate: LocalDate): CaptureRefusal? {
        if (!LeaveRules.codeValid(h, "feedback_category", categoryCode, businessDate)) {
            return CaptureRefusal("category_code", "unknown_code", RecordOutcomeCode.SCHEMA_INVALID, false, "category $categoryCode is not a feedback category on $businessDate")
        }
        if (hasPhoto && maxImages(h) < 1) {
            return CaptureRefusal("photo_uuid", "not_allowed", RecordOutcomeCode.SCHEMA_INVALID, false, "a photo while cfg.feedback.max_images is 0")
        }
        return null
    }
}

/**
 * The ingest precheck of a `feedback` record (tso-manual B3, D-TSO-MAN-36 "ingest precheck shared with POST"): the same
 * [FeedbackRules] the POST runs, judged on the record's business date. A replay of a stored feedback is a duplicate
 * before any rule (the registry answers first), so a category retired since the capture never refuses a resend.
 */
class FeedbackChecks : RecordHandler {
    override val types = setOf("feedback")

    override fun check(h: Handle, rec: IngestRecord): RecordRefusal? {
        if (rec.role != Role.TSO) return RecordRefusal(RecordOutcomeCode.SCOPE_OUT_OF_REACH, "feedback is sent by a TSO, not a ${rec.role.wire}")
        val category = (rec.payload["category_code"] as? JsonPrimitive)?.content ?: return null
        val photo = rec.payload["photo_uuid"].let { it != null && it !is JsonNull }
        return FeedbackRules.check(h, category, photo, rec.businessDate)?.record()
    }
}

package com.aktcl.aron.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Room v5 (2026-10-07): AV/KV content and POSM surveys (docs/requests/android-sr-a-av-kv-survey-data.md; F-SR-020,
// F-SR-021). Reference rows come from the bundle's `content` and `surveys` sections and are replaced by every snapshot;
// the two capture tables are records (`content_view`, `survey_response`). No points ledger: loyalty is deferred (docs/27).

/** An AV or KV item of the bundle (contract `ContentItem`). The file itself lives in the content cache, keyed by [assetUrl]. */
@Entity(tableName = "content_item")
data class ContentItemEntity(
    @PrimaryKey @ColumnInfo(name = "content_id") val contentId: Long,
    val version: Int,
    /** `av` or `kv` (contract ContentKind). */
    val kind: String,
    @ColumnInfo(name = "title_en") val titleEn: String,
    @ColumnInfo(name = "title_bn") val titleBn: String?,
    @ColumnInfo(name = "asset_url") val assetUrl: String,
    val sha256: String,
    val bytes: Long,
    @ColumnInfo(name = "duration_s") val durationS: Int?,
    @ColumnInfo(name = "valid_from") val validFrom: String,
    @ColumnInfo(name = "valid_to") val validTo: String,
    /** Play order in the call (AV before KV). */
    val sequence: Int,
    /** The bundle's empty `outlet_ids`: every outlet of the user's routes. */
    @ColumnInfo(name = "all_outlets") val allOutlets: Boolean,
)

/** One outlet a content item is assigned to (the item's `outlet_ids`). */
@Entity(tableName = "outlet_content_assignment", primaryKeys = ["outlet_id", "content_id"], indices = [Index("content_id")])
data class OutletContentAssignmentEntity(
    @ColumnInfo(name = "outlet_id") val outletId: Long,
    @ColumnInfo(name = "content_id") val contentId: Long,
)

/** A survey of the bundle (contract `SurveyDef`); its questions are [SurveyQuestionEntity] rows. */
@Entity(tableName = "survey")
data class SurveyEntity(
    @PrimaryKey @ColumnInfo(name = "survey_id") val surveyId: Long,
    val version: Int,
    @ColumnInfo(name = "title_en") val titleEn: String?,
    @ColumnInfo(name = "title_bn") val titleBn: String?,
)

/** One question of a survey, in the bundle's order ([ordinal]). */
@Entity(tableName = "survey_question", primaryKeys = ["survey_id", "question_id"])
data class SurveyQuestionEntity(
    @ColumnInfo(name = "survey_id") val surveyId: Long,
    @ColumnInfo(name = "question_id") val questionId: Long,
    val ordinal: Int,
    /** bool, num, option, text or photo_only. */
    @ColumnInfo(name = "answer_type") val answerType: String,
    @ColumnInfo(name = "label_en") val labelEn: String,
    @ColumnInfo(name = "label_bn") val labelBn: String?,
    /** JSON array of option codes. */
    @ColumnInfo(name = "option_codes_json") val optionCodesJson: String = "[]",
    @ColumnInfo(name = "requires_photo") val requiresPhoto: Boolean = false,
    @ColumnInfo(name = "question_key") val questionKey: String? = null,
    val required: Boolean? = null,
    @ColumnInfo(name = "show_if_key") val showIfKey: String? = null,
    @ColumnInfo(name = "show_if_bool") val showIfBool: Boolean? = null,
)

/** An AV or KV item shown (or skipped) during a call (record `content_view`, telemetry class): one per item per visit. */
@Entity(tableName = "content_view", indices = [Index("business_date"), Index(value = ["visit_client_uuid", "content_id"], unique = true)])
data class ContentViewEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "visit_client_uuid") val visitClientUuid: String,
    @ColumnInfo(name = "content_id") val contentId: Long,
    @ColumnInfo(name = "content_version") val contentVersion: Int,
    val kind: String,
    /** viewed, skipped_missing or skipped_user. */
    val outcome: String,
    @ColumnInfo(name = "sequence_no") val sequenceNo: Int,
    @ColumnInfo(name = "started_at") val startedAt: String? = null,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
)

/** One answer of a survey during a call (record `survey_response`): one per question per visit; a photo is a media uuid (core-media). */
@Entity(tableName = "survey_response", indices = [Index("business_date"), Index(value = ["visit_client_uuid", "survey_id", "question_id"], unique = true)])
data class SurveyResponseEntity(
    @PrimaryKey @ColumnInfo(name = "client_uuid") val clientUuid: String,
    @Embedded val meta: CaptureMeta,
    @ColumnInfo(name = "visit_client_uuid") val visitClientUuid: String,
    @ColumnInfo(name = "survey_id") val surveyId: Long,
    @ColumnInfo(name = "survey_version") val surveyVersion: Int,
    @ColumnInfo(name = "question_id") val questionId: Long,
    @ColumnInfo(name = "answer_type") val answerType: String,
    @ColumnInfo(name = "answer_bool") val answerBool: Boolean? = null,
    @ColumnInfo(name = "answer_num") val answerNum: Double? = null,
    @ColumnInfo(name = "answer_option_code") val answerOptionCode: String? = null,
    @ColumnInfo(name = "answer_text") val answerText: String? = null,
    @ColumnInfo(name = "photo_uuid") val photoUuid: String? = null,
)

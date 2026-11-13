package com.aktcl.aron.backend.sync

import com.aktcl.aron.contract.RecordOutcomeCode

/**
 * THE ONE TABLE (C-R2, D-LOY-API-24): which check of a `gift_choice` or `gift_photo` handler a reviewer's accept waives, per hold code.
 *
 * A record reaches the review queue under a code ([RecordOutcomeCode] with status quarantined). When a reviewer accepts it, the record is processed again with
 * `IngestRecord.released` = that code, and EXACTLY the check that produced the code is waived; every other check is judged as for any record. The generic stages of the ingest
 * (business-date window, device enrolment and integrity, content duplicate, day lock) hold a record before a handler runs and have no handler check behind them: they waive nothing here.
 *
 * | hold code                                | waives (handler check)                                                                                     |
 * |------------------------------------------|------------------------------------------------------------------------------------------------------------|
 * | scope_out_of_reach                       | [Check.WHO]: the uploader's role may choose (gift_choice) and the outlet is in the uploader's reach           |
 * | no_assignment_on_date                    | [Check.REACH]: the outlet is in the uploader's reach ON THE RECORD'S DATE (a cover day, a re-assigned route)  |
 * | programme_inactive                       | the EARLIEST failing of [Check.PROGRAMME] (the gift's programme is an active Astha programme) and             |
 * |                                          | [Check.ENROLMENT] (the outlet is enrolled on the date), never both: [firstProgrammeCheck]                      |
 * | business_date_out_of_window, device_not_enrolled, device_integrity_failed, content_duplicate, after_final_submit, anything else | nothing                                             |
 *
 * The kind, status, tier, open quarter and lock checks are never waived by any code.
 */
object GiftHolds {
    enum class Check { WHO, REACH, PROGRAMME, ENROLMENT }

    /** The table. A code not in it waives nothing. */
    val WAIVES: Map<RecordOutcomeCode, Set<Check>> = mapOf(
        RecordOutcomeCode.SCOPE_OUT_OF_REACH to setOf(Check.WHO, Check.REACH),
        RecordOutcomeCode.NO_ASSIGNMENT_ON_DATE to setOf(Check.REACH),
        RecordOutcomeCode.PROGRAMME_INACTIVE to setOf(Check.PROGRAMME, Check.ENROLMENT),
    )

    /** True when the accept of [released] waives [check]. PROGRAMME_INACTIVE covers two checks: use [firstProgrammeCheck] to apply it to one. */
    fun waives(released: RecordOutcomeCode?, check: Check): Boolean = released != null && check in (WAIVES[released] ?: emptySet())

    /** For a PROGRAMME_INACTIVE accept: the one check that is waived, the earliest of PROGRAMME, ENROLMENT that fails now (the status check runs first). Null = neither fails or the code is another. */
    fun firstProgrammeCheck(released: RecordOutcomeCode?, programmeFails: Boolean, enrolmentFails: Boolean): Check? = when {
        released != RecordOutcomeCode.PROGRAMME_INACTIVE -> null
        programmeFails -> Check.PROGRAMME
        enrolmentFails -> Check.ENROLMENT
        else -> null
    }
}

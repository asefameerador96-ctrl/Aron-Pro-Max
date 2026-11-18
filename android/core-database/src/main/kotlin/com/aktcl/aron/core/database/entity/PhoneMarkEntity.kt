package com.aktcl.aron.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * D-AGATE-18: a LOCAL, never-uploaded marker that the PHONE (not the rep) did something after Dhaka midnight: closed a day ([DAY_CLOSED]), ended an earlier call at its 04:00 limit
 * ([CALL_ENDED], with the outlet), dropped an unsaved sale draft ([DRAFT_DROPPED]) or an unsaved memo edit ([EDIT_DROPPED]). A close and its marker are written in ONE Room transaction,
 * so a kill can neither lose the notice nor show one for a close that was never stored. The rollover notice is built from these rows only and they are deleted when it has been shown.
 */
@Entity(tableName = "phone_mark")
data class PhoneMarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    @ColumnInfo(name = "business_date") val businessDate: String,
    @ColumnInfo(name = "outlet_id") val outletId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: String,
) {
    companion object { const val DAY_CLOSED = "day_closed"; const val CALL_ENDED = "call_ended"; const val DRAFT_DROPPED = "draft_dropped"; const val EDIT_DROPPED = "edit_dropped" }
}

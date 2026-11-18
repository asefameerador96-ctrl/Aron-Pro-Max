package com.aktcl.aron.feature.programmes

import org.junit.Assert.assertEquals
import org.junit.Test

class GiftPhotoModelTest {
    private val json = """{"programmes":[{"programme_id":5,"kind":"astha","name_en":"Astha"}],"loyalty":[],"gifts":[
        {"gift_id":1,"programme_id":5,"name_en":"Hotpot","points_cost":null,"tier_codes":[],"status":"active"}],
      "gift_assignments":[{"gift_assignment_id":71,"quarter":"2026-Q4","outlet_id":10,"gift_id":1,"photo_captured":false,"locked":false},
                          {"gift_assignment_id":72,"quarter":"2026-Q4","outlet_id":11,"gift_id":1,"photo_captured":true,"locked":true}]}"""
    private val d = GiftChoiceModel.parse(json)!!
    private fun local(state: String, id: Long = 71) = LocalGiftPhoto("astha", 10, id, null, null, state)

    @Test fun noAssignmentMeansChooseAGiftFirstAndAServerPhotoMeansAlreadyCaptured() {
        assertEquals(PhotoStatus.NoGift, GiftPhotoModel.statusOf(d, 99, "2026-Q4", emptyList()))
        assertEquals(PhotoStatus.Captured(1, onThisPhone = false), GiftPhotoModel.statusOf(d, 11, "2026-Q4", emptyList()))
        assertEquals(PhotoStatus.Ready(1, 71), GiftPhotoModel.statusOf(d, 10, "2026-Q4", emptyList()))
    }

    @Test fun aPhotoSavedOnThisPhoneCountsUntilTheServerRefusesIt() {
        assertEquals(PhotoStatus.Captured(1, onThisPhone = true), GiftPhotoModel.statusOf(d, 10, "2026-Q4", listOf(local("pending"))))
        assertEquals(PhotoStatus.Captured(1, onThisPhone = true), GiftPhotoModel.statusOf(d, 10, "2026-Q4", listOf(local("acked"))))
        assertEquals(PhotoStatus.Ready(1, 71), GiftPhotoModel.statusOf(d, 10, "2026-Q4", listOf(local("rejected")))) // refused: it can be taken again
        assertEquals(PhotoStatus.Ready(1, 71), GiftPhotoModel.statusOf(d, 10, "2026-Q4", listOf(local("pending", id = 99)))) // another assignment's photo
    }
}

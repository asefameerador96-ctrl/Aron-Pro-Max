package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JointCallTest {
    private val rubrics = """[{"rubric_id":3,"version":2,"kind":"retailer_questionnaire","criteria":[]},
        {"rubric_id":7,"version":4,"kind":"joint_call","criteria":[
        {"criterion_id":1,"label_en":"Five-step call","answer_type":"score_1_5","enabled":true},
        {"criterion_id":2,"label_en":"Relationship","answer_type":"score_1_5","enabled":true},
        {"criterion_id":3,"label_en":"Service quality","answer_type":"score_1_5"},
        {"criterion_id":4,"label_en":"Item 4","answer_type":"score_1_5","enabled":false},
        {"criterion_id":5,"label_en":"Note","answer_type":"text","enabled":true}]}]"""

    @Test fun rubricIsTheJointCallOneAndOnlyEnabledScoresAreScored() {
        val r = JointCall.rubric(rubrics)!!
        assertEquals(7L, r.rubricId); assertEquals(4, r.version)
        assertEquals(listOf(1L, 2L, 3L), r.scored.map { it.id }) // missing "enabled" counts as on; item 4 and the text item are not scored
        assertNull(JointCall.rubric(null)); assertNull(JointCall.rubric("nope")); assertNull(JointCall.rubric("[]"))
    }

    /** The shape lead-backend 4e0a3206 sends to the AMO (BundleRubricsTest): active rubrics only, `key` beside the RubricDef members, a null Bangla label, every criterion enabled. */
    @Test fun theServerBundleShapeIsRead() {
        val server = """[{"rubric_id":3,"version":2,"kind":"retailer_questionnaire","criteria":[{"criterion_id":2,"key":"clean","label_en":"Shop clean?","label_bn":"দোকান পরিষ্কার?","answer_type":"bool","enabled":true}]},
            {"rubric_id":4,"version":1,"kind":"joint_call","criteria":[{"criterion_id":4,"key":"greet","label_en":"Greeting","label_bn":null,"answer_type":"score_1_5","enabled":true},
            {"criterion_id":6,"key":"stars","label_en":"Stars","label_bn":"তারা","answer_type":"score_1_5","enabled":true}]}]"""
        val r = JointCall.rubric(server)!!
        assertEquals(4L, r.rubricId); assertEquals(1, r.version)
        assertEquals(listOf(4L, 6L), r.scored.map { it.id })
        assertNull(r.criteria[0].labelBn); assertEquals("তারা", r.criteria[1].labelBn)
        assertNull(JointCall.rubric("""[{"rubric_id":3,"version":2,"kind":"retailer_questionnaire","criteria":[]}]""")) // no joint_call rubric: no Joint Call form
    }

    @Test fun starsDefaultToOneAndStayInOneToFive() {
        val s = JointState().star(1, 9).star(2, 0).star(3, 4)
        assertEquals(listOf(5, 1, 4), listOf(s.of(1), s.of(2), s.of(3)))
        assertEquals(1, JointState().of(99))
    }

    @Test fun saveWritesOneAssessmentAndAnAnswerPerScoredCriterionAndNoDisabledOnes() {
        val r = JointCall.rubric(rubrics)!!
        var n = 0
        val recs = JointCall.records("visit", r, JointState().star(1, 5).star(3, 3), 42) { "u${n++}" }
        assertEquals(listOf("call_assessment", "call_assessment_answer", "call_assessment_answer", "call_assessment_answer"), recs.map { it.type })
        assertEquals(listOf(1, 2, 2, 2), recs.map { it.rank })
        assertEquals("9", recs[0].payload["total_score"].toString()) // 5 + 1 + 3
        assertEquals("15", recs[0].payload["max_score"].toString())
        assertEquals("42", recs[0].payload["assessed_user_id"].toString())
        assertTrue(recs.drop(1).all { it.payload["assessment_client_uuid"].toString() == "\"u0\"" })
    }

    @Test fun assignedSrIsTheSrWhoseRoutesIncludeTheRoute() {
        val sup = """{"team":[{"user_id":9,"full_name":"Late","role":"SR","route_ids":[5]},{"user_id":4,"full_name":"Rahim","role":"SR","route_ids":[5,6]},{"user_id":2,"full_name":"Tso","role":"TSO","route_ids":[5]}]}"""
        assertEquals(AssignedSr(4, "Rahim"), JointCall.assignedSr(sup, 5))
        assertNull(JointCall.assignedSr(sup, 77)); assertNull(JointCall.assignedSr(null, 5)); assertNull(JointCall.assignedSr("x", 5))
    }
}

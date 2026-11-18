package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeographyTest {
    @Test fun theUserSectionWithoutGeographyGivesNullSoTheCardShowsADash() {
        assertNull(Geography.parse("""{"user_id":1,"username":"sr1"}""")); assertNull(Geography.parse(null)); assertNull(Geography.parse("not json"))
    }

    @Test fun theServersShapeIsReadAndNamesFollowTheLanguage() {
        val g = Geography.parse("""{"user_id":1,"geography":{"wing":{"id":1,"code":"W1","name":"Dhaka Wing","name_bn":"ঢাকা উইং"},"division":{"id":2,"code":"D1","name":"Gulshan","name_bn":null},"territory":null,"zone":{"id":4,"code":"Z","name":"Zone 4","name_bn":"জোন ৪"}}}""")!!
        assertEquals("ঢাকা উইং", g.wing!!.text(UiLocale.BN)); assertEquals("Dhaka Wing", g.wing!!.text(UiLocale.EN))
        assertEquals("Gulshan", g.division!!.text(UiLocale.BN)) // no Bangla name: the English one
        assertNull(g.territory); assertEquals("Zone 4", g.zone!!.text(UiLocale.EN))
    }
}

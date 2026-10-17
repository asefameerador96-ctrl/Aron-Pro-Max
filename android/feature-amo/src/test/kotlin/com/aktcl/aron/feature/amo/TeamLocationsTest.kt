package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamLocationsTest {
    @Test fun parsesPinsAndKeepsAnSrWithoutAFix() {
        val j = """{"as_of":"2026-10-17T05:00:00Z","items":[
            {"user_id":2,"full_name":"Zed","route_ids":[5],"last_fix":{"lat":23.8,"lng":90.4,"at":"2026-10-17T04:30:00Z","source":"visit"}},
            {"user_id":1,"full_name":"Amin","route_ids":[],"last_fix":null}]}"""
        val p = TeamLocations.parse(j)
        assertEquals(listOf("Amin", "Zed"), p.map { it.name })
        assertNull(p[0].lat); assertNull(p[0].atMs)
        assertEquals(23.8, p[1].lat!!, 0.0); assertEquals("visit", p[1].source); assertEquals(1_792_211_400_000L, p[1].atMs)
    }

    @Test fun malformedIsEmpty() {
        assertTrue(TeamLocations.parse(null).isEmpty()); assertTrue(TeamLocations.parse("[").isEmpty()); assertTrue(TeamLocations.parse("""{"items":[{"user_id":1}]}""").isEmpty())
    }
}

package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** D-TARGET-MIN-01: working days of a month (Fridays and national holidays out, make-up days in) and revoking a declaration. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CalendarWorkingDaysTest {
    private lateinit var env: AdminEnv

    @BeforeAll fun setUp() { env = AdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private val admin get() = env.token("admin1001", Role.ADMIN)

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.month(m: YearMonth, tok: String = admin) = sendA1(HttpMethod.Get, "/admin/calendar/working-days?month=$m", tok)

    @Test
    fun fridaysAndNationalHolidaysAreOutAndMakeUpDaysAreIn() = env.app {
        // A month far ahead so declarations can be future-dated: 2029-02 has 28 days, Fridays on 2, 9, 16, 23.
        val m = YearMonth.of(2029, 2)
        val plain = month(m).objA1()
        assertEquals(HttpStatusCode.OK, month(m).status)
        assertEquals(24, plain.getValue("working_days").jsonPrimitive.content.toInt())
        val days = plain.getValue("days").jsonArray.map { it.jsonObject }
        assertEquals(28, days.size)
        assertEquals(listOf("2029-02-02", "2029-02-09", "2029-02-16", "2029-02-23"), days.filter { it.getValue("is_friday").jsonPrimitive.content == "true" }.map { it.strA1("date") })
        suspend fun decl(date: String, kind: String, scope: String = "global", id: Long = 0) =
            sendA1(HttpMethod.Post, "/admin/calendar/holidays", admin, """{"date":"$date","scope_type":"$scope","scope_id":$id,"kind":"$kind","name_en":"Entry $date","reason":"Working day test entry"}""")
        val h1 = decl("2029-02-05", "holiday"); assertEquals(HttpStatusCode.Created, h1.status)
        assertEquals(HttpStatusCode.Created, decl("2029-02-09", "holiday").status, "a holiday on a Friday is not counted twice")
        assertEquals(HttpStatusCode.Created, decl("2029-02-10", "makeup_day").status)  // Saturday make-up: working
        assertEquals(HttpStatusCode.Created, decl("2029-02-16", "makeup_day").status)  // a make-up on a Friday makes it a working day
        val zone = env.sql("SELECT id FROM app.zone WHERE code = 'Z-MIR'")!!.toLong()
        assertEquals(HttpStatusCode.Created, decl("2029-02-06", "holiday", "zone", zone).status)  // zone scope: not national
        val after = month(m).objA1()
        assertEquals(24, after.getValue("working_days").jsonPrimitive.content.toInt(), "-1 for Monday the 5th, +1 for the make-up Friday the 16th; the Friday holiday and the Saturday make-up change nothing")
        val byDate = after.getValue("days").jsonArray.map { it.jsonObject }.associateBy { it.strA1("date") }
        assertEquals("true", byDate.getValue("2029-02-05").getValue("is_holiday").jsonPrimitive.content)
        assertEquals("false", byDate.getValue("2029-02-05").getValue("is_working_day").jsonPrimitive.content)
        assertEquals("Entry 2029-02-05", byDate.getValue("2029-02-05").strA1("name_en"))
        assertEquals("false", byDate.getValue("2029-02-09").getValue("is_working_day").jsonPrimitive.content)
        assertEquals("true", byDate.getValue("2029-02-16").getValue("is_working_day").jsonPrimitive.content)
        assertEquals("true", byDate.getValue("2029-02-06").getValue("is_working_day").jsonPrimitive.content)
        // Revoke the Monday holiday: it works again; a second revoke is a conflict; the audit says why.
        val id = h1.objA1().lng("id")
        val rv = sendA1(HttpMethod.Post, "/admin/calendar/holidays/$id/revoke", admin, """{"change_reason":"Declared by mistake, withdrawn"}""")
        assertEquals(HttpStatusCode.OK, rv.status, rv.toString())
        assertEquals(1, env.audit("calendar_holiday", id, "revoke"))
        assertEquals(HttpStatusCode.Conflict, sendA1(HttpMethod.Post, "/admin/calendar/holidays/$id/revoke", admin, """{"change_reason":"Declared by mistake, withdrawn"}""").status)
        assertEquals(25, month(m).objA1().getValue("working_days").jsonPrimitive.content.toInt())
        // The same date and scope can be declared again after a revoke.
        assertEquals(HttpStatusCode.Created, decl("2029-02-05", "holiday").status)
    }

    @Test
    fun revokeRulesAndRoles() = env.app {
        val future = env.today.plusDays(40)
        val c = sendA1(HttpMethod.Post, "/admin/calendar/holidays", admin, """{"date":"$future","scope_type":"global","scope_id":0,"kind":"holiday","name_en":"Revoke me","reason":"Revoke rule test entry"}""")
        val id = c.objA1().lng("id")
        val body = """{"change_reason":"Withdrawn by the committee"}"""
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/calendar/holidays/$id/revoke", admin, """{"change_reason":"short"}""").status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/calendar/holidays/$id/revoke", env.token("tso1001", Role.TSO), body).status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/calendar/holidays/$id/revoke", env.token("support1001", Role.SUPPORT), body).status)
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Post, "/admin/calendar/holidays/99999999/revoke", admin, body).status)
        // An entry whose date is today or earlier cannot be revoked.
        val today = sendA1(HttpMethod.Post, "/admin/calendar/holidays", admin, """{"date":"${env.today}","scope_type":"global","scope_id":0,"kind":"emergency_off","name_en":"Off today","reason":"Emergency off-day test entry"}""")
        assertEquals("ERR_MASTER_EFFECTIVE_DATE_PAST", sendA1(HttpMethod.Post, "/admin/calendar/holidays/${today.objA1().lng("id")}/revoke", admin, body).code())
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/admin/calendar/holidays/$id/revoke", admin, body).status)
        // Working-days reads: readers yes, SR no; bad month 400.
        assertEquals(HttpStatusCode.OK, month(YearMonth.now(), env.token("tso1001", Role.TSO)).status)
        assertEquals(HttpStatusCode.Forbidden, month(YearMonth.now(), env.token("sr1001", Role.SR)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Get, "/admin/calendar/working-days?month=2029-2", admin).status)
        assertTrue(sendA1(HttpMethod.Get, "/admin/calendar/working-days", admin).objA1().strA1("month")!!.length == 7)
    }
}

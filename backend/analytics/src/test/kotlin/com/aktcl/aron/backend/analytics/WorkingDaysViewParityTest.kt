package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.WorkingCalendarRepo
import com.aktcl.aron.rules.NodePath
import kotlinx.datetime.toKotlinLocalDate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D-TSO-MAN-08 / DASH-04: `dw.v_calendar` and `dw.v_calendar_month` stay global with the Friday weekend (V0140 comment), and
 * for the global node they agree with the one count (WorkingDays through WorkingCalendarRepo) date by date and month by
 * month, over a year of declarations of every kind: holidays on weekdays and on Fridays, emergency off days, make-up days on
 * Fridays and on weekdays, a revoked holiday, and scoped declarations that neither may count for the global node.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkingDaysViewParityTest {
    private lateinit var fresh: FreshDb
    private val now = Instant.parse("2026-10-25T06:00:00Z")

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        fresh.dataSource.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    """
                    INSERT INTO app.calendar_holiday (date, scope_type, scope_id, kind, selling_day, name_en, revoked_at) VALUES
                      ('2026-02-21', 'global', 0, 'holiday', false, 'weekday holiday', NULL),
                      ('2026-03-27', 'global', 0, 'holiday', false, 'holiday on a Friday', NULL),
                      ('2026-03-26', 'global', 0, 'emergency_off', false, 'emergency off', NULL),
                      ('2026-04-03', 'global', 0, 'makeup_day', true, 'make-up Friday', NULL),
                      ('2026-04-06', 'global', 0, 'makeup_day', true, 'make-up on a weekday', NULL),
                      ('2026-05-12', 'global', 0, 'holiday', false, 'revoked holiday', now()),
                      ('2026-10-21', 'global', 0, 'holiday', false, 'Test holiday', NULL),
                      ('2026-10-30', 'global', 0, 'makeup_day', true, 'make-up Friday', NULL),
                      ('2026-10-12', 'zone', 7, 'holiday', false, 'zone holiday', NULL),
                      ('2026-10-16', 'division', 3, 'makeup_day', true, 'division make-up Friday', NULL),
                      ('2026-12-16', 'wing', 2, 'emergency_off', false, 'wing emergency off', NULL),
                      ('2026-12-16', 'territory', 5, 'holiday', false, 'territory holiday', NULL)
                    """,
                )
            }
        }
    }

    @AfterAll
    fun tearDown() = fresh.close()

    @Test
    fun theCalendarViewsEqualWorkingDaysForTheGlobalNode() {
        var checkedDates = 0
        fresh.db.jdbi.useHandle<Exception> { h ->
            for (m in 1..12) {
                val ym = YearMonth.of(2026, m)
                val cals = WorkingCalendarRepo.load(h, ym, now)   // no server config: the global weekend from the database (registry default [5])
                val cal = cals.at(NodePath.GLOBAL)
                val view = h.createQuery("SELECT business_date, is_working_day FROM dw.v_calendar WHERE business_date BETWEEN :f AND :t ORDER BY 1")
                    .bind("f", ym.atDay(1)).bind("t", ym.atEndOfMonth()).map { rs, _ -> rs.getObject(1, LocalDate::class.java) to rs.getBoolean(2) }.list()
                assertEquals(ym.lengthOfMonth(), view.size, "dw.dim_date covers $ym")
                for ((date, working) in view) {
                    assertEquals(working, cal.isWorking(date.toKotlinLocalDate()), "is_working_day of $date")
                    checkedDates++
                }
                val monthView = h.createQuery("SELECT working_days FROM dw.v_calendar_month WHERE month = :m").bind("m", ym.atDay(1)).mapTo(Int::class.java).one()
                assertEquals(monthView, cals.workingDays(NodePath.GLOBAL), "working_days of $ym")
                assertEquals(monthView, WorkingCalendar.workingDays(h, ym), "the legacy global count of $ym")
            }
            // The cases the declarations above pin, by hand: October 31 - 5 Fridays - the 21st + the 30th = 26 (the zone and division
            // declarations do not count); March 31 - 4 Fridays - the 26th (the 27th is a Friday already) = 26; April 30 - 4 Fridays + the
            // 3rd (a Friday) = 27 (the make-up Monday adds nothing); May: the revoked holiday is not off = 31 - 5 Fridays = 26.
            assertEquals(26, WorkingCalendarRepo.load(h, YearMonth.of(2026, 10), now).workingDays(NodePath.GLOBAL))
            assertEquals(26, WorkingCalendarRepo.load(h, YearMonth.of(2026, 3), now).workingDays(NodePath.GLOBAL))
            assertEquals(27, WorkingCalendarRepo.load(h, YearMonth.of(2026, 4), now).workingDays(NodePath.GLOBAL))
            assertEquals(26, WorkingCalendarRepo.load(h, YearMonth.of(2026, 5), now).workingDays(NodePath.GLOBAL))
        }
        assertTrue(checkedDates == 365, "every date of 2026 compared, was $checkedDates")
    }
}

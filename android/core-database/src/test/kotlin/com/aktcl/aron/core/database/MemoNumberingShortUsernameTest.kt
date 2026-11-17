package com.aktcl.aron.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.repo.MemoNumbering
import com.aktcl.aron.core.database.repo.MemoNumbers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Lead 2026-11-13: a memo username may have three characters (`^[a-z][a-z0-9]{2,31}$`). Through the real counter and Room: the number is
 * composed, counted per business date, kept in the stored memo number's shape, and the rule's edges still hold.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class MemoNumberingShortUsernameTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()

    @Test fun aThreeCharacterUsernameGetsNumberedMemos() = runTest {
        val numbers = MemoNumbers(db)
        val n = MemoNumbering("abc", 0, 500)
        assertEquals("abc-261113-001", numbers.reserve("2026-11-13", n))
        assertEquals("abc-261113-002", numbers.reserve("2026-11-13", n))
        assertEquals("a new business date starts again", "abc-261114-001", numbers.reserve("2026-11-14", n))
        // a second phone of the same login numbers from its own block (its counter is its own database; the block offset keeps the numbers apart)
        assertEquals(501, MemoNumbers.seqOf(1, MemoNumbering("abc", 1, 500)))
    }

    @Test fun theNumberKeepsTheShapeAndIsShorterThanTheLongestOneTheTicketAlreadyPrints() {
        val no = MemoNumbers.compose("a1b", "2026-11-13", 7)
        assertEquals("a1b-261113-007", no)
        // the printed memo and the Memo screen read the number as a whole or by its last part; nothing depends on the username's length
        assertEquals("007", no.substringAfterLast('-'))
        assertTrue(no.length < MemoNumbers.compose("sr334001", "2026-11-13", 7).length) // 14 against 18 characters: never a wider line
    }

    @Test fun theEdgesOfTheRuleStillHold() {
        MemoNumbering("abc", 0, 500); MemoNumbering("a" + "b".repeat(31), 0, 500) // 3 and 32 characters
        for (bad in listOf("ab", "a", "", "1bc", "Abc", "ab-c", "a" + "b".repeat(32), "ab c")) {
            assertThrows("'$bad' must be refused", IllegalArgumentException::class.java) { MemoNumbering(bad, 0, 500) }
        }
    }
}

package com.aktcl.aron.sr

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.entity.ConfigValueEntity
import com.aktcl.aron.core.database.repo.ReferenceRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** SRM-B-61: the programme tiles follow the three config flags; absent keys fall back to the registry defaults (14 tiles). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ProgrammeFlagsTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AronDatabase::class.java).allowMainThreadQueries().build()
    @After fun tearDown() = db.close()
    private val now = "2026-11-12T05:00:00.000Z"
    private fun cfg(key: String, json: String) = ConfigValueEntity(key = key, valueJson = json, scopeType = "global", scopeId = null, effectiveFrom = null, effectiveTo = null, configVersion = 1, requiresAck = false, scheduled = false)

    @Test fun absentKeysUseTheRegistryDefaultsSoThereAreFourteenTiles() = runTest {
        assertEquals(setOf("astha", "loyalty_point"), ProgrammeFlags.enabledTiles(ReferenceRepository(db), now)) // Match Program is off by default
    }

    @Test fun storedFlagsAreReadTrimmedUnquotedAndCaseInsensitive() = runTest {
        db.referenceDao().insertConfig(listOf(cfg(ProgrammeFlags.ASTHA, " \"FALSE\" "), cfg(ProgrammeFlags.LOYALTY, "true"), cfg(ProgrammeFlags.SUPERSTAR, "\"True\"")))
        assertEquals(setOf("loyalty_point", "match_program"), ProgrammeFlags.enabledTiles(ReferenceRepository(db), now))
    }

    @Test fun junkFallsBackToTheDefaultAndNeverGuesses() {
        assertEquals(setOf("astha", "loyalty_point"), ProgrammeFlags.tiles("maybe", "", "1"))
        assertEquals(emptySet<String>(), ProgrammeFlags.tiles("false", "false", "false"))
        assertEquals(setOf("astha", "loyalty_point", "match_program"), ProgrammeFlags.tiles("true", "TRUE", "true"))
    }
}

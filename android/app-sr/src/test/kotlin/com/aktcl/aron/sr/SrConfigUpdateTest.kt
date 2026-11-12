package com.aktcl.aron.sr

import com.aktcl.aron.core.sync.BundleOutcome
import com.aktcl.aron.feature.home.ConfigUpdate
import org.junit.Test
import org.junit.Assert.assertEquals

class SrConfigUpdateTest {
    @Test fun everyOutcomeMapsToOneAnswer() {
        val expected = mapOf(
            BundleOutcome.APPLIED to ConfigUpdate.UPDATED,
            BundleOutcome.PREFETCH_PROMOTED to ConfigUpdate.UPDATED,
            BundleOutcome.UNCHANGED to ConfigUpdate.ALREADY_CURRENT,
            BundleOutcome.OLDER_IGNORED to ConfigUpdate.ALREADY_CURRENT,
            BundleOutcome.PREFETCH_STORED to ConfigUpdate.ALREADY_CURRENT,
        )
        BundleOutcome.entries.forEach { assertEquals(it.name, expected[it] ?: ConfigUpdate.FAILED, configUpdateOf(it)) }
        assertEquals(ConfigUpdate.FAILED, configUpdateOf(null))
        assertEquals(ConfigUpdate.FAILED, configUpdateOf(BundleOutcome.AUTH_REQUIRED))
    }
}

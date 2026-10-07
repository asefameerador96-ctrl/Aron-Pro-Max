package com.aktcl.aron.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SYS-052 checker: A's run while B is signed in must not pull a bundle delta (it would go under B's FULL grant). */
class SessionSyncRunnerRulesTest {
    @Test fun onlyTheSignedInUsersRunPullsABundleDelta() {
        assertTrue(SessionSyncRunner.refreshesBundle(1001, 1001, SyncStop.DRAINED))
        assertTrue(SessionSyncRunner.refreshesBundle(1001, 1001, SyncStop.RUN_LIMIT))
        assertFalse(SessionSyncRunner.refreshesBundle(1001, 1002, SyncStop.DRAINED)) // A's rows, B signed in
        assertFalse(SessionSyncRunner.refreshesBundle(1001, null, SyncStop.DRAINED)) // nobody signed in
        assertFalse(SessionSyncRunner.refreshesBundle(1001, 1001, SyncStop.RETRY_LATER))
        assertFalse(SessionSyncRunner.refreshesBundle(1001, 1001, SyncStop.OFFLINE))
    }
}

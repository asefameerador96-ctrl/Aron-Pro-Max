package com.aktcl.aron.sr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.feature.outlet.PendingCluster
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PrefsPendingClustersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun clustersSurviveAReopenAndAreKeyedByClientUuid() {
        val first = PrefsPendingClusters(context, 77L)
        first.add(PendingCluster("u-1", "Lakepar", 5, "Agora GoliDaily", "2026-11-12T04:00:00.000Z"))
        first.add(PendingCluster("u-1", "Lakepar", 5, "Agora GoliDaily", "2026-11-12T04:00:01.000Z")) // a retried tap: one record
        first.add(PendingCluster("u-2", "Pink City", 5, "Agora GoliDaily", "2026-11-12T04:05:00.000Z"))
        val reopened = PrefsPendingClusters(context, 77L).all() // a new instance = a relaunch
        assertEquals(listOf("u-1", "u-2"), reopened.map { it.clientUuid })
        assertEquals("Lakepar", reopened.first().name)
        assertEquals("another user has none", emptyList<PendingCluster>(), PrefsPendingClusters(context, 78L).all())
    }

    @Test fun aDamagedStoreIsEmptyNotACrash() {
        context.getSharedPreferences("aron-clusters-79", Context.MODE_PRIVATE).edit().putString("pending", "{not json").commit()
        assertEquals(emptyList<PendingCluster>(), PrefsPendingClusters(context, 79L).all())
    }
}

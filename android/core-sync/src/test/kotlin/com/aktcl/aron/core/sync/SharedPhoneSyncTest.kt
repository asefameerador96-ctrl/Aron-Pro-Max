package com.aktcl.aron.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.contract.SyncTrigger
import com.aktcl.aron.core.common.WallClock
import com.aktcl.aron.core.database.AronDatabase
import com.aktcl.aron.core.database.UserDatabases
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.network.ApiOrigin
import com.aktcl.aron.core.network.AronApiClient
import com.aktcl.aron.core.network.ClientIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * F-SYS-052 (shared phone): user B signed in after A has a separate database file, sees none of A's rows, and A's rows
 * still upload, under A's own upload grant only (the session side is SessionRepositoryTest.whileBIsSignedIn...).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class SharedPhoneSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val fake = FakeIngestServer()
    private val bearerOf = ArrayList<Pair<String?, List<String>>>() // token -> client_uuids of the batch
    private val databases = UserDatabases(context) { null }

    private val auth = object : UploadAuth {
        override suspend fun token(userId: Long): String? = "upload-$userId"
        override suspend fun refresh(userId: Long, rejected: String?): Boolean = false
    }

    private object Clock : WallClock {
        override fun nowMs(): Long = 1_791_194_400_000L
        override fun elapsedRealtimeMs(): Long = 1_000_000L
    }

    @Before fun setUp() {
        listOf(A, B).forEach { context.deleteDatabase(AronDatabase.fileName(it)) }
        fake.validTokens = mutableSetOf("upload-$A", "upload-$B")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = fake.dispatch(request)
                fake.requests.lastOrNull()?.body?.let { body ->
                    bearerOf += request.headers["Authorization"]?.removePrefix("Bearer ") to
                        body["records"]!!.jsonArray.map { it.jsonObject["client_uuid"]!!.jsonPrimitive.content }
                }
                return response
            }
        }
        server.start()
    }

    @After fun tearDown() {
        listOf(A, B).forEach { databases.close(it); databases.allowOpen(it) }
        server.close()
    }

    private fun engine(userId: Long, db: AronDatabase): SyncEngine {
        val ok = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val client = AronApiClient(ApiOrigin.parse(server.url("/").toString().trimEnd('/'), allowCleartextLoopback = true), ok, ClientIdentity("1.0.3+10003") { DEVICE })
        return SyncEngine(userId, db, SyncBatchApi(client, null), auth, { DEVICE }, "1.0.3+10003", Clock, SyncPolicy(), random = Random(7))
    }

    @Test fun userBHasItsOwnDatabaseAndAsRowsUploadUnderAsTokenOnly() = runBlocking {
        val dbA = databases.of(A)
        val repo = CaptureRepository(dbA) { "2026-10-05T04:36:00.000Z" }
        val (visit, fix) = TestRows.visit(outletId = 50001L, seq = 1)
        repo.recordVisitOpen(visit, fix)
        repo.recordVisitClose(TestRows.close(visit.clientUuid))
        val unsentOfA = dbA.outboxDao().unsentCount()
        assertTrue(unsentOfA > 0)

        // B signs in on the same phone: a different file, an empty outbox, none of A's rows.
        val dbB = databases.of(B)
        assertNotEquals(AronDatabase.fileName(A), AronDatabase.fileName(B))
        assertEquals(0, dbB.outboxDao().unsentCount()) // Room creates the file on the first query
        assertTrue(context.getDatabasePath(AronDatabase.fileName(A)).exists() && context.getDatabasePath(AronDatabase.fileName(B)).exists())
        assertTrue(databases.knownUserIds().containsAll(listOf(A, B)))

        // B's run sends nothing; A's run (B still signed in) sends A's rows under A's grant.
        engine(B, dbB).run(SyncTrigger.MANUAL)
        assertTrue(bearerOf.isEmpty())
        val report = engine(A, dbA).run(SyncTrigger.CONNECTIVITY)
        assertEquals(SyncStop.DRAINED, report.stop)
        assertTrue(bearerOf.isNotEmpty())
        assertTrue(bearerOf.all { it.first == "upload-$A" })
        assertEquals(unsentOfA, bearerOf.flatMap { it.second }.distinct().size)
        assertEquals(0, dbA.outboxDao().unsentCount())
    }

    private companion object {
        const val A = 334001L
        const val B = 334002L
        const val DEVICE = "6f1c2d3e-4b5a-4c6d-8e7f-9a0b1c2d3e4f"
    }
}

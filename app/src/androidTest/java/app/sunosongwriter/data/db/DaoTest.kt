package app.sunosongwriter.data.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: AppDatabase
    private var backendId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        backendId = db.backendDao().insert(
            BackendEntity(
                presetId = "groq", name = "Groq", baseUrl = "https://api.groq.com/openai/v1", modelsUrl = null,
                auth = "BEARER", freeAccess = "KEY_TIER", isFreeTier = true, rpm = 30, rpd = 1000, tpm = null,
                modelLimitsJson = "[]", limitScope = "MODEL", limitsFreeModelsOnly = false, dailyResetZone = "UTC", streamUsage = true,
                dailyRemainingHeader = true, extraHeadersJson = "{}", keyHint = null, createdAt = 0,
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun requestCountsIncrementPerModelAndSumPerBackend() = runTest {
        val dao = db.requestCountDao()
        repeat(3) { dao.increment(backendId, "a", "2026-10-16") }
        dao.increment(backendId, "b", "2026-10-16")
        dao.increment(backendId, "a", "2026-10-15")

        assertEquals(3, dao.countForModel(backendId, "a", "2026-10-16"))
        assertEquals(4, dao.countForBackend(backendId, "2026-10-16"))
        dao.deleteBefore("2026-10-16")
        assertEquals(null, dao.countForModel(backendId, "a", "2026-10-15"))
    }

    @Test
    fun replaceListingMarksVanishedModelsUnavailable() = runTest {
        val dao = db.modelInfoDao()
        fun model(id: String, now: Long) = ModelInfoEntity(backendId, id, id, null, null, available = true, lastSeen = now)
        dao.replaceListing(backendId, listOf(model("a", 1), model("b", 1)))

        dao.replaceListing(backendId, listOf(model("a", 2)))

        val byId = dao.getForBackend(backendId).associateBy { it.modelId }
        assertTrue(byId.getValue("a").available)
        assertEquals(2, byId.getValue("a").lastSeen)
        assertFalse(byId.getValue("b").available)
    }

    @Test
    fun deletingABackendCascadesToModelsAndCounts() = runTest {
        db.modelInfoDao().replaceListing(backendId, listOf(ModelInfoEntity(backendId, "a", "a", null, null, true, 0)))
        db.requestCountDao().increment(backendId, "a", "2026-10-16")

        db.backendDao().delete(backendId)

        assertTrue(db.modelInfoDao().getForBackend(backendId).isEmpty())
        assertEquals(0, db.requestCountDao().countForBackend(backendId, "2026-10-16"))
    }
}

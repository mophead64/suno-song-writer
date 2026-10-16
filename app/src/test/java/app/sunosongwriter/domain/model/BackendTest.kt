package app.sunosongwriter.domain.model

import app.sunosongwriter.testBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendTest {
    @Test
    fun freeness() {
        assertTrue(testBackend(freeAccess = FreeAccess.ALL, isFreeTier = false).isFree("anything", null))

        val priced = testBackend(freeAccess = FreeAccess.PRICING)
        assertTrue(priced.isFree("x", pricedFree = true))
        assertFalse(priced.isFree("x", pricedFree = false))
        assertTrue(priced.isFree("vendor/model:free", pricedFree = null))
        assertFalse(priced.isFree("vendor/model", pricedFree = null))

        assertTrue(testBackend(freeAccess = FreeAccess.KEY_TIER, isFreeTier = true).isFree("x", null))
        assertFalse(testBackend(freeAccess = FreeAccess.KEY_TIER, isFreeTier = false).isFree("x", null))
    }

    @Test
    fun modelLimitRulesOverrideBackendLimits() {
        val backend = testBackend(limits = Limits(rpm = 10, rpd = 20)).copy(
            modelLimits = listOf(ModelLimitRule(match = "flash-lite", rpd = 500)),
        )

        assertEquals(Limits(rpm = 10, rpd = 500), backend.limitsFor("gemini-3.5-flash-lite"))
        assertEquals(Limits(rpm = 10, rpd = 20), backend.limitsFor("gemini-3.5-flash"))
    }

    @Test
    fun freeModelOnlyLimitsDoNotApplyToPaidModels() {
        val openRouter = testBackend(freeAccess = FreeAccess.PRICING, limits = Limits(rpm = 20, rpd = 50))
            .copy(limitsFreeModelsOnly = true)

        assertEquals(Limits(rpm = 20, rpd = 50), openRouter.limitsFor("vendor/model:free"))
        assertEquals(Limits(), openRouter.limitsFor("openai/gpt-4o-mini", pricedFree = false))
    }

    @Test
    fun limitKeyFollowsScope() {
        assertEquals("1", testBackend(limitScope = LimitScope.BACKEND).limitKey("m"))
        assertEquals("1/m", testBackend(limitScope = LimitScope.MODEL).limitKey("m"))
    }
}

package app.sunosongwriter.domain.usage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestPacerTest {
    private var now = 0L
    private val pacer = RequestPacer { now }

    private fun reserve(key: String = "b", rpm: Int? = 3) = runBlocking { pacer.reserve(key, rpm) }

    @Test
    fun noLimitNeverWaits() {
        repeat(100) { assertEquals(0, reserve(rpm = null)) }
    }

    @Test
    fun staggersBurstsBeyondTheLimit() {
        assertEquals(listOf(0L, 0L, 0L), List(3) { reserve() })
        assertEquals(60_000, reserve())
        assertEquals(60_000, reserve())
        assertEquals(60_000, reserve())
        assertEquals(120_000, reserve())
    }

    @Test
    fun slotsFreeUpAsTheWindowSlides() {
        repeat(3) { reserve() }
        now = 30_000
        assertEquals(30_000, reserve())
        now = 61_000
        assertEquals(0, reserve()) // the three requests at 0s have left the window; only the 60s booking remains
    }

    @Test
    fun keysAreIndependent() {
        repeat(3) { reserve(key = "a") }
        assertEquals(0, reserve(key = "b"))
    }
}

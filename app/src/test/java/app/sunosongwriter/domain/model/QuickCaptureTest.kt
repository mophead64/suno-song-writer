package app.sunosongwriter.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickCaptureTest {
    @Test
    fun appendsAsANewParagraph() {
        assertEquals("rain on a tin roof", QuickCapture.appendToConcept("  ", " rain on a tin roof\n"))
        assertEquals("first idea\n\nmore rain", QuickCapture.appendToConcept("first idea\n\n", "more rain"))
    }

    @Test
    fun titlesComeFromTheFirstLine() {
        assertEquals("Tin roof", QuickCapture.titleFrom("\n  Tin roof  \nsecond line"))
        assertEquals("Shared idea", QuickCapture.titleFrom("   "))
        assertEquals(58, QuickCapture.titleFrom("x".repeat(80)).length)
    }

    @Test
    fun findsSunoLinksOnly() {
        val text = "Listen to Tin Roof on Suno https://suno.com/s/AbC12. Also https://suno.com/song/9f2e and https://example.com/x"
        assertEquals(listOf("https://suno.com/s/AbC12", "https://suno.com/song/9f2e"), QuickCapture.sunoLinks(text))
        assertEquals(emptyList<String>(), QuickCapture.sunoLinks("no links here"))
    }
}

package app.sunosongwriter.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilterTest {
    private fun song(folderId: Long?, artist: String = "The Gutters", tags: List<String> = listOf("pop-punk")) =
        Song(1, folderId, "Tin Roof", artist, "", tags, 0, 0)

    @Test
    fun emptyFilterMatchesEverything() {
        assertTrue(LibraryFilter().matches(song(null)))
        assertFalse(LibraryFilter().isActive)
    }

    @Test
    fun folderFilterDistinguishesUnfiled() {
        assertTrue(LibraryFilter(folderId = 3).matches(song(3)))
        assertFalse(LibraryFilter(folderId = 3).matches(song(null)))
        assertTrue(LibraryFilter(folderId = LibraryFilter.UNFILED).matches(song(null)))
        assertFalse(LibraryFilter(folderId = LibraryFilter.UNFILED).matches(song(3)))
    }

    @Test
    fun artistAndTagFiltersIgnoreCase() {
        assertTrue(LibraryFilter(artist = "the gutters", tag = "POP-PUNK").matches(song(null)))
        assertFalse(LibraryFilter(tag = "metalcore").matches(song(null)))
        assertFalse(LibraryFilter(artist = "Someone else").matches(song(null)))
    }

    @Test
    fun searchQueryBecomesPrefixTermsAndStripsFtsSyntax() {
        assertEquals("rain* tin*", SearchQuery.toFts("Rain  TIN"))
        assertEquals("pop* punk*", SearchQuery.toFts("pop-punk"))
        assertEquals("don* t*", SearchQuery.toFts("don't"))
        // FTS operators are neutralised: lowercased "or"/"near" are ordinary terms in FTS4.
        assertEquals("über* or* near*", SearchQuery.toFts("\"Über\" OR NEAR("))
        assertNull(SearchQuery.toFts("*?! ()"))
    }
}

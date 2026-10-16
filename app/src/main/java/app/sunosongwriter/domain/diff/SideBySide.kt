package app.sunosongwriter.domain.diff

import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StyleSection

/** One row of a two-column view: a null side is a blank cell, where the line only exists on the other side. */
data class SideRow(val left: List<Segment>?, val right: List<Segment>?) {
    val changed: Boolean get() = left == null || right == null || left.any { it.kind != Segment.Kind.SAME } || right.any { it.kind != Segment.Kind.SAME }
}

data class SideBySideStyle(val section: StyleSection, val row: SideRow)

/** Two variants next to each other, with the differences marked (spec 5.15). */
data class SideBySideContent(
    val style: List<SideBySideStyle>,
    val lyrics: List<SideRow>,
    val settings: List<SettingDiff>,
)

object SideBySide {
    /** Every style section either side has, and every lyric line, aligned so changed lines sit opposite each other. */
    fun content(left: SongContent, right: SongContent): SideBySideContent {
        val style = StyleSection.renderOrder.mapNotNull { section ->
            val a = left.style[section]
            val b = right.style[section]
            when {
                a.isEmpty() && b.isEmpty() -> null
                a == b -> SideBySideStyle(section, SideRow(same(a), same(b)))
                a.isEmpty() -> SideBySideStyle(section, SideRow(null, listOf(Segment(b, Segment.Kind.ADDED))))
                b.isEmpty() -> SideBySideStyle(section, SideRow(listOf(Segment(a, Segment.Kind.REMOVED)), null))
                else -> {
                    val words = TextDiff.words(a, b)
                    SideBySideStyle(section, SideRow(words.filter { it.kind != Segment.Kind.ADDED }, words.filter { it.kind != Segment.Kind.REMOVED }))
                }
            }
        }
        val settings = left.settings.describe().zip(right.settings.describe()).map { (a, b) -> SettingDiff(a.first, a.second, b.second) }
        return SideBySideContent(style, lyrics(TextDiff.lines(left.lyrics, right.lyrics)), settings)
    }

    /** Runs of removed and added lines are paired up row by row, so a rewritten verse lines up with the original. */
    fun lyrics(lines: List<DiffLine>): List<SideRow> {
        val rows = mutableListOf<SideRow>()
        val removed = mutableListOf<String>()
        val added = mutableListOf<String>()
        fun flush() {
            for (i in 0 until maxOf(removed.size, added.size)) {
                rows += SideRow(
                    removed.getOrNull(i)?.let { listOf(Segment(it, Segment.Kind.REMOVED)) },
                    added.getOrNull(i)?.let { listOf(Segment(it, Segment.Kind.ADDED)) },
                )
            }
            removed.clear()
            added.clear()
        }
        for (line in lines) {
            when (line) {
                is DiffLine.Removed -> removed += line.text
                is DiffLine.Added -> added += line.text
                is DiffLine.Same -> { flush(); rows += SideRow(same(line.text), same(line.text)) }
                is DiffLine.Changed -> { flush(); rows += SideRow(line.oldSegments, line.newSegments) }
            }
        }
        flush()
        return rows
    }

    private fun same(text: String) = listOf(Segment(text, Segment.Kind.SAME))
}

package app.sunosongwriter.domain.diff

import app.sunosongwriter.domain.model.SongContent
import app.sunosongwriter.domain.model.StyleSection

/** One piece of a word-level diff. */
data class Segment(val text: String, val kind: Kind) {
    enum class Kind { SAME, ADDED, REMOVED }
}

/** One row of a line-level diff (spec 5.7). Changed lines carry word-level segments for highlighting. */
sealed interface DiffLine {
    data class Same(val text: String) : DiffLine
    data class Added(val text: String) : DiffLine
    data class Removed(val text: String) : DiffLine
    data class Changed(val old: String, val new: String, val oldSegments: List<Segment>, val newSegments: List<Segment>) : DiffLine
}

data class SectionDiff(val section: StyleSection, val old: String, val new: String, val segments: List<Segment>)

data class SettingDiff(val label: String, val old: String?, val new: String?)

data class ContentDiff(
    val style: List<SectionDiff>,
    val lyrics: List<DiffLine>,
    val settings: List<SettingDiff>,
) {
    val lyricsChanged: Boolean get() = lyrics.any { it !is DiffLine.Same }
    val isEmpty: Boolean get() = style.isEmpty() && !lyricsChanged && settings.isEmpty()
}

object TextDiff {
    /** Edit script between two sequences, via longest common subsequence. Fine for lyric-sized inputs. */
    fun <T> edits(a: List<T>, b: List<T>): List<Pair<Segment.Kind, T>> {
        val n = a.size
        val m = b.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = ArrayList<Pair<Segment.Kind, T>>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += Segment.Kind.SAME to a[i]; i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { out += Segment.Kind.REMOVED to a[i]; i++ }
                else -> { out += Segment.Kind.ADDED to b[j]; j++ }
            }
        }
        while (i < n) out += Segment.Kind.REMOVED to a[i++]
        while (j < m) out += Segment.Kind.ADDED to b[j++]
        return out
    }

    private val token = Regex("""\s+|[^\s]+""")

    /** Word-level diff; whitespace is kept as tokens so segments join back into the original text. */
    fun words(old: String, new: String): List<Segment> {
        val merged = mutableListOf<Segment>()
        for ((kind, text) in edits(token.findAll(old).map { it.value }.toList(), token.findAll(new).map { it.value }.toList())) {
            val last = merged.lastOrNull()
            if (last != null && last.kind == kind) merged[merged.lastIndex] = last.copy(text = last.text + text) else merged += Segment(text, kind)
        }
        return merged
    }

    /**
     * Line-level diff. Runs of removed lines followed by added lines are paired up as [DiffLine.Changed]
     * with word-level detail; leftovers stay as plain removals or additions.
     */
    fun lines(old: String, new: String): List<DiffLine> {
        val result = mutableListOf<DiffLine>()
        val removed = mutableListOf<String>()
        val added = mutableListOf<String>()
        fun flush() {
            val paired = minOf(removed.size, added.size)
            for (k in 0 until paired) {
                val words = words(removed[k], added[k])
                result += DiffLine.Changed(
                    old = removed[k],
                    new = added[k],
                    oldSegments = words.filter { it.kind != Segment.Kind.ADDED },
                    newSegments = words.filter { it.kind != Segment.Kind.REMOVED },
                )
            }
            removed.drop(paired).forEach { result += DiffLine.Removed(it) }
            added.drop(paired).forEach { result += DiffLine.Added(it) }
            removed.clear()
            added.clear()
        }
        for ((kind, line) in edits(splitLines(old), splitLines(new))) {
            when (kind) {
                Segment.Kind.SAME -> { flush(); result += DiffLine.Same(line) }
                Segment.Kind.REMOVED -> { if (added.isNotEmpty()) flush(); removed += line }
                Segment.Kind.ADDED -> added += line
            }
        }
        flush()
        return result
    }

    private fun splitLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.lines()

    /** Everything that changed between two versions' content: style per section, lyrics per line, settings per field. */
    fun content(old: SongContent, new: SongContent): ContentDiff {
        val style = StyleSection.entries.mapNotNull { section ->
            val a = old.style[section]
            val b = new.style[section]
            if (a == b) null else SectionDiff(section, a, b, words(a, b))
        }
        val settings = old.settings.describe().zip(new.settings.describe()).mapNotNull { (a, b) ->
            if (a.second == b.second) null else SettingDiff(a.first, a.second, b.second)
        }
        return ContentDiff(style, lines(old.lyrics, new.lyrics), settings)
    }
}

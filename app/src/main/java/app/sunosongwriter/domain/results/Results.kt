package app.sunosongwriter.domain.results

import app.sunosongwriter.domain.model.Snapshot

/** One Suno take of a snapshot (spec 5.12). Only the link is kept, never audio. */
data class Take(
    val id: Long,
    val snapshotId: Long,
    val index: Int,
    val link: String?,
    /** 1–5, or null when not rated. */
    val rating: Int?,
    val notes: String,
    /** Issue tags ([IssueFix.tag]). */
    val issues: List<String>,
    val createdAt: Long,
)

/** What a Fix from results step needs (spec 5.13): the snapshot, and its results written out for the prompt. */
data class FixContext(
    val snapshot: Snapshot,
    /** For the template's `{{results}}`. */
    val results: String,
    /** Labels of the issues it targets, in the catalog's order. */
    val issues: List<String>,
)

object ResultsBrief {
    /** Issue tags across all takes, in the catalog's order (unknown tags last). */
    fun issueTags(takes: List<Take>, catalog: List<IssueFix>): List<String> = inCatalogOrder(takes.flatMap { it.issues }.distinct(), catalog)

    private fun inCatalogOrder(tags: List<String>, catalog: List<IssueFix>): List<String> {
        val order = catalog.map { it.tag }
        return tags.sortedBy { t -> order.indexOf(t).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    /**
     * The `{{results}}` text: each take's rating, issues and notes, then the fix for every issue raised.
     * Tags with no fix text (a custom tag left blank) are listed without one.
     */
    fun describe(takes: List<Take>, catalog: List<IssueFix>): String = buildString {
        takes.sortedBy { it.index }.forEach { take ->
            append("Take ").append(take.index)
            take.rating?.let { append(" (rated ").append(it).append("/5)") }
            append(":\n")
            if (take.issues.isNotEmpty()) {
                append("Issues: ").append(inCatalogOrder(take.issues, catalog).joinToString(", ") { IssueCatalog.label(it, catalog) }).append('\n')
            }
            if (take.notes.isNotBlank()) append("Notes: ").append(take.notes.trim()).append('\n')
            if (take.issues.isEmpty() && take.notes.isBlank()) append("No issues noted.\n")
            append('\n')
        }
        val fixes = issueTags(takes, catalog).mapNotNull { tag ->
            val fix = catalog.firstOrNull { it.tag == tag }?.fixText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            "- ${IssueCatalog.label(tag, catalog)}: $fix"
        }
        if (fixes.isNotEmpty()) {
            append("Suggested fixes (from the Suno v6 guide and what has worked before):\n")
            fixes.forEach { append(it).append('\n') }
        }
    }.trimEnd()
}

/** "Consensus v5" → "Consensus": snapshots keep the label they were sent with. */
val Snapshot.variantName: String get() = versionLabel.substringBeforeLast(" v").ifBlank { versionLabel }

/** Which variant won for a song (spec 5.12). */
data class SongWinner(val songId: Long, val variantName: String, val snapshotId: Long?)

object SunoLinks {
    private val url = Regex("""https?://\S+""")

    /** The first link in pasted or shared text ("Listen to my song on Suno: https://suno.com/s/…"), else the trimmed text. */
    fun extract(text: String): String? {
        val trimmed = text.trim().ifEmpty { return null }
        return url.find(trimmed)?.value?.trimEnd('.', ',', ')', '"', '\'') ?: trimmed
    }

    fun isWebLink(link: String): Boolean = link.startsWith("http://") || link.startsWith("https://")
}

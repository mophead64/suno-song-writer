package app.sunosongwriter.domain.model

/** The steps from an idea to a finished Suno song, in order, as the song screen's timeline shows them. */
enum class SongStep(val label: String, val next: String) {
    IDEA("Idea", "Write down the idea: a line or two about what the song is."),
    TEAM("Team", "Set up your team first: the AI models that write and review songs."),
    DRAFT("Draft", "Get a first draft: Quick start writes lyrics and a style from the idea."),
    REFINE("Refine", "Refine it with your team: run a Writers' Room or single steps on the Team tab."),
    SEND("Suno", "Send it to Suno: copy the style and lyrics across and mark it sent."),
    RESULTS("Results", "Log what came back from Suno: rate the takes and tag any problems."),
}

/** What's done for a song, worked out from its data. */
data class SongProgress(val done: Set<SongStep>) {
    /** The first step not done yet, or null when the song has been through everything. */
    val next: SongStep? get() = SongStep.entries.firstOrNull { it !in done }

    companion object {
        fun of(
            hasIdea: Boolean,
            hasTeam: Boolean,
            hasDraft: Boolean,
            refined: Boolean,
            sent: Boolean,
            hasResults: Boolean,
        ): SongProgress = SongProgress(
            buildSet {
                if (hasIdea) add(SongStep.IDEA)
                if (hasTeam) add(SongStep.TEAM)
                if (hasDraft) add(SongStep.DRAFT)
                // Sending counts as finishing the earlier steps: plenty of songs are written by hand.
                if (refined || sent) add(SongStep.REFINE)
                if (sent) add(SongStep.SEND)
                if (hasResults) add(SongStep.RESULTS)
            },
        )

        /** Steps that change a song through the team (as opposed to the user's own edits). */
        val REFINING_STEPS = setOf("Implement", "Finalise", "Fix from results", "Style proposal", "Tweak")

        fun refined(variants: List<Variant>, versions: List<Version>): Boolean =
            variants.any { it.name.startsWith("Consensus") } || versions.any { it.step in REFINING_STEPS }
    }
}

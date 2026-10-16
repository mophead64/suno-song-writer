package app.sunosongwriter.domain.model

enum class Role(val label: String) {
    LYRICIST("Lyricist"),
    STYLIST("Stylist"),
    REVIEWER("Reviewer"),
    IMPLEMENTER("Implementer"),
    FINALISER("Finaliser"),
}

/** A model taking part in sessions, with a display identity and fallbacks (spec 5.1, 5.2). */
data class Participant(
    val id: Long,
    val name: String,
    val avatar: String,
    val defaultRole: Role?,
    val model: ModelRef?,
    /** Tried in order when [model] is rate-limited, out of quota or unavailable (used from iteration 4). */
    val fallbacks: List<ModelRef>,
)

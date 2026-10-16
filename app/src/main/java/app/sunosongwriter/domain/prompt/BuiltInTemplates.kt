package app.sunosongwriter.domain.prompt

/**
 * The read-only templates that ship with the app (spec 5.8). Editing one makes a user copy.
 *
 * Replies that change the song use one reply format ([CONTENT_FORMAT]), which
 * [app.sunosongwriter.domain.review.ReplyParser] reads; reviews reply in JSON, read by
 * [app.sunosongwriter.domain.review.ReviewParser]. Both parsers are tolerant, because free and
 * relayed models don't always follow instructions exactly.
 */
object BuiltInTemplates {
    private const val SONG = """Song: "{{title}}" by {{artist}}
Genre tags: {{tags}}
Concept:
{{concept}}"""

    private const val CURRENT = """Current style prompt:
{{style}}

Current lyrics:
{{lyrics}}

Current Suno settings:
{{sliders}}"""

    private const val SUNO_RULES = """Suno conventions to follow:
- Style prompt: describe the finished recording, in this order, one labelled line each: Genre, Vocals, Drums, Guitars, Bass, Other Instruments, Arrangement, Production, Ending. Describe what each part is doing (use verbs), not just which instruments are present.
- Lyrics: short section tags in square brackets on their own lines, e.g. [Intro], [Verse], [Pre-Chorus], [Chorus], [Bridge], [Final Chorus]. Keep bracket tags short. Lyric lines themselves are never in brackets: Suno doesn't sing bracketed text.
- Suno SINGS anything in (parentheses): use them only for backing vocals and ad-libs. Never put directions, descriptions or notes in the lyrics; describe the performance in the style prompt instead.
- Don't leave the intro undefined: start the vocals straight away, or use [Instrumental Intro].
- After the last vocal section, end with [Instrumental Outro], [Hard Stop] and [End] so the song doesn't cut off mid-lyric."""

    const val CONTENT_FORMAT = """Reply in exactly this format, with these headings on their own lines:
STYLE:
<the complete style prompt>
LYRICS:
<the complete lyrics>
SETTINGS:
Weirdness: <0-100>%
Style Influence: <0-100>%
NOTE:
<one line describing what you changed>"""

    val WRITE_LYRICS = PromptTemplate(
        key = TemplateKey.builtIn("write-lyrics"),
        action = SessionAction.WRITE_LYRICS,
        name = "Write lyrics",
        body = """You are a professional songwriter writing lyrics for a song that will be generated with Suno.

$SONG

Current style prompt (may be empty):
{{style}}

Extra instructions: {{instructions}}

$SUNO_RULES

Write complete, singable lyrics with strong hooks.

Reply in exactly this format, with these headings on their own lines:
LYRICS:
<the complete lyrics>
NOTE:
<one line describing what you wrote>""",
    )

    val WRITE_STYLE = PromptTemplate(
        key = TemplateKey.builtIn("write-style"),
        action = SessionAction.WRITE_STYLE,
        name = "Write style",
        body = """You are a record producer writing the Style prompt for a song that will be generated with Suno.

$SONG

Current lyrics (may be empty):
{{lyrics}}

Extra instructions: {{instructions}}

$SUNO_RULES
- Include a production clause, e.g. "high-fidelity studio production, close-mic vocals, crisp transients, clear instrument separation, stable loudness".
- Ending line: "complete every written lyric before a short instrumental outro and final hard stop".
- One main musical identity plus two or three interesting influences works better than many genres.
- Keep the whole style prompt under about 1,000 characters.

Reply in exactly this format, with these headings on their own lines:
STYLE:
<the complete style prompt>
SETTINGS:
Weirdness: <0-100>%
Style Influence: <0-100>%
NOTE:
<one line describing the sound>""",
    )

    val REVIEW = PromptTemplate(
        key = TemplateKey.builtIn("review"),
        action = SessionAction.REVIEW,
        name = "Review",
        body = """You are one member of a songwriting team reviewing a song before it is generated with Suno. Suggest specific, concrete improvements.

$SONG

$CURRENT

Extra instructions: {{instructions}}

$SUNO_RULES

Reply with ONLY a JSON object and no other text:
{"verdict": "approve" or "changes", "summary": "one sentence", "suggestions": [{"target": "lyrics" or "style" or "settings", "section": "e.g. Chorus, Verse 2, Vocals, Weirdness", "change": "the exact change to make", "rationale": "why it helps"}]}

Use "approve" with an empty suggestions list if the song is ready. Give at most 6 suggestions, most important first.""",
    )

    val IMPLEMENT = PromptTemplate(
        key = TemplateKey.builtIn("implement"),
        action = SessionAction.IMPLEMENT,
        name = "Implement",
        body = """You are the songwriting team's editor. Apply the accepted changes below to the song, and change nothing else.

$SONG

$CURRENT

Accepted changes to apply:
{{suggestions}}

$SUNO_RULES

Always give the complete style prompt and the complete lyrics, not just the parts you changed.

$CONTENT_FORMAT""",
    )

    val TWEAK = PromptTemplate(
        key = TemplateKey.builtIn("tweak"),
        action = SessionAction.TWEAK,
        name = "Tweak",
        body = """You are a songwriter making a small, targeted change to a song for Suno.

$SONG

$CURRENT

Change requested: {{instructions}}

Make only that change. Keep everything else as it is.

$CONTENT_FORMAT""",
    )

    val FINALISE = PromptTemplate(
        key = TemplateKey.builtIn("finalise"),
        action = SessionAction.FINALISE,
        name = "Finalise (Suno v6 guide)",
        body = """You are refining a finished song so it generates well in Suno v6. Rewrite the style prompt and adjust the lyric tags and settings to follow the guide below. Keep the song's words, story and sound; improve how they are described to Suno.

$SONG

$CURRENT

Extra instructions: {{instructions}}

=== SUNO v6 PROMPTING GUIDE ===
{{guide}}
=== END OF GUIDE ===

$CONTENT_FORMAT""",
    )

    val FIX = PromptTemplate(
        key = TemplateKey.builtIn("fix"),
        action = SessionAction.FIX,
        name = "Fix from results",
        body = """This song was generated in Suno and the results had problems. Change the style prompt, lyric tags and settings to fix them, applying the suggested fixes below. Keep the song's words, story and sound, and change nothing that isn't needed for the fixes.

$SONG

What was sent to Suno:
$CURRENT

What came back from Suno:
{{results}}

Extra instructions: {{instructions}}

$SUNO_RULES

Always give the complete style prompt and the complete lyrics, not just the parts you changed.

$CONTENT_FORMAT""",
    )

    val QUICK_START = PromptTemplate(
        key = TemplateKey.builtIn("quick-start"),
        action = SessionAction.QUICK_START,
        name = "Quick start",
        body = """You are a songwriter and record producer turning an idea into a complete first draft of a song that will be generated with Suno. The team will refine it afterwards, so make it a strong, complete starting point.

The idea:
{{concept}}

Title so far: {{title}}
Artist: {{artist}}
Genre tags so far: {{tags}}

Extra instructions: {{instructions}}

$SUNO_RULES
- Include a production clause in the style, e.g. "high-fidelity studio production, close-mic vocals, crisp transients, clear instrument separation, stable loudness".
- One main musical identity plus two or three interesting influences works better than many genres.
- Keep the whole style prompt under about 1,000 characters.

Reply in exactly this format, with these headings on their own lines:
TITLE:
<a short, memorable song title>
TAGS:
<two to four genre tags, comma-separated>
STYLE:
<the complete style prompt>
LYRICS:
<the complete lyrics>
SETTINGS:
Weirdness: <0-100>%
Style Influence: <0-100>%
NOTE:
<one line describing the song>""",
    )

    val CHAIR = PromptTemplate(
        key = TemplateKey.builtIn("chair"),
        action = SessionAction.CHAIR,
        name = "Chair decision",
        body = """You are chairing a songwriting team's debate about a song that will be generated with Suno. Make the decision for the team, the way a good producer would: keep what serves the song, drop what doesn't.

$SONG

$CURRENT

Extra instructions: {{instructions}}

{{suggestions}}""",
    )

    val all: List<PromptTemplate> = listOf(QUICK_START, WRITE_LYRICS, WRITE_STYLE, REVIEW, IMPLEMENT, TWEAK, FINALISE, FIX, CHAIR)

    fun forAction(action: SessionAction): PromptTemplate = all.first { it.action == action }

    fun find(key: TemplateKey): PromptTemplate? = all.firstOrNull { it.key == key }
}

package app.sunosongwriter.ui.song

import app.sunosongwriter.AppContainer
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.workflow.RunException
import app.sunosongwriter.domain.workflow.RunRequest
import kotlinx.coroutines.flow.first

/** The outcome of Quick generate: the new song, or why the draft couldn't start. */
data class QuickGenerated(val songId: Long?, val error: String?)

/**
 * Quick generate: a new song whose concept is the idea, drafted by Quick start. If the step can't
 * start (e.g. the participant has no model), the empty song is removed again and the error returned.
 */
suspend fun quickGenerate(c: AppContainer, request: QuickStartRequest, folderId: Long? = null): QuickGenerated {
    val songId = c.songs.createSong(title = "", artist = request.artist, concept = request.idea, tags = emptyList(), folderId = folderId)
    val variantId = c.songs.observeVariants(songId).first().first().id
    return try {
        c.runner.start(RunRequest(songId, variantId, SessionAction.QUICK_START, request.participantId, instructions = request.instructions))
        QuickGenerated(songId, null)
    } catch (e: RunException) {
        c.songs.deleteSong(songId)
        QuickGenerated(null, e.message)
    }
}

package app.sunosongwriter.data.repo

import app.sunosongwriter.data.db.PromptTemplateEntity
import app.sunosongwriter.data.db.TemplateDao
import app.sunosongwriter.data.db.TemplateDefaultEntity
import app.sunosongwriter.domain.prompt.BuiltInTemplates
import app.sunosongwriter.domain.prompt.PromptTemplate
import app.sunosongwriter.domain.prompt.SessionAction
import app.sunosongwriter.domain.prompt.TemplateKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class TemplateDefault(val action: SessionAction, val participantId: Long, val key: TemplateKey) {
    val isGlobal: Boolean get() = participantId == GLOBAL

    companion object {
        const val GLOBAL = 0L
    }
}

/** Built-in and user templates, and which one each action uses by default (spec 5.8). */
class TemplateRepository(
    private val dao: TemplateDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** Built-ins first, then the user's, grouped by action. */
    val templates: Flow<List<PromptTemplate>> = dao.observeAll().map { rows -> BuiltInTemplates.all + rows.map { it.toDomain() } }

    val defaults: Flow<List<TemplateDefault>> = dao.observeDefaults().map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun get(key: TemplateKey): PromptTemplate? =
        BuiltInTemplates.find(key) ?: key.userId?.let { dao.get(it) }?.toDomain()

    /** The template to use: the participant's default, else everyone's default, else the built-in. */
    suspend fun resolve(action: SessionAction, participantId: Long?): PromptTemplate {
        val defaults = dao.defaults().mapNotNull { it.toDomain() }.filter { it.action == action }
        val key = participantId?.let { id -> defaults.firstOrNull { it.participantId == id }?.key }
            ?: defaults.firstOrNull { it.isGlobal }?.key
        return key?.let { get(it) }?.takeIf { it.action == action } ?: BuiltInTemplates.forAction(action)
    }

    /** Copies any template into a new, editable user template. */
    suspend fun copy(key: TemplateKey): TemplateKey? {
        val source = get(key) ?: return null
        val now = nowMillis()
        val id = dao.insert(PromptTemplateEntity(action = source.action.name, name = "${source.name} (my copy)", body = source.body, basedOn = key.value, createdAt = now, updatedAt = now))
        return TemplateKey.user(id)
    }

    suspend fun create(action: SessionAction, name: String, body: String): TemplateKey {
        val now = nowMillis()
        return TemplateKey.user(dao.insert(PromptTemplateEntity(action = action.name, name = name, body = body, basedOn = null, createdAt = now, updatedAt = now)))
    }

    /** Built-ins are read-only; this only updates user templates. */
    suspend fun save(template: PromptTemplate) {
        val id = template.key.userId ?: return
        val row = dao.get(id) ?: return
        dao.update(row.copy(action = template.action.name, name = template.name.trim(), body = template.body, updatedAt = nowMillis()))
    }

    suspend fun delete(key: TemplateKey) {
        val id = key.userId ?: return
        dao.clearDefaultsFor(key.value)
        dao.delete(id)
    }

    suspend fun setDefault(action: SessionAction, participantId: Long, key: TemplateKey) =
        dao.setDefault(TemplateDefaultEntity(action.name, participantId, key.value))

    suspend fun clearDefault(action: SessionAction, participantId: Long) = dao.clearDefault(action.name, participantId)

    private fun PromptTemplateEntity.toDomain() = PromptTemplate(
        key = TemplateKey.user(id),
        action = SessionAction.entries.firstOrNull { it.name == action } ?: SessionAction.TWEAK,
        name = name,
        body = body,
        basedOn = basedOn?.let(::TemplateKey),
    )

    private fun TemplateDefaultEntity.toDomain(): TemplateDefault? =
        SessionAction.entries.firstOrNull { it.name == action }?.let { TemplateDefault(it, participantId, TemplateKey(templateKey)) }
}

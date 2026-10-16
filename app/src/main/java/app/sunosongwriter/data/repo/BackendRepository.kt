package app.sunosongwriter.data.repo

import app.sunosongwriter.data.db.BackendDao
import app.sunosongwriter.data.db.ModelInfoDao
import app.sunosongwriter.data.db.ModelInfoEntity
import app.sunosongwriter.data.llm.Endpoint
import app.sunosongwriter.data.llm.LlmException
import app.sunosongwriter.data.llm.OpenRouterCredit
import app.sunosongwriter.data.llm.OpenAiCompatibleClient
import app.sunosongwriter.data.llm.RemoteModel
import app.sunosongwriter.data.llm.ResolvedTarget
import app.sunosongwriter.data.llm.TargetResolver
import app.sunosongwriter.data.presets.BackendPreset
import app.sunosongwriter.data.secure.SecretStore
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.ModelInfo
import app.sunosongwriter.domain.model.ModelRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class BackendRepository(
    private val backendDao: BackendDao,
    private val modelDao: ModelInfoDao,
    private val secrets: SecretStore,
    private val client: OpenAiCompatibleClient,
    json: Json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : TargetResolver {
    private val mappers = Mappers(json)

    val backends: Flow<List<Backend>> = backendDao.observeAll().map { list -> list.map(mappers::toDomain) }

    val allModels: Flow<List<ModelInfo>> = modelDao.observeAll().map { list -> list.map(mappers::toDomain) }

    fun observe(id: Long): Flow<Backend?> = backendDao.observe(id).map { it?.let(mappers::toDomain) }

    fun observeModels(backendId: Long): Flow<List<ModelInfo>> =
        modelDao.observeForBackend(backendId).map { list -> list.map(mappers::toDomain) }

    suspend fun get(id: Long): Backend? = backendDao.get(id)?.let(mappers::toDomain)

    fun draftFrom(preset: BackendPreset): Backend = mappers.fromPreset(preset)

    /**
     * Inserts or updates [backend]. [newKey] replaces the stored API key when non-null;
     * an empty string removes it. Returns the backend's id.
     */
    suspend fun save(backend: Backend, newKey: String?): Long {
        val keyHint = when {
            newKey == null -> backend.keyHint
            newKey.isBlank() -> null
            else -> SecretStore.hintFor(newKey)
        }
        val toSave = backend.copy(keyHint = keyHint)
        val id = if (backend.isNew) {
            backendDao.insert(mappers.toEntity(toSave, nowMillis()))
        } else {
            val createdAt = backendDao.get(backend.id)?.createdAt ?: nowMillis()
            backendDao.update(mappers.toEntity(toSave, createdAt))
            backend.id
        }
        when {
            newKey == null -> Unit
            newKey.isBlank() -> secrets.remove(SecretStore.backendKey(id))
            else -> secrets.put(SecretStore.backendKey(id), newKey.trim())
        }
        return id
    }

    suspend fun delete(id: Long) {
        backendDao.delete(id)
        secrets.remove(SecretStore.backendKey(id))
    }

    suspend fun apiKey(backendId: Long): String? = secrets.get(SecretStore.backendKey(backendId))

    fun endpoint(backend: Backend, apiKey: String?) = Endpoint(
        baseUrl = backend.baseUrl,
        apiKey = apiKey,
        modelsUrl = backend.modelsUrl,
        streamUsage = backend.streamUsage,
        extraHeaders = backend.extraHeaders,
    )

    /**
     * Lists models using unsaved settings, for the "Test connection" button.
     * [apiKey] null means "use the saved key".
     */
    suspend fun testConnection(backend: Backend, apiKey: String?): Result<List<RemoteModel>> {
        if (backend.isRelay) return Result.success(listOf(relayModel(backend)))
        val key = apiKey?.takeIf { it.isNotBlank() } ?: if (backend.isNew) null else apiKey(backend.id)
        return runLlm { client.listModels(endpoint(backend, key)) }
    }

    /** Fetches the model list and applies it to the cache, marking vanished models unavailable. */
    suspend fun refreshModels(backendId: Long): Result<Int> {
        val backend = get(backendId) ?: return Result.failure(IllegalArgumentException("No backend $backendId"))
        val listing = if (backend.isRelay) Result.success(listOf(relayModel(backend))) else runLlm { client.listModels(endpoint(backend, apiKey(backendId))) }
        return listing.map { remote ->
            val now = nowMillis()
            modelDao.replaceListing(
                backendId,
                remote.map {
                    ModelInfoEntity(backendId, it.id, it.displayName, it.pricedFree, it.contextLength, available = true, lastSeen = now)
                },
            )
            remote.size
        }
    }

    /** Refreshes every backend, ignoring failures (e.g. Ollama not running). Called on launch. */
    suspend fun refreshAllModels() {
        backendDao.getAll().forEach { refreshModels(it.id) }
    }

    /** Remaining OpenRouter credit (spec 5.17). Other providers have no API for it, so callers show "not available". */
    suspend fun openRouterCredit(backendId: Long): Result<OpenRouterCredit> {
        val backend = get(backendId) ?: return Result.failure(IllegalArgumentException("No backend $backendId"))
        return runLlm { client.openRouterCredit(endpoint(backend, apiKey(backendId))) }
    }

    /** A relay backend has one "model": the chat app itself. */
    private fun relayModel(backend: Backend) = RemoteModel(RELAY_MODEL_ID, backend.name, pricedFree = true, contextLength = null)

    override suspend fun resolve(ref: ModelRef): ResolvedTarget? {
        val backend = get(ref.backendId) ?: return null
        val model = modelDao.get(ref.backendId, ref.modelId)?.let(mappers::toDomain)
        return ResolvedTarget(backend, model, endpoint(backend, apiKey(backend.id)))
    }

    companion object {
        const val RELAY_MODEL_ID = "chat"
        const val OPENROUTER_PRESET = "openrouter"
    }

    private inline fun <T> runLlm(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: LlmException) {
        Result.failure(e)
    }
}

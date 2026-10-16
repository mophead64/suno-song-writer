package app.sunosongwriter.ui.backends

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.sunosongwriter.data.llm.LlmException
import app.sunosongwriter.data.presets.BackendPreset
import app.sunosongwriter.data.presets.PresetRepository
import app.sunosongwriter.data.repo.BackendRepository
import app.sunosongwriter.domain.model.Backend
import app.sunosongwriter.domain.model.Limits
import app.sunosongwriter.domain.model.ModelInfo
import app.sunosongwriter.ui.BackendEditRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BackendForm(
    val name: String,
    val baseUrl: String,
    val isFreeTier: Boolean,
    val rpm: String,
    val rpd: String,
    val tpm: String,
    /** Typed key; blank means "keep the saved key". */
    val keyInput: String = "",
    /** Relay backends: the chat app's package name. */
    val relayPackage: String = "",
)

data class Status(val message: String, val isError: Boolean)

data class BackendEditState(
    val draft: Backend? = null,
    val preset: BackendPreset? = null,
    val form: BackendForm? = null,
    val busy: Boolean = false,
    val status: Status? = null,
    val deleted: Boolean = false,
) {
    val isNew: Boolean get() = draft?.isNew ?: true
    val urlLooksValid: Boolean get() = form?.baseUrl?.trim()?.let { it.startsWith("http://") || it.startsWith("https://") } == true
    val canSave: Boolean get() = if (draft?.isRelay == true) form?.relayPackage?.isNotBlank() == true else urlLooksValid
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackendEditViewModel(
    savedState: SavedStateHandle,
    private val backends: BackendRepository,
    private val presets: PresetRepository,
) : ViewModel() {
    private val route = savedState.toRoute<BackendEditRoute>()
    private val _state = MutableStateFlow(BackendEditState())
    val state: StateFlow<BackendEditState> = _state.asStateFlow()

    val models: StateFlow<List<ModelInfo>> = _state
        .map { it.draft?.id ?: 0L }
        .flatMapLatest { id -> if (id == 0L) flowOf(emptyList()) else backends.observeModels(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val draft = if (route.backendId != 0L) backends.get(route.backendId) else presets.find(route.presetId)?.let(backends::draftFrom)
            val preset = presets.find(draft?.presetId)
            _state.value = BackendEditState(draft = draft, preset = preset, form = draft?.let(::formFor))
        }
    }

    private fun formFor(b: Backend) = BackendForm(
        name = b.name,
        baseUrl = b.baseUrl,
        isFreeTier = b.isFreeTier,
        rpm = b.limits.rpm?.toString().orEmpty(),
        rpd = b.limits.rpd?.toString().orEmpty(),
        tpm = b.limits.tpm?.toString().orEmpty(),
        relayPackage = b.relayPackage.orEmpty(),
    )

    fun edit(change: (BackendForm) -> BackendForm) {
        _state.update { s -> s.copy(form = s.form?.let(change), status = null) }
    }

    private fun currentDraft(): Backend? {
        val s = _state.value
        val draft = s.draft ?: return null
        val form = s.form ?: return draft
        return draft.copy(
            name = form.name.ifBlank { s.preset?.name ?: "Backend" },
            baseUrl = form.baseUrl.trim().trimEnd('/'),
            isFreeTier = form.isFreeTier,
            limits = Limits(form.rpm.toPositiveIntOrNull(), form.rpd.toPositiveIntOrNull(), form.tpm.toPositiveIntOrNull()),
            relayPackage = form.relayPackage.trim().ifEmpty { null },
        )
    }

    fun testConnection() {
        val draft = currentDraft() ?: return
        val key = _state.value.form?.keyInput?.takeIf { it.isNotBlank() }
        runBusy {
            backends.testConnection(draft, key).fold(
                onSuccess = { models ->
                    val free = models.count { draft.isFree(it.id, it.pricedFree) }
                    Status("Connected. ${models.size} models found, $free free.", isError = false)
                },
                onFailure = { Status(errorText(it), isError = true) },
            )
        }
    }

    /** Saves, then refreshes the model list so pickers have something to show. */
    fun save() {
        val draft = currentDraft() ?: return
        val key = _state.value.form?.keyInput?.takeIf { it.isNotBlank() }
        runBusy {
            val id = backends.save(draft, key)
            val saved = backends.get(id)
            _state.update { it.copy(draft = saved, form = it.form?.copy(keyInput = "")) }
            backends.refreshModels(id).fold(
                onSuccess = { Status("Saved. $it models listed.", isError = false) },
                onFailure = { Status("Saved, but couldn't list models: ${errorText(it)}", isError = true) },
            )
        }
    }

    fun refreshModels() {
        val id = _state.value.draft?.id?.takeIf { it != 0L } ?: return
        runBusy {
            backends.refreshModels(id).fold(
                onSuccess = { Status("$it models listed.", isError = false) },
                onFailure = { Status(errorText(it), isError = true) },
            )
        }
    }

    fun removeKey() {
        val draft = _state.value.draft?.takeIf { !it.isNew } ?: return
        runBusy {
            backends.save(draft, newKey = "")
            _state.update { it.copy(draft = backends.get(draft.id)) }
            Status("Key removed.", isError = false)
        }
    }

    fun localNetworkDenied() {
        _state.update {
            it.copy(status = Status("Local network access was refused, so this device can't reach local servers. Allow it in the app's settings under Nearby devices.", isError = true))
        }
    }

    fun delete() {
        val id = _state.value.draft?.id?.takeIf { it != 0L } ?: return
        viewModelScope.launch {
            backends.delete(id)
            _state.update { it.copy(deleted = true) }
        }
    }

    private fun runBusy(block: suspend () -> Status) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, status = null) }
        viewModelScope.launch {
            val status = try {
                block()
            } catch (e: Exception) {
                Status(errorText(e), isError = true)
            }
            _state.update { it.copy(busy = false, status = status) }
        }
    }

    private fun errorText(e: Throwable): String = (e as? LlmException)?.error?.message ?: e.message ?: e.javaClass.simpleName

    private fun String.toPositiveIntOrNull(): Int? = trim().toIntOrNull()?.takeIf { it > 0 }
}

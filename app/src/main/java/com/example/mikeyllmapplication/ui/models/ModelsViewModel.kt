package com.example.mikeyllmapplication.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mikeyllmapplication.AppContainer
import com.example.mikeyllmapplication.llm.BackendChoice
import com.example.mikeyllmapplication.llm.DownloadState
import com.example.mikeyllmapplication.llm.EngineState
import com.example.mikeyllmapplication.llm.ModelCatalog
import com.example.mikeyllmapplication.llm.ModelSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelRow(
    val spec: ModelSpec,
    val installedPath: String?,
    val partialBytes: Long,
    val download: DownloadState,
    val downloading: Boolean,
    val active: Boolean,
    /** False for files pushed to /data/local/tmp, which the app cannot delete. */
    val deletable: Boolean,
)

class ModelsViewModel(private val c: AppContainer) : ViewModel() {

    /** Bumped to force re-reading the file system after delete / adb push. */
    private val tick = MutableStateFlow(0)

    val engineState: StateFlow<EngineState> = c.llm.state

    private val _backend = MutableStateFlow(c.settings.backend)
    val backend: StateFlow<BackendChoice> = _backend.asStateFlow()

    private val _hfToken = MutableStateFlow(c.settings.hfToken)
    val hfToken: StateFlow<String> = _hfToken.asStateFlow()

    val downloadDir: String = c.models.downloadDir.absolutePath

    val rows: StateFlow<List<ModelRow>> = combine(
        c.models.downloads, c.activeDownloads, c.llm.state, tick,
    ) { downloads, active, engine, _ ->
        ModelCatalog.models.map { spec ->
            val file = c.models.findModelFile(spec)
            ModelRow(
                spec = spec,
                installedPath = file?.absolutePath,
                deletable = file?.parentFile == c.models.downloadDir,
                partialBytes = c.models.partialBytes(spec),
                download = downloads[spec.id] ?: DownloadState.Idle,
                downloading = spec.id in active,
                active = (engine as? EngineState.Ready)?.model?.id == spec.id ||
                    (engine as? EngineState.Loading)?.model?.id == spec.id,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun download(spec: ModelSpec) = c.startDownload(spec)
    fun pause(spec: ModelSpec) = c.pauseDownload(spec)
    fun load(spec: ModelSpec) = c.selectAndLoad(spec)

    fun delete(spec: ModelSpec) {
        viewModelScope.launch {
            if ((engineState.value as? EngineState.Ready)?.model?.id == spec.id) c.llm.unload()
            c.models.delete(spec)
            tick.value++
        }
    }

    fun rescan() {
        tick.value++
    }

    fun setBackend(b: BackendChoice) {
        c.settings.backend = b
        _backend.value = b
        // Reload the current model on the new backend.
        (engineState.value as? EngineState.Ready)?.model?.let { c.selectAndLoad(it) }
    }

    fun setHfToken(t: String) {
        c.settings.hfToken = t
        _hfToken.value = t
    }
}

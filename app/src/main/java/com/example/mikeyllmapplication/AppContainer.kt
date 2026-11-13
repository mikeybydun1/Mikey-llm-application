package com.example.mikeyllmapplication

import android.content.Context
import com.example.mikeyllmapplication.device.DeviceInfoRepository
import com.example.mikeyllmapplication.llm.BackendChoice
import com.example.mikeyllmapplication.llm.EngineState
import com.example.mikeyllmapplication.llm.LlmEngine
import com.example.mikeyllmapplication.llm.ModelCatalog
import com.example.mikeyllmapplication.llm.ModelManager
import com.example.mikeyllmapplication.llm.ModelSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Persisted user preferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var selectedModelId: String?
        get() = prefs.getString("model", null)
        set(v) = prefs.edit().putString("model", v).apply()

    var backend: BackendChoice
        get() = runCatching { BackendChoice.valueOf(prefs.getString("backend", null)!!) }.getOrDefault(BackendChoice.AUTO)
        set(v) = prefs.edit().putString("backend", v.name).apply()

    var includeDeviceData: Boolean
        get() = prefs.getBoolean("deviceData", true)
        set(v) = prefs.edit().putBoolean("deviceData", v).apply()

    var hfToken: String
        get() = prefs.getString("hfToken", "") ?: ""
        set(v) = prefs.edit().putString("hfToken", v).apply()

    var askedPermissions: Boolean
        get() = prefs.getBoolean("askedPermissions", false)
        set(v) = prefs.edit().putBoolean("askedPermissions", v).apply()
}

/** Manual dependency container; lives as long as the process. */
class AppContainer(context: Context) {
    val settings = AppSettings(context)
    val models = ModelManager(context)
    val llm = LlmEngine(context)
    val device = DeviceInfoRepository(context)

    /** Work that must outlive any single screen (downloads, model loading). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val _activeDownloads = MutableStateFlow<Set<String>>(emptySet())
    val activeDownloads: StateFlow<Set<String>> = _activeDownloads.asStateFlow()

    fun startDownload(spec: ModelSpec, loadWhenDone: Boolean = true) {
        if (downloadJobs[spec.id]?.isActive == true) return
        val job = scope.launch {
            val result = models.download(spec, settings.hfToken.ifBlank { null })
            if (result.isSuccess && loadWhenDone && llm.state.value !is EngineState.Ready) {
                selectAndLoad(spec)
            }
        }
        downloadJobs[spec.id] = job
        _activeDownloads.value = _activeDownloads.value + spec.id
        job.invokeOnCompletion {
            downloadJobs.remove(spec.id)
            _activeDownloads.value = _activeDownloads.value - spec.id
        }
    }

    fun pauseDownload(spec: ModelSpec) {
        downloadJobs[spec.id]?.cancel()
    }

    fun selectAndLoad(spec: ModelSpec) {
        settings.selectedModelId = spec.id
        val file = models.findModelFile(spec) ?: return
        scope.launch { llm.load(spec, file, settings.backend) }
    }

    /** On app start, load the previously used model (or the first installed one) automatically. */
    fun autoLoad() {
        if (llm.state.value !is EngineState.NotLoaded) return
        val preferred = ModelCatalog.byId(settings.selectedModelId)?.takeIf { models.isInstalled(it) }
            ?: ModelCatalog.models.firstOrNull { models.isInstalled(it) }
            ?: return
        selectAndLoad(preferred)
    }
}

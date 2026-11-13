package com.example.mikeyllmapplication

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.mikeyllmapplication.device.DeviceInfoRepository
import com.example.mikeyllmapplication.device.SectionKey
import com.example.mikeyllmapplication.llm.BackendChoice
import com.example.mikeyllmapplication.llm.EngineState
import com.example.mikeyllmapplication.llm.LlmEngine
import com.example.mikeyllmapplication.llm.ModelCatalog
import com.example.mikeyllmapplication.llm.ModelManager
import com.example.mikeyllmapplication.llm.Prompts
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceAndLlmInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyCollectorProducesData() = runBlocking {
        val repo = DeviceInfoRepository(context)
        val sections = repo.collectAll()
        assertEquals(SectionKey.entries.size, sections.size)
        sections.forEach { s ->
            Log.i("CollectorTest", s.toPromptText(maxChildren = 3))
            assertTrue("${s.key} failed: ${s.notes}", s.notes.none { it.startsWith("Failed to read") })
        }
        // These must always have content on any device / emulator.
        listOf(SectionKey.SYSTEM, SectionKey.HARDWARE, SectionKey.BATTERY, SectionKey.NETWORK, SectionKey.WIFI)
            .forEach { k -> assertTrue("$k empty", sections.first { it.key == k }.entries.isNotEmpty()) }
    }

    @Test
    fun bleScanCompletes() = runBlocking {
        val repo = DeviceInfoRepository(context)
        repo.scanBle(2_000)
        assertTrue(repo.bluetooth.lastScanAtMs > 0 || repo.bluetooth.lastScanError != null)
    }

    @Test
    fun contextRespectsBudget() = runBlocking {
        val repo = DeviceInfoRepository(context)
        val ctx = repo.buildContext("Summarize everything about my phone", 4000)
        assertTrue(ctx.text.length <= 4000 + 20)
        assertTrue(ctx.text.startsWith("DEVICE: "))
    }

    /** Runs only when a model file is installed (download it in the app or adb push it). */
    @Test
    fun llmAnswersFromDeviceData() = runBlocking {
        val mm = ModelManager(context)
        val spec = ModelCatalog.models.firstOrNull { mm.isInstalled(it) }
        assumeTrue("No model installed; skipping LLM test", spec != null)
        val engine = LlmEngine(context)
        engine.load(spec!!, mm.findModelFile(spec)!!, BackendChoice.AUTO)
        assertTrue(engine.state.value.toString(), engine.state.value is EngineState.Ready)
        val repo = DeviceInfoRepository(context)
        val q = "What Android API level is this device running? Answer with just the number."
        val ctx = repo.buildContext(q, spec.contextCharBudget)
        val answer = engine.send(Prompts.userTurn(ctx.text, q)).toList().joinToString("")
        Log.i("LlmTest", "Answer: $answer")
        assertTrue("Answer was: $answer", answer.contains(android.os.Build.VERSION.SDK_INT.toString()))
        engine.unload()
    }
}

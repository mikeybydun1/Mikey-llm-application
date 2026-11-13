package com.example.mikeyllmapplication

import com.example.mikeyllmapplication.device.DeviceSection
import com.example.mikeyllmapplication.device.Entry
import com.example.mikeyllmapplication.device.Format
import com.example.mikeyllmapplication.device.SectionKey
import com.example.mikeyllmapplication.device.section
import com.example.mikeyllmapplication.llm.ModelCatalog
import com.example.mikeyllmapplication.llm.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatAndPromptTest {

    @Test
    fun wifiChannels() {
        assertEquals(1, Format.wifiChannel(2412))
        assertEquals(6, Format.wifiChannel(2437))
        assertEquals(13, Format.wifiChannel(2472))
        assertEquals(14, Format.wifiChannel(2484))
        assertEquals(36, Format.wifiChannel(5180))
        assertEquals(149, Format.wifiChannel(5745))
        assertEquals(1, Format.wifiChannel(5955)) // 6 GHz channel 1
        assertEquals(233, Format.wifiChannel(7115))
        assertNull(Format.wifiChannel(1000))
    }

    @Test
    fun wifiBands() {
        assertEquals("2.4 GHz", Format.wifiBand(2437))
        assertEquals("5 GHz", Format.wifiBand(5180))
        assertEquals("6 GHz", Format.wifiBand(5955))
    }

    @Test
    fun signalQualityBuckets() {
        assertEquals("excellent", Format.wifiRssiQuality(-45))
        assertEquals("fair", Format.wifiRssiQuality(-72))
        assertEquals("good", Format.lteRsrpQuality(-85))
        assertEquals("poor", Format.lteRsrpQuality(-105))
    }

    @Test
    fun bytesAndIp() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("2.4 GB", Format.bytes(2_588_147_712))
        assertEquals("192.168.1.10", Format.ipv4FromLittleEndianInt(0x0A01A8C0))
    }

    @Test
    fun telephonyUnavailableSentinel() {
        assertNull(Format.cellInt(Int.MAX_VALUE))
        assertEquals("-95 dBm", Format.cellInt(-95, "dBm"))
    }

    @Test
    fun sectionBuilderSkipsNullsAndSurvivesExceptions() {
        val s = section(SectionKey.WIFI) {
            put("ok") { "value" }
            put("null") { null }
            put("blank") { "  " }
            put("throws") { error("boom") }
            put("denied") { throw SecurityException("nope") }
        }
        assertEquals(listOf("ok", "denied"), s.entries.map { it.label })
        assertTrue(s.entries.last().value.contains("permission denied"))
    }

    @Test
    fun promptTextTruncatesLongLists() {
        val s = DeviceSection(
            SectionKey.BLUETOOTH,
            listOf(Entry("Scan", "5 found", (1..5).map { Entry("dev$it", "-60 dBm") })),
            notes = listOf("a note"),
        )
        val text = s.toPromptText(maxChildren = 2)
        assertTrue(text.startsWith("## Bluetooth & BLE"))
        assertTrue(text.contains("dev2"))
        assertFalse(text.contains("dev3"))
        assertTrue(text.contains("… and 3 more"))
        assertTrue(text.contains("NOTE: a note"))
    }

    @Test
    fun userTurnWrapsDeviceData() {
        assertEquals("hi", Prompts.userTurn(null, "hi"))
        val p = Prompts.userTurn("## Wi-Fi\n- RSSI: -50", "How is my Wi-Fi?")
        assertTrue(p.startsWith("<DEVICE DATA>"))
        assertTrue(p.endsWith("Question: How is my Wi-Fi?"))
    }

    @Test
    fun catalogIsConsistent() {
        assertEquals(ModelCatalog.models.size, ModelCatalog.models.map { it.id }.toSet().size)
        ModelCatalog.models.forEach {
            assertTrue(it.url.startsWith("https://huggingface.co/"))
            assertTrue(it.url.endsWith(it.fileName))
            assertTrue(it.contextCharBudget / 3 < it.maxTokens / 2)
        }
    }
}

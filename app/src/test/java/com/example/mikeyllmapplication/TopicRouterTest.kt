package com.example.mikeyllmapplication

import com.example.mikeyllmapplication.device.SectionKey
import com.example.mikeyllmapplication.device.TopicRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicRouterTest {

    private fun sections(q: String) = TopicRouter.route(q).sections

    @Test
    fun wifiQuestionDoesNotPullInCellular() {
        val s = sections("How good is my Wi-Fi signal and what channel am I on?")
        assertTrue(SectionKey.WIFI in s)
        assertTrue(SectionKey.NETWORK in s)
        assertFalse(SectionKey.CELLULAR in s)
    }

    @Test
    fun ambiguousSignalQuestionIncludesBothRadios() {
        val s = sections("Why are my signal bars so low?")
        assertTrue(SectionKey.WIFI in s)
        assertTrue(SectionKey.CELLULAR in s)
    }

    @Test
    fun bleScanIsTriggeredOnlyForNearbyQuestions() {
        assertTrue(TopicRouter.route("Scan for nearby BLE devices").wantsBleScan)
        assertTrue(TopicRouter.route("What beacons are around me?").wantsBleScan)
        assertFalse(TopicRouter.route("Does my phone support BLE 2M PHY?").wantsBleScan)
    }

    @Test
    fun cellularKeywords() {
        listOf(
            "Which LTE band am I on?",
            "What's my RSRP?",
            "Am I on 5G NSA?",
            "Is my SIM roaming?",
            "Which carrier am I connected to?",
        ).forEach { q -> assertTrue(q, SectionKey.CELLULAR in sections(q)) }
    }

    @Test
    fun wholeWordMatchingAvoidsFalsePositives() {
        // "doubt" contains "bt", "simple" contains "sim", "cellar" contains "cell".
        val s = sections("I doubt this simple wine cellar question needs much thought")
        assertFalse(SectionKey.BLUETOOTH in s)
        assertFalse(SectionKey.CELLULAR in s)
    }

    @Test
    fun prefixKeywordsMatchWordForms() {
        assertTrue(SectionKey.BLUETOOTH in sections("Which devices are advertising?"))
        assertTrue(SectionKey.BATTERY in sections("Is it charging fast?"))
    }

    @Test
    fun summaryRequestsIncludeEverythingButAppList() {
        val s = sections("Summarize everything about my phone")
        assertEquals(SectionKey.entries.toSet() - SectionKey.APPS, s)
    }

    @Test
    fun unknownQuestionFallsBackToSystem() {
        assertEquals(setOf(SectionKey.SYSTEM), sections("Tell me a joke"))
    }

    @Test
    fun batteryQuestionAddsThermalHardware() {
        val s = sections("Why does my battery drain so fast?")
        assertTrue(SectionKey.BATTERY in s)
        assertTrue(SectionKey.HARDWARE in s)
    }

    @Test
    fun radioQuestionsIncludePermissions() {
        assertTrue(SectionKey.PERMISSIONS in sections("What is my Wi-Fi SSID?"))
    }
}

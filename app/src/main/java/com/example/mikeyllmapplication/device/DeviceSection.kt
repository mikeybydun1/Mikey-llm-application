package com.example.mikeyllmapplication.device

/** The categories of device information the app can collect. */
enum class SectionKey(val title: String) {
    SYSTEM("System & Build"),
    HARDWARE("CPU, Memory & Storage"),
    DISPLAY("Display"),
    BATTERY("Battery & Power"),
    NETWORK("Connectivity (IP / DNS / Transports)"),
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth & BLE"),
    CELLULAR("Cellular / SIM / Mobile network"),
    LOCATION("Location & GNSS"),
    SENSORS("Sensors"),
    AUDIO("Audio"),
    CAMERA("Cameras"),
    FEATURES("Hardware features (NFC, UWB, ...)"),
    SECURITY("Security & Settings"),
    APPS("Installed apps"),
    PERMISSIONS("App permissions"),
}

/** One key/value reading. [children] holds nested rows (e.g. one scanned network). */
data class Entry(
    val label: String,
    val value: String,
    val children: List<Entry> = emptyList(),
)

data class DeviceSection(
    val key: SectionKey,
    val entries: List<Entry>,
    /** Short explanations of why data is missing (e.g. a permission was denied). */
    val notes: List<String> = emptyList(),
) {
    val title: String get() = key.title

    /** Compact, LLM-friendly text rendering of this section. */
    fun toPromptText(maxChildren: Int = 12): String = buildString {
        append("## ").append(title).append('\n')
        entries.forEach { appendEntry(it, 0, maxChildren) }
        notes.forEach { append("- NOTE: ").append(it).append('\n') }
    }

    private fun StringBuilder.appendEntry(e: Entry, depth: Int, maxChildren: Int) {
        repeat(depth) { append("  ") }
        append("- ").append(e.label)
        if (e.value.isNotEmpty()) append(": ").append(e.value)
        append('\n')
        e.children.take(maxChildren).forEach { appendEntry(it, depth + 1, maxChildren) }
        if (e.children.size > maxChildren) {
            repeat(depth + 1) { append("  ") }
            append("- … and ").append(e.children.size - maxChildren).append(" more\n")
        }
    }
}

/** Small DSL that keeps collectors readable and makes every single reading failure-tolerant. */
class SectionBuilder(private val key: SectionKey) {
    private val entries = mutableListOf<Entry>()
    private val notes = mutableListOf<String>()

    /** Adds a row; null / blank values are skipped and exceptions are swallowed. */
    fun put(label: String, value: () -> Any?) {
        val v = try {
            value()
        } catch (e: SecurityException) {
            "unavailable (permission denied)"
        } catch (e: Throwable) {
            null
        }
        val s = v?.toString()?.trim()
        if (!s.isNullOrEmpty()) entries += Entry(label, s)
    }

    fun group(label: String, value: String = "", children: List<Entry>) {
        entries += Entry(label, value, children)
    }

    fun add(entry: Entry) {
        entries += entry
    }

    fun note(text: String) {
        notes += text
    }

    fun build() = DeviceSection(key, entries.toList(), notes.toList())
}

inline fun section(key: SectionKey, block: SectionBuilder.() -> Unit): DeviceSection =
    SectionBuilder(key).apply(block).build()

/** Builds child rows the same tolerant way. */
class EntryListBuilder {
    val list = mutableListOf<Entry>()
    fun put(label: String, value: () -> Any?) {
        val s = try {
            value()?.toString()?.trim()
        } catch (e: Throwable) {
            null
        }
        if (!s.isNullOrEmpty()) list += Entry(label, s)
    }
}

inline fun entries(block: EntryListBuilder.() -> Unit): List<Entry> =
    EntryListBuilder().apply(block).list

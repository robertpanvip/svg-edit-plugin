package com.pan.svg.plugin

import com.intellij.ide.util.PropertiesComponent
import com.pan.svg.core.Json

/**
 * Persists the SVGO pass toggles as one JSON string under a single [PropertiesComponent] key, so
 * the whole settings map survives as the pass catalogue evolves without any schema churn. The
 * value only carries explicit booleans; an empty or unreadable value degrades to the empty map,
 * which the engine reads as SVGO's default preset (everything on).
 */
object SvgoSettingsStore {
    private const val KEY = "com.pan.svg.svgo.settings"

    /** The persisted settings, or the empty map (= SVGO defaults) when absent or corrupt. */
    fun load(): Map<String, Boolean> {
        val raw =
            runCatching { PropertiesComponent.getInstance().getValue(KEY) }.getOrNull()
                ?: return emptyMap()
        return runCatching { decode(raw) }.getOrElse { emptyMap() }
    }

    /** Persists [settings] verbatim; a storage failure is non-fatal (the defaults still apply). */
    fun save(settings: Map<String, Boolean>) {
        runCatching { PropertiesComponent.getInstance().setValue(KEY, Json.write(settings)) }
    }

    private fun decode(raw: String): Map<String, Boolean> {
        val parsed = Json.parse(raw) as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Boolean>()
        for ((key, value) in parsed) {
            if (key is String && value is Boolean) out[key] = value
        }
        return out
    }
}

package nl.stijnvdpol.klinedash.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "DashboardLayout"
private const val FORMAT_VERSION = 1

private object Keys {
    const val VERSION = "version"
    const val PAGES = "pages"
    const val TEMPLATE = "template"
    const val PRIMARY = "primary"
    const val FIELDS = "fields"
    const val SIZE = "size"
    const val CELLS = "cells"
    const val SIGNAL = "signal"
    const val WIDGET = "widget"
    const val SHIFT_RPM = "shiftRpm"
}

/** Template and widget names in the JSON are lower case, so a hand-edited file stays readable. */
private fun PageTemplate.jsonName() = name.lowercase()
private fun WidgetType.jsonName() = name.lowercase()

fun encodeDashboardLayout(layout: DashboardLayout): String {
    val pages = JSONArray()
    layout.pages.forEach { pages.put(encodePage(it)) }
    return JSONObject().put(Keys.VERSION, FORMAT_VERSION).put(Keys.PAGES, pages).toString()
}

private fun encodePage(page: Page): JSONObject {
    val json = JSONObject().put(Keys.TEMPLATE, page.template.jsonName())
    when (page) {
        is MainPage -> json.put(Keys.PRIMARY, JSONArray(page.primary)).put(Keys.FIELDS, JSONArray(page.fields))
        is GridPage -> json.put(Keys.SIZE, page.size.code).put(Keys.CELLS, encodeCells(page.cells))
        is TrackPage -> json.put(Keys.SHIFT_RPM, page.shiftRpm)
    }
    return json
}

private fun encodeCells(cells: List<GridCell>): JSONArray {
    val array = JSONArray()
    cells.forEach { array.put(JSONObject().put(Keys.SIGNAL, it.signalId).put(Keys.WIDGET, it.widget.jsonName())) }
    return array
}

/**
 * The stored layout, or the default one when nothing is stored (null) or the text cannot be used.
 * Never throws: the reason is logged and the dashboard stays usable.
 */
fun decodeDashboardLayout(raw: String?): DashboardLayout {
    if (raw == null) return DashboardLayout.Default
    return try {
        parseDashboardLayout(raw)
    } catch (e: JSONException) {
        Log.w(TAG, "Stored dashboard layout is not valid JSON (${e.message}), using the default", e)
        DashboardLayout.Default
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Stored dashboard layout is unusable (${e.message}), using the default", e)
        DashboardLayout.Default
    }
}

/**
 * Parses and validates. A page that cannot be understood (unknown template or grid size) is
 * skipped and logged; slot lists are cut or filled up to the size the template needs; the shift
 * point is clamped. Unknown signal ids are kept. Fails when the text is not JSON, has no "pages"
 * array, or no page is left.
 */
internal fun parseDashboardLayout(raw: String): DashboardLayout {
    val array = JSONObject(raw).getJSONArray(Keys.PAGES)
    val pages = (0 until array.length()).mapNotNull { index ->
        parsePage(array.optJSONObject(index)).also { if (it == null) Log.w(TAG, "Skipping unreadable page ${index + 1}") }
    }.take(MAX_PAGES)
    require(pages.isNotEmpty()) { "no usable page" }
    return DashboardLayout(pages)
}

private fun parsePage(json: JSONObject?): Page? {
    json ?: return null
    return when (json.optString(Keys.TEMPLATE)) {
        PageTemplate.MAIN.jsonName() -> MainPage(
            primary = fitSlots(json.optJSONArray(Keys.PRIMARY), DEFAULT_PRIMARY_IDS, PRIMARY_SLOT_COUNT),
            fields = fitSlots(json.optJSONArray(Keys.FIELDS), DEFAULT_FIELD_IDS, FIELD_SLOT_COUNT),
        )
        PageTemplate.GRID.jsonName() -> parseGridPage(json)
        PageTemplate.TRACK.jsonName() -> TrackPage(clampShiftRpm(json.optInt(Keys.SHIFT_RPM, DEFAULT_SHIFT_RPM)))
        else -> null
    }
}

private fun parseGridPage(json: JSONObject): GridPage? {
    val size = GridSize.entries.firstOrNull { it.code == json.optString(Keys.SIZE) } ?: return null
    val array = json.optJSONArray(Keys.CELLS)
    val defaults = defaultGridCells(size)
    return GridPage(size, List(size.cellCount) { index -> parseCell(array?.optJSONObject(index)) ?: defaults[index] })
}

/** A cell without a signal id is unusable (null, the default fills its place); an unknown widget becomes a number. */
private fun parseCell(json: JSONObject?): GridCell? {
    val signal = json?.optString(Keys.SIGNAL)?.takeIf { it.isNotBlank() } ?: return null
    val widget = WidgetType.entries.firstOrNull { it.jsonName() == json.optString(Keys.WIDGET) } ?: WidgetType.NUMBER
    return GridCell(signal, widget)
}

private fun fitSlots(json: JSONArray?, defaults: List<String>, count: Int): List<String> =
    List(count) { slot -> json?.optString(slot)?.takeIf { it.isNotBlank() } ?: defaults[slot] }

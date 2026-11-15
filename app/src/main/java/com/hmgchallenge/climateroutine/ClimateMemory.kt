package com.hmgchallenge.climateroutine

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.round

internal enum class Zone(val label: String) { DRIVER("운전석"), PASSENGER("동승석") }

internal enum class Control(val label: String, val step: Double, val min: Double, val max: Double, val initial: Double) {
    TEMPERATURE("설정 온도", .5, 16.0, 30.0, 22.0),
    FAN("풍량", 1.0, 0.0, 8.0, 2.0),
    DIRECTION("풍향", 1.0, 0.0, 2.0, 0.0),
    HEAT("시트 열선", 1.0, 0.0, 3.0, 0.0),
    VENT("시트 통풍", 1.0, 0.0, 3.0, 0.0);

    fun normalize(value: Double) = (round(value / step) * step).coerceIn(min, max)
    fun display(value: Double) = when (this) {
        TEMPERATURE -> "${value}°C"
        DIRECTION -> listOf("상체", "상체·발", "발")[value.toInt()]
        else -> if (value == 0.0) "꺼짐" else "${value.toInt()}단"
    }
}

internal inline fun <T> JSONArray.objects(convert: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { optJSONObject(it)?.let(convert) }

internal data class Conditions(val cabin: Double, val minute: Int, val vent: Double, val heat: Double) {
    fun distanceSquared(other: Conditions): Double {
        val clock = abs(minute - other.minute).let { minOf(it, 1440 - it) }
        val components = listOf((cabin - other.cabin) / 5, clock / 240.0,
            (vent - other.vent) / 3, (heat - other.heat) / 3)
        return components.sumOf { it * it }
    }
}

internal data class ClimatePoint(val zone: Zone, val control: Control, val conditions: Conditions,
                                 val value: Double, val time: Long, val correction: Boolean = false,
                                 val previousTarget: Double? = null) {
    fun json() = JSONObject().put("zone", zone.name).put("control", control.name)
        .put("cabin", conditions.cabin).put("minute", conditions.minute)
        .put("vent", conditions.vent).put("heat", conditions.heat).put("value", value)
        .put("time", time).put("correction", correction).put("previousTarget", previousTarget)

    companion object {
        fun read(row: JSONObject) = ClimatePoint(Zone.valueOf(row.getString("zone")),
            Control.valueOf(row.getString("control")),
            Conditions(row.getDouble("cabin"), row.getInt("minute"), row.getDouble("vent"), row.getDouble("heat")),
            row.getDouble("value"), row.getLong("time"), row.optBoolean("correction"),
            if (row.has("previousTarget")) row.optDouble("previousTarget") else null)
    }
}

internal class ClimateMemory(private val prefs: SharedPreferences) {
    private fun list(key: String) = JSONArray(prefs.getString(key, "[]") ?: "[]").objects { it }
    private fun save(key: String, rows: List<JSONObject>) {
        prefs.edit().putString(key, JSONArray(rows).toString()).apply()
    }

    val profiles get() = list("profilesV2")
    val routines get() = list("routinesV2")
    val events get() = list("eventsV2")
    val tracking get() = prefs.getString("trackingV2", null)?.let(::JSONObject)
    val legacyEvents get() = list("records")
    val history get() = list("historyV2")

    fun startTracking(name: String, end: Long) {
        prefs.edit().putString("trackingV2", JSONObject().put("name", name).put("end", end)
            .put("points", JSONArray()).toString()).apply()
    }

    fun stopTracking(): JSONObject? {
        val session = tracking ?: return null
        val points = session.getJSONArray("points")
        val profile = if (points.length() == 0) null else JSONObject()
            .put("name", session.getString("name")).put("id", java.util.UUID.randomUUID().toString())
            .put("enabled", false).put("points", points)
        val edit = prefs.edit().remove("trackingV2")
        if (profile != null) edit.putString("profilesV2", JSONArray(profiles + profile).toString())
        edit.apply()
        return profile
    }

    fun record(point: ClimatePoint, profileId: String?) {
        save("eventsV2", (events + point.json()).takeLast(600))
        tracking?.let { session ->
            session.put("points", merged(session.getJSONArray("points"), point))
            prefs.edit().putString("trackingV2", session.toString()).apply()
        }
        if (profileId != null && point.correction) {
            profiles.firstOrNull { it.optString("id") == profileId }?.let {
                it.put("points", merged(it.getJSONArray("points"), point))
                saveProfile(it)
            }
        }
    }

    private fun merged(points: JSONArray, point: ClimatePoint): JSONArray {
        val rows = points.objects(ClimatePoint::read).toMutableList()
        val index = rows.indexOfLast { it.zone == point.zone && it.control == point.control &&
            it.conditions.distanceSquared(point.conditions) < .04 }
        // The latest explicit change wins when the same conditions are repeated.
        if (index >= 0) rows.removeAt(index)
        return JSONArray(rows.takeLast(399).map(ClimatePoint::json) + point.json())
    }

    fun predict(profile: JSONObject, zone: Zone, control: Control, conditions: Conditions, now: Long): Double? {
        val neighbors = profile.getJSONArray("points").objects(ClimatePoint::read)
            .filter { it.zone == zone && it.control == control }
            .map { it to conditions.distanceSquared(it.conditions) }
            .filter { it.second <= 1.0 }.sortedBy { it.second }.take(5)
        if (neighbors.isEmpty()) return null
        var weighted = 0.0
        var total = 0.0
        for ((point, distance) in neighbors) {
            val ageDays = (now - point.time).coerceAtLeast(0) / 86_400_000.0
            val weight = (if (point.correction) 2.0 else 1.0) / ((.05 + distance) * (1 + ageDays / 30))
            weighted += point.value * weight
            total += weight
        }
        return control.normalize(weighted / total)
    }

    fun saveProfile(row: JSONObject) = saveRow("profilesV2", row)
    fun saveRoutine(row: JSONObject) = saveRow("routinesV2", row)
    fun deleteProfile(id: String) = delete("profilesV2", id)
    fun deleteRoutine(id: String) = delete("routinesV2", id)

    private fun saveRow(key: String, row: JSONObject) {
        val id = row.getString("id")
        val rows = list(key).toMutableList()
        val index = rows.indexOfFirst { it.optString("id") == id }
        if (index == -1) rows.add(row) else rows[index] = row
        save(key, rows)
    }

    private fun delete(key: String, id: String) = save(key, list(key).filter { it.optString("id") != id })

    fun log(message: String) {
        save("historyV2", (history + JSONObject().put("time", System.currentTimeMillis())
            .put("message", message)).takeLast(40))
    }

    fun clearRecords() {
        prefs.edit().remove("eventsV2").remove("records").remove("trackingV2").remove("trackingUntil").apply()
    }

    fun migrateRoutine() {
        if (!prefs.contains("routine") && !prefs.contains("on") && !prefs.contains("off")) return
        val on = prefs.getInt("on", 3).coerceIn(0, 119)
        val old = JSONObject().put("id", "previous-timer").put("name", "기존 열선 루틴")
            .put("zone", Zone.DRIVER.name).put("type", "timer").put("on", on)
            .put("off", prefs.getInt("off", 20).coerceIn(on + 1, 120)).put("threshold", 16)
            .put("duration", 10).put("enabled", prefs.getBoolean("routine", false))
        if (routines.none { it.optString("id") == "previous-timer" }) saveRoutine(old)
        prefs.edit().remove("routine").remove("on").remove("off").apply()
    }
}

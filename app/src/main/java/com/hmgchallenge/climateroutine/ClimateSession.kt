package com.hmgchallenge.climateroutine

import java.util.Calendar

internal class ClimateSession(private val memory: ClimateMemory, private val announce: (String) -> Unit) {
    private data class Channel(val zone: Zone, val control: Control)
    private data class Target(val value: Double, val owner: String, val profileId: String? = null)

    private val values = mutableMapOf<Channel, Double>()
    private val overrides = mutableSetOf<Channel>()
    private val automatic = mutableMapOf<Channel, Target>()
    private val suspendedTargets = mutableMapOf<Channel, Target>()
    private val heatSince = mutableMapOf<Zone, Long>()
    private val restUntil = mutableMapOf<Zone, Long>()
    private val conditionStarts = mutableMapOf<String, Long>()
    private var started: Long? = null
    var cabin = 18.0
    var simulatedMinute: Int? = null
    var status = "주행 세션을 시작하면 자동 동작을 확인할 수 있습니다."
        private set
    var remaining = emptyMap<Zone, Long>()
        private set
    val running get() = started != null

    fun minute(now: Long): Int = simulatedMinute ?: Calendar.getInstance().apply {
        timeInMillis = now
    }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    fun value(zone: Zone, control: Control) = values[Channel(zone, control)] ?: control.initial

    private fun conditions(zone: Zone, now: Long) =
        Conditions(cabin, minute(now), value(zone, Control.VENT), value(zone, Control.HEAT))

    fun toggle(elapsed: Long) {
        if (running) stop() else {
            started = elapsed
            status = "자동 적용 조건을 확인하고 있습니다."
        }
    }

    fun stop() {
        if (!running) return
        started = null
        overrides.clear()
        automatic.clear()
        suspendedTargets.clear()
        heatSince.clear()
        restUntil.clear()
        conditionStarts.clear()
        remaining = emptyMap()
        Zone.entries.forEach {
            values[Channel(it, Control.HEAT)] = 0.0
            values[Channel(it, Control.VENT)] = 0.0
        }
        status = "주행 세션을 종료했습니다."
    }

    fun manual(zone: Zone, control: Control, change: Double, now: Long, elapsed: Long) {
        val channel = Channel(zone, control)
        val current = value(zone, control)
        val next = control.normalize(current + change)
        if (next == current) return
        val interrupted = automatic.remove(channel)
        if (interrupted != null) suspendedTargets[channel] = interrupted
        val target = suspendedTargets[channel]
        val point = ClimatePoint(zone, control, conditions(zone, now), next, now, target != null, target?.value)
        if (memory.tracking != null || target != null) memory.record(point, target?.profileId)
        if (running) overrides.add(channel)
        set(channel, next, elapsed)
        if (control == Control.HEAT) remaining = remaining - zone
        if (interrupted != null) report("${zone.label} ${control.label} 자동 조절을 중지했습니다.")
    }

    fun update(now: Long, elapsed: Long) {
        val start = started ?: return
        val profile = memory.profiles.firstOrNull { it.optBoolean("enabled") }
        val routines = memory.routines.filter { it.optBoolean("enabled") }
        val countdown = mutableMapOf<Zone, Long>()
        for (zone in Zone.entries) {
            val context = conditions(zone, now)
            // First enabled routine for a zone owns its heater until the session ends.
            val routine = routines.firstOrNull { it.optString("zone") == zone.name }
            for (control in Control.entries) {
                val channel = Channel(zone, control)
                val activeSince = heatSince[zone]
                if (control == Control.HEAT && activeSince != null && elapsed - activeSince >= 30 * MINUTE) {
                    set(channel, 0.0, elapsed)
                    automatic.remove(channel)
                    overrides.add(channel)
                    report("${zone.label} 열선 가동 한도에 도달했습니다.")
                }
                if (channel in overrides) continue
                val target = if (control == Control.HEAT && routine != null) {
                    routineTarget(routine, zone, start, elapsed, countdown)
                } else {
                    val preference = profile?.let { memory.predict(it, zone, control, context, now) }
                    if (profile == null || preference == null) null else {
                        val desired = if (control == Control.HEAT && preference > 0) {
                            when {
                                elapsed < (restUntil[zone] ?: 0) -> 0.0
                                activeSince != null && elapsed - activeSince >= 10 * MINUTE -> {
                                    restUntil[zone] = elapsed + 5 * MINUTE
                                    0.0
                                }
                                else -> preference
                            }
                        } else preference
                        Target(desired, profile.getString("name"), profile.getString("id"))
                    }
                }
                val previous = automatic[channel]
                if (target == null) {
                    if (previous != null && (control == Control.HEAT || control == Control.VENT)) {
                        set(channel, 0.0, elapsed)
                    }
                    automatic.remove(channel)
                    continue
                }
                automatic[channel] = target
                val current = value(zone, control)
                val next = if (control == Control.TEMPERATURE) {
                    target.value.coerceIn(current - .5, current + .5)
                } else target.value
                if (current == next) continue
                set(channel, next, elapsed)
                if (previous != target || next == target.value) {
                    report("${target.owner} · ${zone.label} ${control.label} ${control.display(next)}")
                }
            }
        }
        remaining = countdown
    }

    private fun routineTarget(routine: org.json.JSONObject, zone: Zone, start: Long, elapsed: Long,
                              countdown: MutableMap<Zone, Long>): Target {
        val id = routine.getString("id")
        val end: Long
        val active: Boolean
        if (routine.getString("type") == "timer") {
            end = minOf(start + routine.getInt("off") * MINUTE, start + (routine.getInt("on") + 30) * MINUTE)
            active = elapsed >= start + routine.getInt("on") * MINUTE && elapsed < end
        } else {
            val triggered = conditionStarts[id] ?: if (cabin <= routine.getInt("threshold")) {
                elapsed.also { conditionStarts[id] = it }
            } else null
            end = (triggered ?: 0) + routine.getInt("duration") * MINUTE
            active = triggered != null && elapsed < end
        }
        if (active) countdown[zone] = (end - elapsed + 999) / 1000
        return Target(if (active) 1.0 else 0.0, routine.getString("name"))
    }

    private fun set(channel: Channel, value: Double, elapsed: Long) {
        values[channel] = value
        if (channel.control == Control.HEAT) {
            if (value > 0) heatSince.putIfAbsent(channel.zone, elapsed) else heatSince.remove(channel.zone)
        }
    }

    private fun report(message: String) {
        status = message
        memory.log(message)
        announce(message)
    }

    companion object { const val MINUTE = 60_000L }
}

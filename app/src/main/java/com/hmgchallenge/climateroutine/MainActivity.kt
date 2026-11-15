package com.hmgchallenge.climateroutine

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.InputFilter
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("climate", MODE_PRIVATE) }
    private val memory by lazy { ClimateMemory(prefs) }
    private val session by lazy { ClimateSession(memory, ::speak) }
    private val handler = Handler(Looper.getMainLooper())
    private val controlLabels = mutableMapOf<Pair<Zone, Control>, TextView>()
    private var statusLabel: TextView? = null
    private var environmentLabel: TextView? = null
    private var trackingLabel: TextView? = null
    private var page = 0
    private lateinit var body: LinearLayout
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var windowWidth = 0
    private var active = false
    private val now get() = System.currentTimeMillis()
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val canvas get() = if (dark) 0xff18191c.toInt() else 0xfff3f4f6.toInt()
    private val panel get() = if (dark) 0xff26282d.toInt() else Color.WHITE
    private val buttonFill get() = if (dark) 0xff39404b.toInt() else 0xffe9edf3.toInt()
    private val ink get() = if (dark) Color.WHITE else 0xff171b22.toInt()
    private val muted get() = if (dark) 0xffc8cdd5.toInt() else 0xff4e5968.toInt()
    private val accent get() = if (dark) 0xffa8c8ff.toInt() else 0xff05469b.toInt()
    private val onAccent get() = if (dark) 0xff17273e.toInt() else Color.WHITE

    private val tick = object : Runnable {
        override fun run() {
            val time = now
            val tracking = memory.tracking
            if (tracking != null && time >= tracking.getLong("end")) {
                finishTracking()
            }
            if (session.running) session.update(time, SystemClock.elapsedRealtime())
            updateHome()
            handler.postDelayed(this, if (session.running) 1000 else 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memory.migrateRoutine()
        page = savedInstanceState?.getInt("page") ?: 0
        speech = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS && !isDestroyed) {
                val engine = speech ?: return@TextToSpeech
                val voice = engine.voices?.firstOrNull {
                    it.locale.language == Locale.KOREAN.language && !it.isNetworkConnectionRequired
                }
                speechReady = voice != null && engine.setVoice(voice) == TextToSpeech.SUCCESS
            }
        }
        findViewById<View>(android.R.id.content).addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && (width >= dp(900)) != (windowWidth >= dp(900))) {
                windowWidth = width
                handler.post { if (!isDestroyed) render() }
            }
        }
        windowWidth = dp(resources.configuration.screenWidthDp)
    }

    override fun onStart() {
        super.onStart()
        active = true
        render()
        handler.post(tick)
    }

    override fun onStop() {
        active = false
        handler.removeCallbacks(tick)
        session.stop()
        speech?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speech?.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page", page)
        super.onSaveInstanceState(outState)
    }

    private fun speak(message: String) {
        if (active && speechReady && prefs.getBoolean("voice", false)) {
            speech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }

    private fun dp(size: Int) = (size * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 20f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        this.text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setPadding(0, dp(5), 0, dp(5))
    }
    private fun button(title: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 20f
        isAllCaps = false
        minHeight = dp(64)
        minimumHeight = dp(64)
        minimumWidth = dp(64)
        stateListAnimator = null
        backgroundTintList = null
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(0x33808080),
            shape(if (primary) accent else buttonFill, 14), null)
        setTextColor(if (primary) onAccent else ink)
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
    }
    private fun card() = column().apply {
        background = shape(panel)
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    private fun LinearLayout.block(view: View) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun LinearLayout.weighted(view: View) {
        addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
    }
    private fun render() {
        controlLabels.clear()
        statusLabel = null
        environmentLabel = null
        trackingLabel = null
        val frame = column().apply {
            setBackgroundColor(canvas)
            val gutter = if (windowWidth >= dp(900)) dp(36) else dp(18)
            setPadding(gutter, dp(18), gutter, 0)
        }
        frame.addView(text(getString(R.string.app_name), 38f, bold = true))
        frame.addView(text("시뮬레이션 · 차량 연결 안 됨", 18f, accent))
        val tabs = row()
        listOf("홈", "트래킹", "공조 프로필", "루틴", "데이터").forEachIndexed { index, name ->
            tabs.addView(button(name, page == index) {
                if (page != index) { page = index; render() }
            }.apply { isSelected = page == index }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        frame.block(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })
        body = column().apply { setPadding(0, 0, 0, dp(20)) }
        frame.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        when (page) {
            0 -> home()
            1 -> trackingPage()
            2 -> profilesPage()
            3 -> routinesPage()
            4 -> dataPage()
        }
        setContentView(frame)
        updateHome()
    }

    private fun home() {
        val overview = card()
        overview.block(row().apply {
            val stack = column()
            environmentLabel = text("", 27f, bold = true).also { stack.addView(it) }
            trackingLabel = text("", 18f, muted).also { stack.addView(it) }
            addView(stack, LinearLayout.LayoutParams(0, -2, 1f))
            addView(button(if (session.running) "모의 시동 끄기" else "모의 시동 켜기", true) {
                session.toggle(SystemClock.elapsedRealtime())
                session.update(now, SystemClock.elapsedRealtime())
                render()
            })
        })
        statusLabel = text("", 21f, muted).also { overview.addView(it) }
        body.block(overview)
        val zones = Zone.entries.map(::zoneCard)
        if (windowWidth >= dp(900)) {
            body.block(row().apply {
                gravity = Gravity.TOP
                zones.forEach { weighted(it) }
            })
        } else zones.forEach { body.block(it) }
        body.block(button("모의 실내 조건 조정") { environmentDialog() })
    }

    private fun zoneCard(zone: Zone) = card().apply {
        block(text(zone.label, 27f, bold = true))
        for (control in Control.entries) {
            block(row().apply {
                val label = text("", 20f, bold = true)
                controlLabels[zone to control] = label
                addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                if (control == Control.DIRECTION) {
                    addView(button("변경") {
                        val delta = if (session.value(zone, control) == control.max) -2.0 else 1.0
                        manual(zone, control, delta)
                    }.apply { contentDescription = "${zone.label} 풍향 변경" })
                } else {
                    for ((sign, delta) in listOf("−" to -control.step, "+" to control.step)) {
                        addView(button(sign) { manual(zone, control, delta) }.apply {
                            contentDescription = "${zone.label} ${control.label} ${if (delta < 0) "내리기" else "올리기"}"
                        }, LinearLayout.LayoutParams(dp(68), -2).apply { marginStart = dp(8) })
                    }
                }
            })
        }
    }

    private fun manual(zone: Zone, control: Control, delta: Double) {
        if (memory.tracking?.getLong("end")?.let { it <= now } == true) finishTracking()
        session.manual(zone, control, delta, now, SystemClock.elapsedRealtime())
        updateHome()
    }

    private fun updateHome() {
        if (page != 0) return
        val profile = memory.profiles.firstOrNull { it.optBoolean("enabled") }?.optString("name")
        val moment = session.minute(now)
        environmentLabel?.text = "실내 ${session.cabin}°C · %02d:%02d".format(Locale.KOREA, moment / 60, moment % 60)
        trackingLabel?.text = memory.tracking?.optString("name")?.let { "${it} 기록 중" } ?: "트래킹 꺼짐"
        statusLabel?.text = buildString {
            append(if (session.running && profile != null) "${profile} 프로필 사용 중\n" else "")
            append(session.status)
            session.remaining.forEach { (zone, remaining) ->
                append("\n${zone.label} 열선 · %d:%02d 뒤 꺼짐".format(Locale.KOREA, remaining / 60, remaining % 60))
            }
        }
        controlLabels.forEach { (key, label) ->
            val (zone, control) = key
            label.text = "${control.label}  ${control.display(session.value(zone, control))}"
        }
    }

    private fun environmentDialog() {
        val form = dialogForm()
        var cabin = session.cabin.toInt()
        var hour = session.minute(now) / 60
        adjuster(form, "실내 온도", "°C", cabin, 5..40) { cabin = it }
        adjuster(form, "시각", "시", hour, 0..23) { hour = it }
        AlertDialog.Builder(this).setTitle("모의 실내 조건").setView(form)
            .setNeutralButton("현재 시각으로") { _, _ -> session.simulatedMinute = null; updateHome() }
            .setNegativeButton("취소", null).setPositiveButton("반영") { _, _ ->
                session.cabin = cabin.toDouble()
                session.simulatedMinute = hour * 60
                session.update(now, SystemClock.elapsedRealtime())
                updateHome()
            }.show()
    }

    private fun trackingPage() {
        val content = card()
        val tracking = memory.tracking
        if (tracking != null) {
            content.block(text("${tracking.getString("name")} 기록 중", 28f, bold = true))
            content.block(text("${tracking.getJSONArray("points").length()}개 학습 조건 · ${date(tracking.getLong("end"))} 종료", color = muted))
            content.block(button("기록 종료 및 프로필 만들기", true) { finishTracking(); render() })
        } else {
            content.block(text("새 트래킹", 28f, bold = true))
            content.block(text("이름과 기간을 정한 뒤 홈에서 평소처럼 공조를 조작하세요.", color = muted))
            content.block(button("기록 시작", true) { newTracking() })
        }
        body.block(content)
    }

    private fun newTracking() {
        val form = dialogForm()
        val input = nameInput("예: 겨울 출근").also { form.block(it) }
        val durations = listOf("1일", "7일", "5분 (시연)")
        val picker = picker(durations).also { form.block(it) }
        showForm("트래킹 시작", form, input) { name ->
            val minutes = listOf(1440L, 10080L, 5L)[picker.selectedItemPosition]
            memory.startTracking(name, now + minutes * ClimateSession.MINUTE)
        }
    }

    private fun finishTracking() {
        val result = memory.stopTracking()
        if (result != null) {
            toast("${result.optString("name")} 프로필을 저장했습니다.")
            if (page == 1) page = 2
        } else toast("기록된 공조 조작이 없습니다.")
        if (page == 1 || page == 2) render()
    }

    private fun profilesPage() {
        val profiles = memory.profiles
        if (profiles.isEmpty()) body.block(card().apply {
            block(text("저장된 공조 프로필이 없습니다", 26f, bold = true))
            block(button("트래킹 시작하기", true) { page = 1; render() })
        })
        profiles.forEach { profile ->
            val id = profile.getString("id")
            val points = profile.getJSONArray("points").objects(ClimatePoint::read)
            body.block(card().apply {
                block(text(profile.getString("name"), 27f, bold = true))
                block(text(Zone.entries.joinToString(" · ") { zone ->
                    "${zone.label} ${points.count { it.zone == zone }}개 조건"
                }, color = muted))
                block(row().apply {
                    val enabled = profile.optBoolean("enabled")
                    weighted(button(if (enabled) "끄기" else "켜기", !enabled) {
                        if (!enabled) profiles.filter { it.optBoolean("enabled") }.forEach {
                            memory.saveProfile(it.put("enabled", false))
                        }
                        memory.saveProfile(profile.put("enabled", !enabled))
                        render()
                    })
                    weighted(button("이름 변경") {
                        val form = dialogForm()
                        val input = nameInput("프로필 이름", profile.getString("name")).also { form.block(it) }
                        showForm("프로필 수정", form, input) { memory.saveProfile(profile.put("name", it)) }
                    })
                    weighted(button("삭제") { confirmDelete(profile.getString("name")) { memory.deleteProfile(id) } })
                })
            })
        }
    }

    private fun routinesPage() {
        body.block(button("루틴 만들기", true) {
            AlertDialog.Builder(this).setTitle("루틴 템플릿")
                .setItems(arrayOf("시동 후 켜기·끄기", "실내 온도 이하에서 켜기")) { _, choice ->
                    editRoutine(if (choice == 0) "timer" else "condition", null)
                }.setNegativeButton("취소", null).show()
        })
        if (memory.routines.isEmpty()) body.block(text("저장된 루틴이 없습니다.", color = muted))
        memory.routines.forEach { routine ->
            val zone = Zone.valueOf(routine.getString("zone"))
            val rule = if (routine.getString("type") == "timer") {
                "시동 ${routine.getInt("on")}분 뒤 켜기 · ${routine.getInt("off")}분 뒤 끄기"
            } else "실내 ${routine.getInt("threshold")}°C 이하 · ${routine.getInt("duration")}분 가동"
            body.block(card().apply {
                block(text(routine.getString("name"), 27f, bold = true))
                block(text("${zone.label} 열선 · $rule", color = muted))
                block(row().apply {
                    val enabled = routine.optBoolean("enabled")
                    weighted(button(if (enabled) "끄기" else "켜기", !enabled) {
                        if (!enabled) disableOtherRoutines(routine.getString("id"), zone)
                        memory.saveRoutine(routine.put("enabled", !enabled))
                        render()
                    })
                    weighted(button("수정") { editRoutine(routine.getString("type"), routine) })
                    weighted(button("삭제") { confirmDelete(routine.getString("name")) { memory.deleteRoutine(routine.getString("id")) } })
                })
            })
        }
        body.block(text("같은 좌석에서는 켜 둔 루틴이 프로필의 열선 설정보다 우선합니다.", 18f, muted))
    }

    private fun disableOtherRoutines(id: String, zone: Zone) {
        memory.routines.filter { it.optString("id") != id && it.optString("zone") == zone.name && it.optBoolean("enabled") }
            .forEach { memory.saveRoutine(it.put("enabled", false)) }
    }

    private fun editRoutine(type: String, old: JSONObject?) {
        val form = dialogForm()
        val name = nameInput("루틴 이름", old?.optString("name") ?: "").also { form.block(it) }
        val area = picker(Zone.entries.map { it.label }).also {
            it.setSelection(if (old?.optString("zone") == Zone.PASSENGER.name) 1 else 0)
            form.block(it)
        }
        var on = old?.optInt("on", 3) ?: 3
        var off = old?.optInt("off", 20) ?: 20
        var threshold = old?.optInt("threshold", 16) ?: 16
        var duration = old?.optInt("duration", 10) ?: 10
        if (type == "timer") {
            adjuster(form, "켜기", "분", on, 0..119) { on = it }
            adjuster(form, "끄기", "분", off, 1..120) { off = it }
        } else {
            adjuster(form, "실내 온도 이하", "°C", threshold, 5..35) { threshold = it }
            adjuster(form, "가동 시간", "분", duration, 1..30) { duration = it }
        }
        showForm(if (old == null) "새 루틴" else "루틴 수정", form, name,
            validate = { type != "timer" || off > on || run {
                toast("끄는 시각은 켜는 시각보다 늦어야 합니다.")
                false
            } }) { title ->
            val id = old?.optString("id") ?: UUID.randomUUID().toString()
            val zone = Zone.entries[area.selectedItemPosition]
            val enabled = old?.optBoolean("enabled") ?: false
            if (enabled) disableOtherRoutines(id, zone)
            memory.saveRoutine(JSONObject().put("id", id).put("name", title).put("zone", zone.name)
                .put("type", type).put("on", on).put("off", off).put("threshold", threshold)
                .put("duration", duration).put("enabled", enabled))
        }
    }

    private fun dataPage() {
        val voice = prefs.getBoolean("voice", false)
        body.block(button(if (voice) "완료 음성 안내 끄기" else "완료 음성 안내 켜기") {
            if (!voice && !speechReady) toast("기기에서 사용할 수 있는 한국어 오프라인 음성이 없습니다.")
            else { prefs.edit().putBoolean("voice", !voice).apply(); render() }
        })
        body.block(card().apply {
            block(text("최근 실행", 26f, bold = true))
            if (memory.history.isEmpty()) block(text("자동 실행 기록이 없습니다.", color = muted))
            memory.history.takeLast(8).asReversed().forEach {
                block(text("${date(it.getLong("time"))} · ${it.getString("message")}", 18f, muted))
            }
        })
        body.block(card().apply {
            val events = memory.events.objectsAsPoints()
            val legacy = memory.legacyEvents
            block(text("공조 조작 기록 ${events.size + legacy.size}건", 26f, bold = true))
            events.takeLast(12).asReversed().forEach {
                val delta = it.previousTarget?.let { previous -> " · ${it.control.display(previous)}에서 수동 변경" }.orEmpty()
                block(text("${date(it.time)} · ${it.zone.label} ${it.control.label} ${it.control.display(it.value)}$delta", 18f, muted))
            }
            legacy.takeLast((12 - events.size).coerceAtLeast(0)).asReversed().forEach {
                block(text("${date(it.optLong("time"))} · ${it.optString("value")}", 18f, muted))
            }
            block(button("조작 기록 삭제") {
                AlertDialog.Builder(this@MainActivity).setTitle("조작 기록 삭제")
                    .setMessage("진행 중 트래킹과 조작 기록을 삭제합니다. 저장한 공조 프로필은 보관됩니다.")
                    .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                        memory.clearRecords(); render()
                    }.show()
            })
        })
    }

    private fun List<JSONObject>.objectsAsPoints() = map(ClimatePoint::read)
    private fun date(time: Long) = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(time))
    private fun dialogForm() = column().apply {
        setPadding(dp(24), dp(12), dp(24), dp(12))
        isFocusableInTouchMode = true
    }
    private fun nameInput(hintText: String, initial: String = "") = EditText(this).apply {
        hint = hintText
        textSize = 22f
        setSingleLine()
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        filters = arrayOf(InputFilter.LengthFilter(32))
        setText(initial)
    }
    private fun picker(options: List<String>) = Spinner(this).apply {
        minimumHeight = dp(64)
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, options)
    }
    private fun adjuster(form: LinearLayout, name: String, unit: String, initial: Int, range: IntRange, change: (Int) -> Unit) {
        var value = initial
        form.block(row().apply {
            val label = text("$name  $value$unit", bold = true)
            addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            listOf("−" to -1, "+" to 1).forEach { (sign, step) ->
                addView(button(sign) {
                    value = (value + step).coerceIn(range)
                    label.text = "$name  $value$unit"
                    change(value)
                }.apply { contentDescription = "$name ${if (step < 0) "내리기" else "올리기"}" },
                    LinearLayout.LayoutParams(dp(68), -2).apply { marginStart = dp(8) })
            }
        })
    }
    private fun showForm(title: String, form: LinearLayout, name: EditText,
                         validate: () -> Boolean = { true }, save: (String) -> Unit) {
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("취소", null).setPositiveButton("저장", null).show()
        form.requestFocus()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = name.text.toString().trim()
            if (value.isEmpty()) { name.error = "이름을 입력하세요"; return@setOnClickListener }
            if (!validate()) return@setOnClickListener
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(name.windowToken, 0)
            save(value)
            dialog.dismiss()
            render()
        }
    }
    private fun confirmDelete(name: String, delete: () -> Unit) {
        AlertDialog.Builder(this).setTitle("삭제").setMessage("${name}을 삭제할까요?")
            .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ -> delete(); render() }.show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

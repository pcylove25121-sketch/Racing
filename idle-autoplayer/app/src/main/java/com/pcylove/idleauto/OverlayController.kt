package com.pcylove.idleauto

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 게임 화면 위에 떠 있는 것들을 관리해요.
 *  - 조작 패널: ▶/■ 재생, ＋ 타겟 추가, － 마지막 삭제, ● 녹화, ✕ 닫기
 *  - 타겟 마커: 번호가 적힌 동그라미. 끌어서 위치를 옮기면 바로 저장돼요.
 *  - 녹화 레이어: 화면 전체를 덮고 터치를 기록한 뒤, 같은 터치를 게임에 그대로 넣어 줘요.
 */
class OverlayController(private val service: AutoService) : Player.Listener {

    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)!!
    private val store = ScriptStore(service)
    private val handler = Handler(Looper.getMainLooper())
    private val player = Player(service, this)
    private val density = service.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var script: Script? = null

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var statusText: TextView? = null
    private var playBtn: TextView? = null
    private var recordBtn: TextView? = null

    private val markers = mutableListOf<Marker>()

    private var recordLayer: View? = null
    private var recordParams: WindowManager.LayoutParams? = null
    private var recording = false
    private var lastRecordedEndAt = 0L

    val isBusy: Boolean get() = player.isPlaying || recording
    val openScriptId: String? get() = script?.id

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    // ───────────────────────── 열기 / 닫기 ─────────────────────────

    fun open(s: Script) {
        close()
        script = s
        val size = screenSize()
        if (s.screenW != 0 && (s.screenW != size.x || s.screenH != size.y)) {
            Toast.makeText(
                service,
                "이 매크로는 ${s.screenW}×${s.screenH} 화면에서 만들었어요. 지금 화면(${size.x}×${size.y})과 달라 위치가 어긋날 수 있어요.",
                Toast.LENGTH_LONG
            ).show()
        }
        buildPanel()
        s.steps.forEach { if (it.type != StepType.WAIT) markers.add(Marker(it)) }
        renumberMarkers()
        setStatus("「${s.name}」 · ＋ 타겟 추가 · ● 녹화 · ▶ 시작")
    }

    /** 편집 화면에서 저장했을 때, 같은 매크로가 열려 있으면 다시 그려요. */
    fun reloadIfOpen(s: Script) {
        if (script?.id == s.id && !isBusy) open(s)
    }

    fun close() {
        if (recording) stopRecording(silent = true)
        if (player.isPlaying) player.stop("패널 닫힘")
        markers.forEach { it.remove() }
        markers.clear()
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
        statusText = null
        playBtn = null
        recordBtn = null
        script = null
    }

    fun emergencyStop() {
        if (recording) stopRecording()
        if (player.isPlaying) player.stop("볼륨 아래 버튼으로 정지")
    }

    // ───────────────────────── 패널 ─────────────────────────

    private fun buildPanel() {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xE6141226.toInt())
                setStroke(dp(1), 0x6622E8FF)
            }
        }
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val grip = makeButton("⠿", 0xFFA79FCC.toInt()).also { it.setOnTouchListener(PanelDrag()) }
        playBtn = makeButton("▶", 0xFF22E8FF.toInt()).also { it.setOnClickListener { togglePlay() } }
        val add = makeButton("＋", 0xFFF3EEFF.toInt()).also { it.setOnClickListener { addTargetAtCenter() } }
        val minus = makeButton("－", 0xFFF3EEFF.toInt()).also { it.setOnClickListener { removeLastStep() } }
        recordBtn = makeButton("●", 0xFFFF3355.toInt()).also { it.setOnClickListener { toggleRecording() } }
        val closeBtn = makeButton("✕", 0xFFA79FCC.toInt()).also { it.setOnClickListener { close() } }
        listOf(grip, playBtn!!, add, minus, recordBtn!!, closeBtn).forEach { row.addView(it) }

        statusText = TextView(service).apply {
            setTextColor(0xFFF3EEFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
            isSingleLine = true
            setPadding(dp(6), 0, dp(6), 0)
            maxWidth = dp(230)
            setOnTouchListener(PanelDrag())
        }
        root.addView(row)
        root.addView(statusText)

        val size = screenSize()
        panelParams = baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            x = dp(12)
            y = (size.y * 0.12f).toInt()
        }
        panel = root
        wm.addView(root, panelParams)
    }

    private fun makeButton(text: String, color: Int): TextView = TextView(service).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(40)
        minHeight = dp(40)
        setPadding(dp(4), 0, dp(4), 0)
        isClickable = true
        isFocusable = false
    }

    private fun bringPanelToFront() {
        val p = panel ?: return
        val lp = panelParams ?: return
        runCatching { wm.removeView(p) }
        runCatching { wm.addView(p, lp) }
    }

    private fun setStatus(text: String) {
        statusText?.text = text
    }

    private inner class PanelDrag : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val lp = panelParams ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = ev.rawX; startRawY = ev.rawY
                    startX = lp.x; startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val size = screenSize()
                    lp.x = (startX + (ev.rawX - startRawX)).roundToInt().coerceIn(0, max(0, size.x - dp(60)))
                    lp.y = (startY + (ev.rawY - startRawY)).roundToInt().coerceIn(0, max(0, size.y - dp(60)))
                    panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
                }
            }
            return true
        }
    }

    // ───────────────────────── 재생 ─────────────────────────

    private fun togglePlay() {
        val s = script ?: return
        if (player.isPlaying) {
            player.stop("정지")
            return
        }
        if (recording) stopRecording()
        if (s.steps.isEmpty()) {
            Toast.makeText(service, "먼저 ＋ 로 타겟을 추가하거나 ● 로 녹화해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        markers.forEach { it.setPassive(true) }
        setKeepScreenOn(true)
        playBtn?.text = "■"
        player.start(s)
    }

    override fun onProgress(loop: Int, stepIndex: Int, total: Int) {
        val s = script ?: return
        val sec = player.elapsedMs / 1000
        val loopLabel = if (s.loopCount > 0) "$loop/${s.loopCount}바퀴" else "${loop}바퀴"
        setStatus("▶ $loopLabel · 동작 ${stepIndex + 1}/$total · %d:%02d".format(sec / 60, sec % 60))
    }

    override fun onStopped(reason: String) {
        markers.forEach { it.setPassive(false) }
        setKeepScreenOn(false)
        playBtn?.text = "▶"
        setStatus("■ $reason")
    }

    private fun setKeepScreenOn(on: Boolean) {
        val p = panel ?: return
        val lp = panelParams ?: return
        lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        runCatching { wm.updateViewLayout(p, lp) }
    }

    // ───────────────────────── 타겟 마커 ─────────────────────────

    private fun addTargetAtCenter() {
        val s = script ?: return
        if (player.isPlaying) return
        val size = screenSize()
        val n = markers.size
        val step = Step(
            type = StepType.TAP,
            x = (size.x / 2f + (n % 5) * dp(28)).coerceIn(0f, size.x.toFloat()),
            y = (size.y / 2f + (n / 5) * dp(28)).coerceIn(0f, size.y.toFloat()),
            delayMs = s.defaultDelayMs,
        )
        s.steps.add(step)
        markers.add(Marker(step).also { if (recording) it.view.visibility = View.GONE })
        renumberMarkers()
        persist()
        setStatus("타겟 ${s.steps.size}번 추가 · 끌어서 위치를 맞춰요")
    }

    private fun removeLastStep() {
        val s = script ?: return
        if (player.isPlaying) return
        val last = s.steps.removeLastOrNull() ?: return
        markers.firstOrNull { it.step === last }?.let { it.remove(); markers.remove(it) }
        renumberMarkers()
        persist()
        setStatus("마지막 동작 삭제 · 남은 동작 ${s.steps.size}개")
    }

    private fun renumberMarkers() {
        val s = script ?: return
        markers.forEach { m -> m.setNumber(s.steps.indexOf(m.step) + 1) }
    }

    private fun persist() {
        val s = script ?: return
        val size = screenSize()
        s.screenW = size.x
        s.screenH = size.y
        store.upsert(s)
    }

    private inner class Marker(val step: Step) {
        private val size = dp(44)
        val view = FrameLayout(service)
        private val label = TextView(service)
        private val params = baseParams(size, size).apply {
            x = (step.x - size / 2f).roundToInt()
            y = (step.y - size / 2f).roundToInt()
        }
        private val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (step.type == StepType.SWIPE) 0xCCFF2BD6.toInt() else 0xCC22E8FF.toInt())
            setStroke(dp(2), Color.WHITE)
        }

        init {
            view.background = bg
            label.apply {
                setTextColor(0xFF07060F.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
            }
            view.addView(label, FrameLayout.LayoutParams(size, size))
            view.setOnTouchListener(Drag())
            wm.addView(view, params)
        }

        fun setNumber(n: Int) {
            label.text = when (step.type) {
                StepType.SWIPE -> "$n↗"
                StepType.LONG_PRESS -> "$n⏺"
                else -> "$n"
            }
        }

        /** 재생 중에는 터치가 마커에 막히지 않게 반투명·터치 불가로 바꿔요. */
        fun setPassive(passive: Boolean) {
            params.flags = if (passive) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            view.alpha = if (passive) 0.35f else 1f
            runCatching { wm.updateViewLayout(view, params) }
        }

        fun remove() {
            runCatching { wm.removeView(view) }
        }

        private inner class Drag : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false

            override fun onTouch(v: View, ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX; startRawY = ev.rawY
                        startX = params.x; startY = params.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - startRawX
                        val dy = ev.rawY - startRawY
                        if (hypot(dx, dy) > touchSlop) moved = true
                        if (moved) {
                            val scr = screenSize()
                            params.x = (startX + dx).roundToInt().coerceIn(-size / 2, scr.x - size / 2)
                            params.y = (startY + dy).roundToInt().coerceIn(-size / 2, scr.y - size / 2)
                            runCatching { wm.updateViewLayout(view, params) }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (moved) {
                            val newX = params.x + size / 2f
                            val newY = params.y + size / 2f
                            if (step.type == StepType.SWIPE) {
                                step.x2 += newX - step.x
                                step.y2 += newY - step.y
                            }
                            step.x = newX
                            step.y = newY
                            persist()
                            setStatus("${label.text}번 → (${newX.toInt()}, ${newY.toInt()}) 저장됨")
                        } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                            setStatus("${label.text}번 · ${step.describe()} · ${step.delayMs}ms 대기")
                        }
                    }
                }
                return true
            }
        }
    }

    // ───────────────────────── 녹화 ─────────────────────────

    private fun toggleRecording() {
        if (recording) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (script == null) return
        if (player.isPlaying) player.stop("녹화 시작")
        recording = true
        lastRecordedEndAt = 0L
        markers.forEach { it.view.visibility = View.GONE }

        val layer = FrameLayout(service).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), 0xCCFF3355.toInt())
            }
            addView(TextView(service).apply {
                text = "● 녹화 중 · 게임을 평소처럼 조작하세요 · 패널의 ■ 로 종료"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setBackgroundColor(0xAAFF3355.toInt())
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(24) })
            setOnTouchListener(RecorderTouch())
        }
        val lp = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        recordLayer = layer
        recordParams = lp
        wm.addView(layer, lp)
        bringPanelToFront()
        recordBtn?.text = "■"
        setStatus("● 녹화 중 · 터치가 그대로 게임에 전달돼요")
    }

    private fun stopRecording(silent: Boolean = false) {
        recording = false
        recordLayer?.let { runCatching { wm.removeView(it) } }
        recordLayer = null
        recordParams = null
        markers.forEach { it.view.visibility = View.VISIBLE }
        recordBtn?.text = "●"
        if (!silent) {
            val n = script?.steps?.size ?: 0
            setStatus("녹화 종료 · 동작 ${n}개 저장됨 · ▶ 로 재생")
        }
    }

    private inner class RecorderTouch : View.OnTouchListener {
        private var sx = 0f
        private var sy = 0f
        private var maxDist = 0f

        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val s = script ?: return true
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX; sy = ev.rawY; maxDist = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    maxDist = max(maxDist, hypot(ev.rawX - sx, ev.rawY - sy))
                }
                MotionEvent.ACTION_UP -> {
                    val ex = ev.rawX
                    val ey = ev.rawY
                    val dur = (ev.eventTime - ev.downTime).coerceAtLeast(1)
                    val dist = hypot(ex - sx, ey - sy)
                    val slop = touchSlop * 1.5f
                    val step = when {
                        dist < slop && maxDist < slop && dur < 350 ->
                            Step(StepType.TAP, sx, sy, durationMs = dur.coerceIn(1, 300))
                        dist < slop && maxDist < slop ->
                            Step(StepType.LONG_PRESS, sx, sy, durationMs = dur)
                        else ->
                            Step(StepType.SWIPE, sx, sy, ex, ey, durationMs = dur.coerceAtLeast(40))
                    }
                    step.delayMs = if (lastRecordedEndAt == 0L) 500L
                    else (ev.downTime - lastRecordedEndAt).coerceIn(0L, 60_000L)
                    lastRecordedEndAt = ev.eventTime

                    s.steps.add(step)
                    markers.add(Marker(step).also { it.view.visibility = View.GONE })
                    renumberMarkers()
                    persist()
                    setStatus("● ${s.steps.size}번 ${step.label} 기록 · 계속 조작하세요")
                    passThrough(step)
                }
            }
            return true
        }
    }

    /**
     * 녹화 레이어가 터치를 먹었으니, 같은 제스처를 게임에 넣어 줘요.
     * 넣는 동안은 레이어를 터치 불가로 바꿔서 우리가 넣은 터치가 다시 기록되지 않게 해요.
     */
    private fun passThrough(step: Step) {
        val layer = recordLayer ?: return
        val lp = recordParams ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(layer, lp) }
        handler.postDelayed({
            service.dispatchStep(step) {
                handler.post {
                    if (recording && recordLayer === layer) {
                        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                        runCatching { wm.updateViewLayout(layer, lp) }
                    }
                }
            }
        }, 60)
    }

    // ───────────────────────── 공용 ─────────────────────────

    private fun baseParams(w: Int, h: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 상태 바·내비 바·노치 영역까지 좌표계를 화면 전체와 맞춰요.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Point(dm.widthPixels, dm.heightPixels)
        }
    }
}

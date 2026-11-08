package com.pcylove.idleauto.limbus

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.pcylove.idleauto.AutoService

/**
 * 림버스 모드 패널. 지금은 "화면을 읽을 수 있는지" 확인하는 단계예요.
 *  📷 : 화면을 캡처해서 보정된 영역의 합 예측을 읽고, 결과를 패널에 표시. 캡처한 PNG 는 앱 폴더에 저장.
 *  ✕ : 닫기
 * 보정(영역 좌표·글자 색)이 끝나면 여기서 LimbusPlanner 로 배치를 계산하고 드래그까지 이어질 예정이에요.
 */
class LimbusOverlay(private val service: AutoService) {

    private val wm: WindowManager = service.getSystemService(WindowManager::class.java)!!
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density + 0.5f).toInt()

    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var busy = false
    private val reader: LimbusReader by lazy { LimbusReader(LabelTemplates.load(service)) }

    val isOpen: Boolean get() = panel != null

    fun open() {
        if (panel != null) return
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xE6261414.toInt())
                setStroke(dp(1), 0x66FF3355)
            }
        }
        val row = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val grip = button("⠿", 0xFFA79FCC.toInt()).also { it.setOnTouchListener(Drag()) }
        val shot = button("📷", 0xFFF3EEFF.toInt()).also { it.setOnClickListener { captureAndRead() } }
        val close = button("✕", 0xFFA79FCC.toInt()).also { it.setOnClickListener { close() } }
        listOf(grip, shot, close).forEach { row.addView(it) }
        status = TextView(service).apply {
            setTextColor(0xFFF3EEFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 4
            maxWidth = dp(260)
            setPadding(dp(6), 0, dp(6), 0)
            setOnTouchListener(Drag())
        }
        root.addView(row); root.addView(status)
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12); y = dp(60)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        }
        panel = root
        wm.addView(root, params)
        val profile = LimbusProfile.load(service)
        setStatus("림버스 모드 · ${profile.name}\n승률 버튼을 누른 뒤 📷 를 누르면 칼럼별 합 예측을 읽어요")
    }

    fun close() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null; params = null; status = null
    }

    private fun setStatus(s: String) { status?.text = s }

    private fun captureAndRead() {
        if (busy) return
        busy = true
        setStatus("캡처 중…")
        ScreenCapture.capture(service) { bmp, err ->
            busy = false
            if (bmp == null) { setStatus("캡처 실패: $err"); return@capture }
            val profile = LimbusProfile.load(service)
            val sb = StringBuilder("캡처 ${bmp.width}×${bmp.height}")
            if (bmp.width != profile.screenW || bmp.height != profile.screenH) {
                sb.append("\n프로필(${profile.screenW}×${profile.screenH})과 달라 좌표가 어긋날 수 있어요")
            }
            val t0 = System.currentTimeMillis()
            val obs = runCatching { reader.read(BitmapPixels(bmp).toArray()) }.getOrNull()
            val ms = System.currentTimeMillis() - t0
            if (obs == null) {
                sb.append("\n인식 오류")
                ScreenCapture.saveDebug(service, bmp)?.let { sb.append("\n저장: ${it.name}") }
            } else {
                sb.append("\n${obs.summary()} (${ms}ms)")
                val annotated = runCatching { LimbusDebug.annotate(bmp, obs) }.getOrNull()
                val saved = ScreenCapture.saveDebug(service, annotated ?: bmp, "limbus_read")
                sb.append("\n저장: ${saved?.name ?: "실패"}")
                annotated?.recycle()
            }
            setStatus(sb.toString())
            bmp.recycle()
        }
    }

    private fun button(text: String, color: Int): TextView = TextView(service).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(40); minHeight = dp(40)
        isClickable = true; isFocusable = false
    }

    private inner class Drag : View.OnTouchListener {
        private var sx = 0f; private var sy = 0f; private var ox = 0; private var oy = 0
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val lp = params ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (ox + (ev.rawX - sx)).toInt().coerceAtLeast(0)
                    lp.y = (oy + (ev.rawY - sy)).toInt().coerceAtLeast(0)
                    panel?.let { runCatching { wm.updateViewLayout(it, lp) } }
                }
            }
            return true
        }
    }
}

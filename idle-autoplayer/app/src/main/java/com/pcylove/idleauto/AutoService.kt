package com.pcylove.idleauto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 접근성 서비스. 두 가지 일을 해요.
 *  1) 게임 화면에 실제 터치(탭/스와이프)를 넣어 줘요 (dispatchGesture).
 *  2) 화면 위에 떠 있는 조작 패널/타겟 마커를 보여 줘요 (TYPE_ACCESSIBILITY_OVERLAY, 별도 권한 불필요).
 * 재생 중 볼륨 아래 버튼을 누르면 즉시 멈춰요 (비상 정지).
 */
class AutoService : AccessibilityService() {

    lateinit var overlay: OverlayController
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayController(this)
        instance = this
        Toast.makeText(this, "방치형 오토 준비 완료. 앱에서 매크로를 열어 주세요.", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 화면 내용은 읽지 않아요. 제스처 전달과 패널 표시에만 쓰는 서비스예요.
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && this::overlay.isInitialized && overlay.isBusy) {
            if (event.action == KeyEvent.ACTION_DOWN) overlay.emergencyStop()
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (this::overlay.isInitialized) overlay.close()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (this::overlay.isInitialized) overlay.close()
        instance = null
        super.onDestroy()
    }

    /** 한 동작을 실제 터치로 보내요. 끝나면(취소돼도) onDone 을 꼭 불러요. */
    fun dispatchStep(step: Step, onDone: () -> Unit) {
        if (step.type == StepType.WAIT) {
            onDone(); return
        }
        val maxDur = GestureDescription.getMaxGestureDuration()
        val path = Path()
        val x = step.x.coerceAtLeast(0f)
        val y = step.y.coerceAtLeast(0f)
        path.moveTo(x, y)
        val duration: Long = when (step.type) {
            StepType.TAP -> step.durationMs.coerceIn(1, 300)
            StepType.LONG_PRESS -> step.durationMs.coerceIn(400, maxDur)
            StepType.SWIPE -> {
                path.lineTo(step.x2.coerceAtLeast(0f), step.y2.coerceAtLeast(0f))
                step.durationMs.coerceIn(40, maxDur)
            }
            StepType.WAIT -> 1
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        var finished = false
        val finish = {
            if (!finished) {
                finished = true
                onDone()
            }
        }
        val accepted = try {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = finish()
                override fun onCancelled(gestureDescription: GestureDescription?) = finish()
            }, null)
        } catch (e: Exception) {
            false
        }
        if (!accepted) finish()
    }

    companion object {
        @Volatile
        var instance: AutoService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }
}

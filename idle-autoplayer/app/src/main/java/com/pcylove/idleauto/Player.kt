package com.pcylove.idleauto

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 매크로 재생기. 메인 스레드 Handler 로 한 동작씩 예약해서 실행해요.
 * token 으로 "정지 후 늦게 도착한 콜백"을 무시해요.
 */
class Player(private val service: AutoService, private val listener: Listener) {

    interface Listener {
        fun onProgress(loop: Int, stepIndex: Int, total: Int)
        fun onStopped(reason: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var script: Script? = null
    private var stepIndex = 0
    private var loop = 0
    private var startedAt = 0L
    private var token = 0

    var isPlaying = false
        private set

    val elapsedMs: Long
        get() = if (isPlaying) SystemClock.uptimeMillis() - startedAt else 0L

    fun start(s: Script) {
        if (isPlaying) stop("다시 시작")
        if (s.steps.isEmpty()) {
            listener.onStopped("실행할 동작이 없어요")
            return
        }
        script = s
        isPlaying = true
        stepIndex = 0
        loop = 0
        startedAt = SystemClock.uptimeMillis()
        token++
        scheduleNext(token, s.steps[0].delayMs)
    }

    fun stop(reason: String = "정지") {
        if (!isPlaying) return
        isPlaying = false
        token++
        handler.removeCallbacksAndMessages(null)
        listener.onStopped(reason)
    }

    private fun scheduleNext(t: Int, delay: Long) {
        handler.postDelayed({ if (t == token && isPlaying) runStep(t) }, delay.coerceAtLeast(0))
    }

    private fun runStep(t: Int) {
        val s = script ?: return
        if (s.timeLimitMin > 0 && elapsedMs >= s.timeLimitMin * 60_000L) {
            stop("시간 제한(${s.timeLimitMin}분) 도달")
            return
        }
        if (stepIndex >= s.steps.size) {
            stepIndex = 0
        }
        val step = s.steps[stepIndex]
        listener.onProgress(loop + 1, stepIndex, s.steps.size)
        val onDone = Runnable { if (t == token && isPlaying) advance(t) }
        if (step.type == StepType.WAIT) {
            handler.postDelayed(onDone, step.durationMs.coerceAtLeast(0))
        } else {
            service.dispatchStep(step) { handler.post(onDone) }
        }
    }

    private fun advance(t: Int) {
        val s = script ?: return
        stepIndex++
        if (stepIndex >= s.steps.size) {
            stepIndex = 0
            loop++
            if (s.loopCount > 0 && loop >= s.loopCount) {
                stop("${s.loopCount}회 반복 완료")
                return
            }
            scheduleNext(t, s.loopDelayMs + s.steps[0].delayMs)
        } else {
            scheduleNext(t, s.steps[stepIndex].delayMs)
        }
    }
}

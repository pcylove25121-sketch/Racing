package com.pcylove.idleauto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** 매크로 이름·반복 설정·동작별 간격을 손으로 고치는 화면. 나갈 때 자동 저장돼요. */
class EditorActivity : Activity() {

    private lateinit var store: ScriptStore
    private lateinit var script: Script
    private lateinit var content: LinearLayout

    private lateinit var nameField: EditText
    private lateinit var loopField: EditText
    private lateinit var loopDelayField: EditText
    private lateinit var limitField: EditText
    private lateinit var defaultDelayField: EditText
    private val stepFields = mutableListOf<Triple<Step, EditText, EditText?>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ScriptStore(this)
        val id = intent.getStringExtra(EXTRA_ID)
        val found = id?.let { store.get(it) }
        if (found == null) {
            Toast.makeText(this, "매크로를 찾을 수 없어요.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        script = found
        title = "매크로 편집"
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    private fun render() {
        content.removeAllViews()
        stepFields.clear()

        val general = card()
        general.addView(text("기본 설정", 17f, bold = true))
        general.space(6)
        nameField = EditText(this).apply { setText(script.name); setSingleLine(); hint = "매크로 이름" }
        general.addView(labeled("이름", nameField))
        loopField = numberField(script.loopCount.toLong())
        general.addView(labeled("반복 횟수", loopField, "0 이면 멈출 때까지 무한 반복"))
        loopDelayField = numberField(script.loopDelayMs)
        general.addView(labeled("바퀴 사이 대기 (ms)", loopDelayField, "모든 동작을 한 번 끝내고 다음 바퀴 전까지 기다리는 시간"))
        limitField = numberField(script.timeLimitMin.toLong())
        general.addView(labeled("자동 정지 (분)", limitField, "0 이면 제한 없음. 예: 480 이면 8시간 뒤 멈춤"))
        defaultDelayField = numberField(script.defaultDelayMs)
        general.addView(labeled("새 타겟 기본 대기 (ms)", defaultDelayField, "패널에서 ＋ 로 타겟을 추가할 때 적용되는 간격"))
        content.addView(general)

        val stepsCard = card()
        stepsCard.addView(text("동작 ${script.steps.size}개", 17f, bold = true))
        stepsCard.addView(text("각 동작의 「대기」는 그 동작을 하기 전에 기다리는 시간이에요. 좌표는 패널에서 마커를 끌어 바꿔요.", 13f, color = 0xFF6F6A8A.toInt()))
        stepsCard.space(6)
        if (script.steps.isEmpty()) {
            stepsCard.addView(text("동작이 없어요. 패널의 ＋ 또는 ● 로 추가하거나, 아래에서 대기 동작을 넣어요.", 14f, color = 0xFF6F6A8A.toInt()))
        }
        script.steps.forEachIndexed { i, step ->
            val block = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            block.addView(text("${i + 1}. ${step.describe()}", 14f, bold = true))
            val delayField = numberField(step.delayMs, 90)
            val durField = if (step.type == StepType.TAP) null else numberField(step.durationMs, 90)
            val controls = row(
                text("대기(ms) ", 13f), delayField,
            )
            if (durField != null) {
                controls.addView(text("  지속(ms) ", 13f))
                controls.addView(durField)
            }
            block.addView(controls)
            block.addView(
                row(
                    button("↑") { collect(); moveStep(i, -1) },
                    button("↓") { collect(); moveStep(i, +1) },
                    button("삭제") { collect(); script.steps.removeAt(i); render() },
                )
            )
            stepFields.add(Triple(step, delayField, durField))
            stepsCard.addView(block)
        }
        stepsCard.space(6)
        stepsCard.addView(button("＋ 대기 동작 추가 (1초)") {
            collect()
            script.steps.add(Step(StepType.WAIT, durationMs = 1000, delayMs = 0))
            render()
        })
        content.addView(stepsCard)

        val actions = card()
        actions.addView(button("저장하고 패널 열기") {
            collect(); save()
            val service = AutoService.instance
            if (service == null) {
                Toast.makeText(this, "먼저 접근성 서비스를 켜 주세요.", Toast.LENGTH_LONG).show()
            } else {
                service.overlay.open(script)
                Toast.makeText(this, "패널이 떠 있어요. 이제 게임을 실행하세요.", Toast.LENGTH_LONG).show()
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        actions.addView(button("저장") { collect(); save(); Toast.makeText(this, "저장했어요.", Toast.LENGTH_SHORT).show() })
        content.addView(actions)
    }

    private fun moveStep(i: Int, dir: Int) {
        val j = i + dir
        if (j < 0 || j >= script.steps.size) return
        val tmp = script.steps[i]
        script.steps[i] = script.steps[j]
        script.steps[j] = tmp
        render()
    }

    private fun collect() {
        if (!this::nameField.isInitialized) return
        script.name = nameField.text.toString().trim().ifEmpty { "새 매크로" }
        script.loopCount = loopField.intValue(0).coerceAtLeast(0)
        script.loopDelayMs = loopDelayField.longValue(1000).coerceAtLeast(0)
        script.timeLimitMin = limitField.intValue(0).coerceAtLeast(0)
        script.defaultDelayMs = defaultDelayField.longValue(500).coerceAtLeast(0)
        stepFields.forEach { (step, delay, dur) ->
            step.delayMs = delay.longValue(step.delayMs).coerceAtLeast(0)
            dur?.let { step.durationMs = it.longValue(step.durationMs).coerceAtLeast(1) }
        }
    }

    private fun save() {
        store.upsert(script)
        AutoService.instance?.overlay?.reloadIfOpen(script)
    }

    override fun onPause() {
        super.onPause()
        if (this::script.isInitialized) {
            collect()
            save()
        }
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}

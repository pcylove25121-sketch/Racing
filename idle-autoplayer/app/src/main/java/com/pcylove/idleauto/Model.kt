package com.pcylove.idleauto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 한 번의 동작. delayMs 는 "이 동작을 하기 전에 기다리는 시간"이에요. */
enum class StepType { TAP, LONG_PRESS, SWIPE, WAIT }

data class Step(
    var type: StepType = StepType.TAP,
    var x: Float = 0f,
    var y: Float = 0f,
    var x2: Float = 0f,
    var y2: Float = 0f,
    /** 탭/길게 누르기/스와이프가 걸리는 시간, WAIT 는 기다리는 시간 */
    var durationMs: Long = 60,
    /** 이 동작 전에 기다리는 시간 */
    var delayMs: Long = 500,
) {
    val label: String
        get() = when (type) {
            StepType.TAP -> "탭"
            StepType.LONG_PRESS -> "길게 누르기"
            StepType.SWIPE -> "스와이프"
            StepType.WAIT -> "대기"
        }

    fun describe(): String = when (type) {
        StepType.TAP -> "탭 (${x.toInt()}, ${y.toInt()})"
        StepType.LONG_PRESS -> "길게 누르기 ${durationMs}ms (${x.toInt()}, ${y.toInt()})"
        StepType.SWIPE -> "스와이프 (${x.toInt()}, ${y.toInt()}) → (${x2.toInt()}, ${y2.toInt()}) ${durationMs}ms"
        StepType.WAIT -> "대기 ${durationMs}ms"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.name)
        put("x", x.toDouble()); put("y", y.toDouble())
        put("x2", x2.toDouble()); put("y2", y2.toDouble())
        put("duration", durationMs); put("delay", delayMs)
    }

    companion object {
        fun fromJson(o: JSONObject): Step = Step(
            type = runCatching { StepType.valueOf(o.optString("type", "TAP")) }.getOrDefault(StepType.TAP),
            x = o.optDouble("x", 0.0).toFloat(),
            y = o.optDouble("y", 0.0).toFloat(),
            x2 = o.optDouble("x2", 0.0).toFloat(),
            y2 = o.optDouble("y2", 0.0).toFloat(),
            durationMs = o.optLong("duration", 60),
            delayMs = o.optLong("delay", 500),
        )
    }
}

/** 매크로 하나. 게임 하나당 하나씩 만들어 쓰면 편해요. */
data class Script(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "새 매크로",
    var steps: MutableList<Step> = mutableListOf(),
    /** 0 = 무한 반복 */
    var loopCount: Int = 0,
    /** 한 바퀴 돌고 다음 바퀴 시작 전 대기 */
    var loopDelayMs: Long = 1000,
    /** 0 = 제한 없음 (분) */
    var timeLimitMin: Int = 0,
    /** 패널에서 ＋ 로 타겟을 추가할 때 기본 대기 시간 */
    var defaultDelayMs: Long = 500,
    /** 녹화/배치 당시 화면 크기 (다른 해상도 경고용) */
    var screenW: Int = 0,
    var screenH: Int = 0,
) {
    fun summary(): String {
        val loop = if (loopCount <= 0) "무한 반복" else "${loopCount}회 반복"
        val limit = if (timeLimitMin <= 0) "" else " · ${timeLimitMin}분 후 자동 정지"
        return "동작 ${steps.size}개 · $loop$limit"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("loopCount", loopCount); put("loopDelay", loopDelayMs)
        put("timeLimitMin", timeLimitMin); put("defaultDelay", defaultDelayMs)
        put("screenW", screenW); put("screenH", screenH)
        put("steps", JSONArray().also { arr -> steps.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Script {
            val steps = mutableListOf<Step>()
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) steps.add(Step.fromJson(arr.getJSONObject(i)))
            return Script(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "새 매크로"),
                steps = steps,
                loopCount = o.optInt("loopCount", 0),
                loopDelayMs = o.optLong("loopDelay", 1000),
                timeLimitMin = o.optInt("timeLimitMin", 0),
                defaultDelayMs = o.optLong("defaultDelay", 500),
                screenW = o.optInt("screenW", 0),
                screenH = o.optInt("screenH", 0),
            )
        }
    }
}

/** SharedPreferences 에 JSON 으로 저장. 매크로 몇 개 수준이라 이걸로 충분해요. */
class ScriptStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("scripts", Context.MODE_PRIVATE)

    fun loadAll(): MutableList<Script> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { Script.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(mutableListOf())
    }

    fun saveAll(list: List<Script>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun get(id: String): Script? = loadAll().firstOrNull { it.id == id }

    fun upsert(script: Script) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.id == script.id }
        if (idx >= 0) list[idx] = script else list.add(script)
        saveAll(list)
    }

    fun delete(id: String) {
        saveAll(loadAll().filterNot { it.id == id })
    }

    companion object {
        private const val KEY = "list"
    }
}

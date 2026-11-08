package com.pcylove.idleauto.limbus

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

/**
 * 화면 읽기 보정값. 기기/해상도마다 다르고, 실제 전투 스크린샷으로 맞춰야 해요.
 * 좌표는 화면 폭·높이에 대한 비율(0.0~1.0)로 저장해서 같은 비율의 기기면 그대로 써요.
 *
 * 갤럭시 S25: 가로 모드 2340×1080 (19.5:9). 아직 영역 좌표와 글자 색은 미보정이에요.
 */
data class LimbusProfile(
    var name: String = "Galaxy S25 (2340×1080 가로)",
    var screenW: Int = 2340,
    var screenH: Int = 1080,
    /** 합 예측 글자 색 샘플 (라벨 → RGB). 스크린샷에서 뽑아 넣어요. */
    var labelColors: MutableMap<Clash, Int> = mutableMapOf(),
    /** 수감자 스킬 카드 영역들 (왼쪽부터). 예측 글자는 각 카드 위쪽에 떠요. */
    var skillCards: MutableList<RectF> = mutableListOf(),
    /** 적 체력 바 영역들 (왼쪽부터) */
    var enemyHpBars: MutableList<RectF> = mutableListOf(),
    /** 전투 시작 버튼 중심 (비율) */
    var startButton: FloatArray = floatArrayOf(0f, 0f),
) {
    val needsCalibration: Boolean
        get() = labelColors.size < 5 || skillCards.isEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name); put("screenW", screenW); put("screenH", screenH)
        put("labelColors", JSONObject().also { o -> labelColors.forEach { (k, v) -> o.put(k.name, v) } })
        put("skillCards", rects(skillCards)); put("enemyHpBars", rects(enemyHpBars))
        put("startButton", JSONArray().put(startButton[0].toDouble()).put(startButton[1].toDouble()))
    }

    private fun rects(list: List<RectF>) = JSONArray().also { a ->
        list.forEach { r -> a.put(JSONArray().put(r.left.toDouble()).put(r.top.toDouble()).put(r.right.toDouble()).put(r.bottom.toDouble())) }
    }

    companion object {
        private const val PREF = "limbus_profile"

        fun fromJson(o: JSONObject): LimbusProfile {
            val p = LimbusProfile(
                name = o.optString("name", "보정 프로필"),
                screenW = o.optInt("screenW", 2340), screenH = o.optInt("screenH", 1080),
            )
            o.optJSONObject("labelColors")?.let { lc ->
                lc.keys().forEach { k -> runCatching { Clash.valueOf(k) }.getOrNull()?.let { p.labelColors[it] = lc.getInt(k) } }
            }
            fun readRects(key: String): MutableList<RectF> {
                val out = mutableListOf<RectF>()
                val a = o.optJSONArray(key) ?: return out
                for (i in 0 until a.length()) {
                    val r = a.getJSONArray(i)
                    out += RectF(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat())
                }
                return out
            }
            p.skillCards = readRects("skillCards")
            p.enemyHpBars = readRects("enemyHpBars")
            o.optJSONArray("startButton")?.let { if (it.length() == 2) p.startButton = floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) }
            return p
        }

        fun load(context: Context): LimbusProfile {
            val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("json", null)
            return raw?.let { runCatching { fromJson(JSONObject(it)) }.getOrNull() } ?: LimbusProfile()
        }

        fun save(context: Context, p: LimbusProfile) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("json", p.toJson().toString()).apply()
        }
    }
}

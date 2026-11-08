package com.pcylove.idleauto.limbus

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 림버스 전투 화면(갤럭시 S25, 2340×1080 가로) 인식기.
 * 실제 스크린샷 두 장으로 맞춘 규칙이고, 테스트(app/src/test)에서 같은 스크린샷으로 검증돼요.
 *
 *  - 스킬 칼럼: 하단 속도 숫자(노란-흰 글자, y 865~910)의 위치로 칼럼 중심을 찾아요. 7칸이면 x ≈ 708, 856, …, 1597.
 *  - 합 예측 라벨: 칼럼 위쪽(y 725~890)에서 라벨 템플릿("매우 우세", "우세", "일방공격" …)을 절반 해상도로 템플릿 매칭.
 *  - 적 스킬 슬롯: 빨간 육각형 테두리 덩어리 중, 바로 위에 노란 속도 숫자가 있는 것.
 */
class LimbusReader(private val templates: Map<LabelKind, GrayImage>) {

    enum class LabelKind(val label: String, val clash: Clash?) {
        DOMINATING("매우 우세", Clash.DOMINATING),
        FAVORED("우세", Clash.FAVORED),
        NEUTRAL("보통", Clash.NEUTRAL),
        STRUGGLING("열세", Clash.STRUGGLING),
        HOPELESS("매우 열세", Clash.HOPELESS),
        ONE_SIDED("일방공격", null),
        DEFENSE("방어/반격", null),
    }

    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
        val w: Int get() = right - left + 1
        val h: Int get() = bottom - top + 1
    }

    data class Column(val index: Int, val centerX: Int, val label: LabelKind?, val score: Float, val labelRect: Rect?)
    data class EnemySlot(val rect: Rect, val speedDigitRect: Rect)
    data class Observation(val columns: List<Column>, val enemySlots: List<EnemySlot>) {
        fun summary(): String {
            val cols = columns.joinToString(" ") { c -> "${c.index + 1}:${c.label?.label ?: "-"}" }
            return "칼럼 ${columns.size}개 [$cols] · 적 슬롯 ${enemySlots.size}개"
        }
    }

    fun read(px: Pixels): Observation {
        val centers = findColumns(px)
        val cols = centers.mapIndexed { i, cx -> readLabel(px, i, cx) }
        return Observation(cols, findEnemySlots(px))
    }

    // ───────── 칼럼 (속도 숫자) ─────────

    fun findColumns(px: Pixels): List<Int> {
        val digits = Blobs.find(px, Rect(560, 865, 1760, 910), ::isSpeedDigit, 8, 40, 20, 45, 0.2f)
            .sortedBy { it.cx }
        val groups = mutableListOf<MutableList<Int>>()
        for (d in digits) {
            if (groups.isNotEmpty() && d.cx - groups.last().last() < 60) groups.last().add(d.cx) else groups.add(mutableListOf(d.cx))
        }
        return groups.map { it.sum() / it.size }
    }

    // ───────── 라벨 (템플릿 매칭) ─────────

    private fun readLabel(px: Pixels, index: Int, cx: Int): Column {
        val region = Rect((cx - 85).coerceAtLeast(0), 720, (cx + 85).coerceAtMost(px.width - 1), 895)
        val half = ArrayPixels.crop(px, region.left, region.top, region.right + 1, region.bottom + 1).halfGray()
        var best: LabelKind? = null
        var bestScore = 0f
        var bestPos: Pair<Int, Int>? = null
        var bestTpl: GrayImage? = null
        for ((kind, tpl) in templates) {
            val (score, pos) = Ncc.bestMatch(half, tpl)
            if (score > bestScore) { bestScore = score; best = kind; bestPos = pos; bestTpl = tpl }
        }
        val threshold = if (best != null && best.label.length <= 2) THRESHOLD_SHORT else THRESHOLD
        return if (best != null && bestScore >= threshold && bestPos != null && bestTpl != null) {
            val (hx, hy) = bestPos
            Column(index, cx, best, bestScore, Rect(region.left + hx * 2, region.top + hy * 2, region.left + (hx + bestTpl.width) * 2, region.top + (hy + bestTpl.height) * 2))
        } else Column(index, cx, null, bestScore, null)
    }

    // ───────── 적 스킬 슬롯 ─────────

    fun findEnemySlots(px: Pixels): List<EnemySlot> {
        val rings = Blobs.find(px, Rect(1100, 90, 2200, 720), ::isSlotRing, 45, 110, 45, 110, 0.15f)
        val out = mutableListOf<EnemySlot>()
        for (r in rings) {
            val search = Rect((r.cx - 45).coerceAtLeast(0), (r.top - 95).coerceAtLeast(0), (r.cx + 45).coerceAtMost(px.width - 1), (r.top - 5).coerceAtLeast(0))
            if (search.bottom <= search.top) continue
            val digit = Blobs.find(px, search, ::isSpeedDigit, 8, 40, 18, 45, 0.2f).firstOrNull() ?: continue
            out += EnemySlot(r, digit)
        }
        return out.sortedBy { it.rect.cx }
    }

    companion object {
        const val THRESHOLD = 0.70f        // 4글자 라벨
        const val THRESHOLD_SHORT = 0.80f  // 2글자 라벨 (배경 영향이 커서 더 엄격)

        /** 노란-흰 속도 숫자 */
        fun isSpeedDigit(c: Int): Boolean {
            val r = Rgb.r(c); val g = Rgb.g(c); val b = Rgb.b(c)
            return r > 200 && g > 200 && b in 61..199 && abs(r - g) < 40
        }

        /** 적 슬롯의 빨간 육각형 테두리 */
        fun isSlotRing(c: Int): Boolean = Rgb.dist(c, 0x963220) <= 70
    }
}

/** 색 조건을 만족하는 픽셀 덩어리(연결 요소) 찾기 */
object Blobs {
    fun find(
        px: Pixels, region: LimbusReader.Rect, pred: (Int) -> Boolean,
        minW: Int, maxW: Int, minH: Int, maxH: Int, minFill: Float,
    ): List<LimbusReader.Rect> {
        val x0 = region.left.coerceAtLeast(0); val y0 = region.top.coerceAtLeast(0)
        val x1 = region.right.coerceAtMost(px.width - 1); val y1 = region.bottom.coerceAtMost(px.height - 1)
        if (x1 <= x0 || y1 <= y0) return emptyList()
        val w = x1 - x0 + 1; val h = y1 - y0 + 1
        val seen = BooleanArray(w * h)
        val out = mutableListOf<LimbusReader.Rect>()
        val stack = IntArray(w * h)
        for (y in y0..y1) for (x in x0..x1) {
            val i = (y - y0) * w + (x - x0)
            if (seen[i]) continue
            seen[i] = true
            if (!pred(px.get(x, y))) continue
            var sp = 0
            stack[sp++] = i
            var minX = x; var maxX = x; var minY = y; var maxY = y; var count = 0
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = x0 + cur % w; val cy = y0 + cur / w
                count++
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx; if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                for ((nx, ny) in arrayOf(cx + 1 to cy, cx - 1 to cy, cx to cy + 1, cx to cy - 1)) {
                    if (nx < x0 || nx > x1 || ny < y0 || ny > y1) continue
                    val j = (ny - y0) * w + (nx - x0)
                    if (seen[j]) continue
                    seen[j] = true
                    if (pred(px.get(nx, ny))) stack[sp++] = j
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (bw in minW..maxW && bh in minH..maxH && count >= bw * bh * minFill) out += LimbusReader.Rect(minX, minY, maxX, maxY)
        }
        return out
    }
}

/** 정규화 상호상관 템플릿 매칭 (회색조) */
object Ncc {
    /** 반환: (최고 점수, 템플릿 좌상단 위치) */
    fun bestMatch(img: GrayImage, tpl: GrayImage): Pair<Float, Pair<Int, Int>?> {
        val tw = tpl.width; val th = tpl.height
        if (tw > img.width || th > img.height) return 0f to null
        var tMean = 0f
        for (v in tpl.data) tMean += v
        tMean /= tpl.data.size
        val t = FloatArray(tpl.data.size) { tpl.data[it] - tMean }
        var tNorm = 0f
        for (v in t) tNorm += v * v
        tNorm = sqrt(tNorm) + 1e-6f
        var best = -1f; var bestPos: Pair<Int, Int>? = null
        val n = tw * th
        for (y in 0..img.height - th) for (x in 0..img.width - tw) {
            var sum = 0f
            for (j in 0 until th) { val row = (y + j) * img.width + x; for (i in 0 until tw) sum += img.data[row + i] }
            val mean = sum / n
            var num = 0f; var den = 0f
            for (j in 0 until th) {
                val row = (y + j) * img.width + x
                val trow = j * tw
                for (i in 0 until tw) { val d = img.data[row + i] - mean; num += d * t[trow + i]; den += d * d }
            }
            val score = num / (sqrt(den) * tNorm + 1e-6f)
            if (score > best) { best = score; bestPos = x to y }
        }
        return best to bestPos
    }
}

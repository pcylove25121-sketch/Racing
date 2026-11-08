package com.pcylove.idleauto.limbus

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 스크린샷의 한 영역에서 합 예측 글자를 색으로 읽어요.
 * 보정된 라벨 색(labelColors)과 가까운 픽셀을 세어서, 가장 많은 라벨을 고르고 너무 적으면 UNKNOWN.
 * 글자 인식(OCR)보다 빠르고 폰트가 바뀌어도 색만 같으면 동작해요.
 */
object LabelReader {

    data class Reading(val label: Clash, val pixels: Int, val total: Int) {
        val confidence: Float get() = if (total == 0) 0f else pixels.toFloat() / total
    }

    /** 두 색의 거리 (각 채널 차이의 합, 0~765) */
    fun colorDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    fun read(bmp: Bitmap, region: RectF, labelColors: Map<Clash, Int>, tolerance: Int = 90, minPixels: Int = 12, step: Int = 2): Reading {
        if (labelColors.isEmpty()) return Reading(Clash.UNKNOWN, 0, 0)
        val l = (region.left * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
        val t = (region.top * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)
        val r = (region.right * bmp.width).roundToInt().coerceIn(l + 1, bmp.width)
        val b = (region.bottom * bmp.height).roundToInt().coerceIn(t + 1, bmp.height)
        val counts = IntArray(Clash.entries.size)
        var total = 0
        var y = t
        while (y < b) {
            var x = l
            while (x < r) {
                val c = bmp.getPixel(x, y)
                total++
                var best: Clash? = null
                var bestD = tolerance + 1
                for ((label, ref) in labelColors) {
                    val d = colorDistance(c, ref)
                    if (d < bestD) { bestD = d; best = label }
                }
                if (best != null) counts[best.ordinal]++
                x += step
            }
            y += step
        }
        val idx = counts.indices.maxByOrNull { counts[it] } ?: return Reading(Clash.UNKNOWN, 0, total)
        val n = counts[idx]
        return if (n < minPixels) Reading(Clash.UNKNOWN, n, total) else Reading(Clash.entries[idx], n, total)
    }

    /** 체력 바 영역에서 채워진 비율(0~1)을 읽어요. 바 색(barColor)과 가까운 픽셀이 왼쪽부터 어디까지 이어지는지로 계산. */
    fun readBarRatio(bmp: Bitmap, region: RectF, barColor: Int, tolerance: Int = 90): Float {
        val l = (region.left * bmp.width).roundToInt().coerceIn(0, bmp.width - 1)
        val r = (region.right * bmp.width).roundToInt().coerceIn(l + 1, bmp.width)
        val y = ((region.top + region.bottom) / 2f * bmp.height).roundToInt().coerceIn(0, bmp.height - 1)
        var filled = 0
        for (x in l until r) if (colorDistance(bmp.getPixel(x, y), barColor) <= tolerance) filled++
        return filled.toFloat() / (r - l)
    }
}

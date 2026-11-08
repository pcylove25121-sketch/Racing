package com.pcylove.idleauto.limbus

/**
 * 화면 픽셀에 접근하는 최소 인터페이스.
 * 안드로이드에서는 Bitmap 을, JVM 테스트에서는 ImageIO 로 읽은 배열을 감싸서 같은 인식 코드를 돌려요.
 */
interface Pixels {
    val width: Int
    val height: Int
    /** 0xRRGGBB (알파 무시) */
    fun get(x: Int, y: Int): Int
}

class ArrayPixels(override val width: Int, override val height: Int, private val data: IntArray) : Pixels {
    override fun get(x: Int, y: Int): Int = data[y * width + x] and 0xFFFFFF

    /** 2×2 평균으로 절반 크기 회색조 (템플릿 매칭용) */
    fun halfGray(): GrayImage {
        val w = width / 2; val h = height / 2
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            for (dy in 0..1) for (dx in 0..1) sum += gray(get(x * 2 + dx, y * 2 + dy))
            out[y * w + x] = sum / 4f
        }
        return GrayImage(w, h, out)
    }

    companion object {
        fun gray(rgb: Int): Float {
            val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000f
        }
        fun crop(src: Pixels, left: Int, top: Int, right: Int, bottom: Int): ArrayPixels {
            val l = left.coerceIn(0, src.width); val t = top.coerceIn(0, src.height)
            val r = right.coerceIn(l, src.width); val b = bottom.coerceIn(t, src.height)
            val w = r - l; val h = b - t
            val d = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) d[y * w + x] = src.get(l + x, t + y)
            return ArrayPixels(w, h, d)
        }
    }
}

class GrayImage(val width: Int, val height: Int, val data: FloatArray) {
    fun get(x: Int, y: Int): Float = data[y * width + x]
}

object Rgb {
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun dist(a: Int, b: Int): Int = Math.abs(r(a) - r(b)) + Math.abs(g(a) - g(b)) + Math.abs(b(a) - b(b))
}

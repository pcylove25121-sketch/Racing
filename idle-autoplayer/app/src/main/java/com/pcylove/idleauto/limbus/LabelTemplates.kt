package com.pcylove.idleauto.limbus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** assets 의 limbus/labels 폴더에 든 라벨 템플릿 PNG 를 읽어요. (스크린샷에서 잘라낸 원본 크기, 매칭은 절반 해상도) */
object LabelTemplates {
    private val files = mapOf(
        LimbusReader.LabelKind.DOMINATING to "dominating.png",
        LimbusReader.LabelKind.FAVORED to "favored.png",
        LimbusReader.LabelKind.NEUTRAL to "neutral.png",
        LimbusReader.LabelKind.STRUGGLING to "struggling.png",
        LimbusReader.LabelKind.HOPELESS to "hopeless.png",
        LimbusReader.LabelKind.ONE_SIDED to "onesided.png",
    )

    fun load(context: Context): Map<LimbusReader.LabelKind, GrayImage> {
        val out = mutableMapOf<LimbusReader.LabelKind, GrayImage>()
        for ((kind, name) in files) {
            val bmp = runCatching { context.assets.open("limbus/labels/$name").use { BitmapFactory.decodeStream(it) } }.getOrNull() ?: continue
            out[kind] = BitmapPixels(bmp).toArray().halfGray()
            bmp.recycle()
        }
        return out
    }
}

class BitmapPixels(private val bmp: Bitmap) : Pixels {
    override val width: Int get() = bmp.width
    override val height: Int get() = bmp.height
    override fun get(x: Int, y: Int): Int = bmp.getPixel(x, y) and 0xFFFFFF

    /** 한 번에 배열로 복사 (getPixel 반복보다 훨씬 빠름) */
    fun toArray(): ArrayPixels {
        val d = IntArray(bmp.width * bmp.height)
        bmp.getPixels(d, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return ArrayPixels(bmp.width, bmp.height, d)
    }
}

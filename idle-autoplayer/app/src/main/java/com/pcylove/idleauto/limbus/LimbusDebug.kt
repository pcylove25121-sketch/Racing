package com.pcylove.idleauto.limbus

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** 인식 결과를 그림 위에 표시한 디버그용 비트맵. 사용자가 보내 주면 어디가 틀렸는지 바로 알 수 있어요. */
object LimbusDebug {
    fun annotate(src: Bitmap, obs: LimbusReader.Observation): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.GREEN }
        val text = Paint().apply { color = Color.WHITE; textSize = 28f; isFakeBoldText = true; setShadowLayer(4f, 0f, 0f, Color.BLACK) }
        for (col in obs.columns) {
            c.drawRect((col.centerX - 85).toFloat(), 720f, (col.centerX + 85).toFloat(), 895f, box)
            c.drawText("${col.index + 1}:${col.label?.label ?: "-"} ${"%.2f".format(col.score)}", (col.centerX - 80).toFloat(), 712f, text)
            col.labelRect?.let { r ->
                box.color = Color.YELLOW
                c.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), box)
                box.color = Color.GREEN
            }
        }
        box.color = Color.CYAN
        for (s in obs.enemySlots) {
            c.drawRect(s.rect.left.toFloat(), s.rect.top.toFloat(), s.rect.right.toFloat(), s.rect.bottom.toFloat(), box)
        }
        return out
    }
}

package com.pcylove.idleauto.limbus

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 접근성 서비스의 스크린샷 기능(안드로이드 11+)으로 화면을 비트맵으로 가져와요.
 * MediaProjection 과 달리 권한 창이 뜨지 않고, 갤럭시 S25(안드로이드 15)에서 바로 돼요.
 * 시스템이 연속 호출을 제한하니(약 0.3초 간격) 너무 자주 부르면 실패 콜백이 와요.
 */
object ScreenCapture {

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun capture(service: AccessibilityService, onResult: (Bitmap?, String?) -> Unit) {
        if (!isSupported()) {
            onResult(null, "안드로이드 11 이상에서만 화면을 읽을 수 있어요")
            return
        }
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hb = result.hardwareBuffer
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            hb.close()
                        }
                        if (bmp == null) onResult(null, "비트맵 변환 실패") else onResult(bmp, null)
                    }

                    override fun onFailure(errorCode: Int) {
                        val why = when (errorCode) {
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "너무 빨리 다시 찍었어요"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "접근성 권한이 없어요"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "화면을 찾을 수 없어요"
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "시스템 오류"
                            else -> "오류 $errorCode"
                        }
                        onResult(null, why)
                    }
                }
            )
        } catch (e: Exception) {
            onResult(null, e.message ?: "캡처 실패")
        }
    }

    /** 디버그용으로 앱 전용 폴더(Android/data/com.pcylove.idleauto/files/Pictures)에 PNG 로 저장. 경로를 돌려줘요. */
    fun saveDebug(service: AccessibilityService, bmp: Bitmap, tag: String = "limbus"): File? {
        return try {
            val dir = File(service.getExternalFilesDir("Pictures"), "")
            dir.mkdirs()
            val name = "${tag}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
            val f = File(dir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            f
        } catch (e: Exception) {
            null
        }
    }
}

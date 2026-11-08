package com.pcylove.idleauto.limbus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/** 갤럭시 S25 실제 스크린샷으로 인식기를 검증해요. (지정 전 / 승률 버튼 누른 뒤) */
class LimbusReaderTest {

    /**
     * 안드로이드 단위 테스트는 android.jar 기준으로 컴파일돼서 javax.imageio 를 직접 참조하면 컴파일이 안 돼요.
     * 실행은 일반 JVM 이라 리플렉션으로 ImageIO 를 불러 씁니다.
     */
    private fun load(path: String): ArrayPixels {
        val stream: InputStream = javaClass.getResourceAsStream(path) ?: error("리소스 없음: $path")
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val img = imageIo.getMethod("read", InputStream::class.java).invoke(null, stream)!!
        val cls = img.javaClass
        val w = cls.getMethod("getWidth").invoke(img) as Int
        val h = cls.getMethod("getHeight").invoke(img) as Int
        val d = IntArray(w * h)
        cls.getMethod("getRGB", Int::class.java, Int::class.java, Int::class.java, Int::class.java, IntArray::class.java, Int::class.java, Int::class.java)
            .invoke(img, 0, 0, w, h, d, 0, w)
        return ArrayPixels(w, h, d)
    }

    private fun templates(): Map<LimbusReader.LabelKind, GrayImage> = mapOf(
        LimbusReader.LabelKind.DOMINATING to "dominating.png",
        LimbusReader.LabelKind.FAVORED to "favored.png",
        LimbusReader.LabelKind.ONE_SIDED to "onesided.png",
    ).mapValues { (_, f) -> load("/limbus/labels/$f").halfGray() }

    private val before by lazy { load("/limbus/s25_before.jpg") }
    private val winrate by lazy { load("/limbus/s25_winrate.jpg") }

    @Test
    fun `스크린샷 크기는 2340x1080`() {
        assertEquals(2340, before.width); assertEquals(1080, before.height)
    }

    @Test
    fun `속도 숫자로 스킬 칼럼 7개를 찾는다`() {
        val reader = LimbusReader(templates())
        val expected = listOf(708, 856, 1004, 1152, 1300, 1449, 1597)
        for (img in listOf(before, winrate)) {
            val cols = reader.findColumns(img)
            assertEquals(7, cols.size)
            cols.zip(expected).forEach { (c, e) -> assertTrue("칼럼 $c ≈ $e", Math.abs(c - e) <= 6) }
        }
    }

    @Test
    fun `승률 지정 후 라벨을 읽는다 - 4번 매우 우세, 5번 일방공격, 6번 우세`() {
        val obs = LimbusReader(templates()).read(winrate)
        val labels = obs.columns.map { it.label }
        assertEquals(LimbusReader.LabelKind.DOMINATING, labels[3])
        assertEquals(LimbusReader.LabelKind.ONE_SIDED, labels[4])
        assertEquals(LimbusReader.LabelKind.FAVORED, labels[5])
        // 매우 우세 라벨 위치는 1150~1215, 770~816 근처
        val r = obs.columns[3].labelRect!!
        assertTrue("라벨 위치 $r", Math.abs(r.left - 1150) <= 8 && Math.abs(r.top - 770) <= 8)
    }

    @Test
    fun `지정 전 화면에는 예측 라벨이 없다`() {
        val obs = LimbusReader(templates()).read(before)
        obs.columns.forEach { assertNull("칼럼 ${it.index + 1} 점수 ${it.score}", it.label) }
    }

    @Test
    fun `적 스킬 슬롯을 찾는다 (빨간 육각형 + 위쪽 속도 숫자)`() {
        val reader = LimbusReader(templates())
        val s1 = reader.findEnemySlots(before)
        val s2 = reader.findEnemySlots(winrate)
        assertTrue("지정 전 ${s1.size}", s1.size >= 3)
        assertTrue("지정 후 ${s2.size}", s2.size >= 3)
        // 지정 전 화면: (1415, 226) 근처에 슬롯 하나
        assertTrue(s1.any { Math.abs(it.rect.cx - 1415) <= 10 && Math.abs(it.rect.cy - 226) <= 10 })
        // 보스 몸통(오렌지색)이나 UI 는 잡히지 않아야 함: 모든 슬롯은 y < 500
        (s1 + s2).forEach { assertTrue("슬롯 ${it.rect}", it.rect.cy < 500) }
    }

    @Test
    fun `요약 문자열`() {
        val obs = LimbusReader(templates()).read(winrate)
        assertTrue(obs.summary().startsWith("칼럼 7개"))
    }
}

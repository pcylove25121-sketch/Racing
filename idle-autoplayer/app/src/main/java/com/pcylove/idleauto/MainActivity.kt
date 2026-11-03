package com.pcylove.idleauto

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var store: ScriptStore
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "방치형 오토"
        store = ScriptStore(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        renderServiceCard()
        renderScripts()
        renderHelp()
    }

    // ───────────── 1. 접근성 서비스 ─────────────

    private fun isServiceEnabled(): Boolean {
        if (AutoService.isRunning) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, AutoService::class.java).flattenToString()
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(me, ignoreCase = true) || it.equals(ComponentName(this, AutoService::class.java).flattenToShortString(), ignoreCase = true) }
    }

    private fun renderServiceCard() {
        val on = isServiceEnabled()
        val card = card()
        card.addView(text("1단계 · 접근성 서비스", 17f, bold = true))
        card.space(6)
        card.addView(
            text(
                if (on) "켜져 있어요 ✓  게임 화면에 터치를 넣고 패널을 띄울 수 있어요."
                else "꺼져 있어요.  설정 → 접근성 → 설치된 앱(또는 다운로드한 앱) → 「방치형 오토」를 켜 주세요.",
                14f, color = if (on) 0xFF1B7F4D.toInt() else 0xFFB3261E.toInt()
            )
        )
        if (!on && Build.VERSION.SDK_INT >= 33) {
            card.space(6)
            card.addView(
                text(
                    "안드로이드 13 이상에서 스위치가 회색이면: 설정 → 애플리케이션 → 방치형 오토 → 오른쪽 위 ⋮ → 「제한된 설정 허용」을 먼저 눌러 주세요.",
                    12f, color = 0xFF6F6A8A.toInt()
                )
            )
        }
        card.space(8)
        card.addView(button(if (on) "접근성 설정 보기" else "접근성 설정 열기") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        content.addView(card)
    }

    // ───────────── 2. 매크로 목록 ─────────────

    private fun renderScripts() {
        val header = card()
        header.addView(text("2단계 · 매크로", 17f, bold = true))
        header.space(6)
        header.addView(text("게임마다 매크로를 하나씩 만들고, 「패널 열기」를 누른 뒤 게임을 실행하면 화면 위에 조작 패널이 떠요.", 14f))
        header.space(8)
        header.addView(button("＋ 새 매크로 만들기") { promptNewScript() })
        content.addView(header)

        val scripts = store.loadAll()
        if (scripts.isEmpty()) {
            val empty = card()
            empty.addView(text("아직 매크로가 없어요. 위의 「＋ 새 매크로 만들기」로 시작해요.", 14f, color = 0xFF6F6A8A.toInt()))
            content.addView(empty)
        }
        scripts.forEach { s ->
            val card = card()
            card.addView(text(s.name, 16f, bold = true))
            card.addView(text(s.summary(), 13f, color = 0xFF6F6A8A.toInt()))
            card.space(6)
            card.addView(
                row(
                    button("▶ 패널 열기") { openPanel(s) },
                    button("편집") {
                        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, s.id))
                    },
                    button("삭제") { confirmDelete(s) },
                )
            )
            content.addView(card)
        }
    }

    private fun promptNewScript() {
        val input = EditText(this).apply {
            hint = "예: 던전 자동 반복"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("새 매크로 이름")
            .setView(input)
            .setPositiveButton("만들기") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "새 매크로" }
                val s = Script(name = name)
                store.upsert(s)
                startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, s.id))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDelete(s: Script) {
        AlertDialog.Builder(this)
            .setTitle("「${s.name}」 삭제")
            .setMessage("동작 ${s.steps.size}개가 함께 지워져요. 되돌릴 수 없어요.")
            .setPositiveButton("삭제") { _, _ ->
                AutoService.instance?.overlay?.let { if (it.openScriptId == s.id) it.close() }
                store.delete(s.id)
                render()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openPanel(s: Script) {
        val service = AutoService.instance
        if (service == null) {
            Toast.makeText(this, "먼저 접근성 서비스를 켜 주세요.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        service.overlay.open(s)
        Toast.makeText(this, "패널이 떠 있어요. 이제 게임을 실행하세요.\n＋ 타겟 추가 · ● 녹화 · ▶ 시작 · 볼륨↓ 비상 정지", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    // ───────────── 3. 도움말 ─────────────

    private fun renderHelp() {
        val card = card()
        card.addView(text("사용법", 17f, bold = true))
        card.space(6)
        card.addView(
            text(
                """
                • ＋ : 화면 가운데에 번호 마커가 생겨요. 끌어서 게임의 버튼 위에 놓으면 그 자리를 순서대로 눌러요.
                • ● : 녹화 모드. 게임을 평소처럼 조작하면 탭·길게 누르기·스와이프와 사이 간격이 그대로 기록돼요. ■ 로 종료.
                • ▶ : 저장된 동작을 순서대로 반복해요. ■ 또는 볼륨 아래 버튼으로 멈춰요.
                • － : 마지막 동작을 지워요. 세부 간격·반복 횟수·자동 정지 시간은 「편집」에서 바꿔요.
                • 마커 번호를 짧게 누르면 그 동작의 정보가 패널 아래에 표시돼요.
                • 재생 중에는 화면이 꺼지지 않아요. 패널 바로 아래 자리는 눌리지 않으니 패널은 빈 곳으로 옮겨 두세요.
                • 화면 회전이나 해상도가 바뀌면 좌표가 어긋나요. 게임은 항상 같은 방향으로 켜 주세요.
                """.trimIndent(),
                14f
            )
        )
        card.space(10)
        card.addView(
            text(
                "⚠ 게임에 따라 매크로 사용을 약관으로 금지하고 제재하는 경우가 있어요. 각 게임의 운영 정책을 확인한 뒤 본인 책임으로 사용해 주세요. 이 앱은 화면 내용을 읽거나 전송하지 않고, 터치만 대신 눌러 줘요.",
                12f, color = 0xFF6F6A8A.toInt()
            )
        )
        content.addView(card)
    }
}

package com.pcylove.idleauto.limbus

/**
 * 합에 쓰지 않고 남은 스킬을 어느 적에게 일방 공격으로 쓸지 정해요.
 *
 * 조사한 피해 규칙:
 *  - 흐트러진 적은 내성이 통째로 바뀌어 피해를 2배(흐트러짐+ 2.5배, ++ 3배) 받는다. 약한 스킬도 충분히 들어간다.
 *  - 흐트러짐 선(게이지)을 넘기면 그 적은 이번 턴과 다음 턴에 행동하지 못하고 준비한 스킬이 전부 취소된다.
 *    → 강한 스킬의 가장 좋은 쓸 곳은 "멀쩡한 적을 선 아래로 밀어 넣는 것".
 *  - 물리 내성: 내성 0.5배, 견딤 0.75배, 보통 1배, 취약 2배.
 *  - 죽이면 그 적의 공격이 사라진다. 가장 가치가 크다. 단, 필요한 만큼만 쓰고 넘치는 위력은 낭비.
 *
 * 배분 순서:
 *  1. 처치 가능한 적: 가장 약한 스킬(조합)로 처치. 흐트러진 적은 2배라 더 싸게 끝난다.
 *  2. 흐트러뜨릴 수 있는 적: 다음 흐트러짐 선까지 필요한 피해를 가장 싼 조합으로 채운다. 넘기면 그 뒤 스킬은 2배로 들어간다.
 *  3. 나머지: 강한 스킬은 멀쩡한 적(체력 낮은 순)에게, 약한 스킬은 흐트러진 적에게. 체력 숫자를 몰라도 이 규칙은 동작한다.
 */
object AttackAllocator {

    /** 배분용 적 상태 사본 */
    class State(val enemy: Enemy) {
        var hp: Int = enemy.hp
        var staggered: Boolean = enemy.staggered
        var staggerLevel: Int = if (enemy.staggered) enemy.staggerLevel.coerceAtLeast(1) else 0
        var alive: Boolean = true
        val hpKnown: Boolean get() = enemy.hp > 0 && enemy.maxHp > 0
        val ratio: Float get() = if (hpKnown) hp.toFloat() / enemy.maxHp else enemy.hpRatio

        /** 현재 체력보다 낮은 가장 가까운 흐트러짐 선 (없으면 null) */
        fun nextThreshold(): Int? = enemy.staggerThresholds.filter { it < hp }.maxOrNull()

        fun multiplier(skill: SinnerSkill): Float =
            if (staggered) 1f + 1f + 0.5f * (staggerLevel - 1).coerceAtLeast(0)   // 2.0 / 2.5 / 3.0
            else skill.attackType?.let { enemy.resists[it]?.multiplier } ?: 1f
    }

    data class Result(val targetEnemyOf: Map<Int, Int>, val notes: List<String>)

    /**
     * @param skillIdx 남은 스킬 인덱스들
     * @param hitQuality 이 스킬이 이 적을 일방 공격할 자리의 질. 0 = 자리 없음, 1 = 합 담당이 있는 슬롯에 덧붙이기만 가능, 2 = 빈 슬롯 있음
     */
    fun allocate(
        board: Board,
        skillIdx: List<Int>,
        hitQuality: (skill: Int, enemyId: Int) -> Int,
        options: PlanOptions,
    ): Result {
        val canHit = { s: Int, id: Int -> hitQuality(s, id) > 0 }
        val notes = mutableListOf<String>()
        val out = mutableMapOf<Int, Int>()
        val states = board.enemies.map { State(it) }
        val remaining = skillIdx.sortedBy { board.skills[it].strength }.toMutableList() // 약한 것부터

        fun dmg(s: Int, st: State): Int = (board.skills[s].strength * st.multiplier(board.skills[s])).toInt()

        fun apply(s: Int, st: State, why: String) {
            out[s] = st.enemy.id
            remaining.remove(s)
            if (st.hpKnown) {
                val before = st.hp
                st.hp -= dmg(s, st)
                if (st.hp <= 0) { st.alive = false; st.hp = 0 }
                else {
                    // 선을 넘겼으면 바로 흐트러짐 (같은 턴의 뒤 공격은 2배)
                    val crossed = st.enemy.staggerThresholds.count { it < before && it >= st.hp }
                    if (crossed > 0) {
                        if (!st.staggered) { st.staggered = true; st.staggerLevel = crossed } else st.staggerLevel = (st.staggerLevel + crossed).coerceAtMost(3)
                    }
                }
            }
            notes += why
        }

        /** need 이상 피해를 내는 가장 싼 스킬 조합 (1~3개). 위력 합이 가장 작은 것. */
        fun cheapestCombo(st: State, need: Int): List<Int>? {
            val cands = remaining.filter { canHit(it, st.enemy.id) }
            if (cands.isEmpty() || need <= 0) return null
            cands.firstOrNull { dmg(it, st) >= need }?.let { return listOf(it) } // 오름차순이라 첫 번째가 가장 약함
            var best: List<Int>? = null
            var bestCost = Int.MAX_VALUE
            for (i in cands.indices) for (j in i + 1 until cands.size) {
                val cost = board.skills[cands[i]].strength + board.skills[cands[j]].strength
                if (cost < bestCost && dmg(cands[i], st) + dmg(cands[j], st) >= need) { best = listOf(cands[i], cands[j]); bestCost = cost }
            }
            if (best != null) return best
            for (i in cands.indices) for (j in i + 1 until cands.size) for (k in j + 1 until cands.size) {
                val cost = board.skills[cands[i]].strength + board.skills[cands[j]].strength + board.skills[cands[k]].strength
                if (cost < bestCost && dmg(cands[i], st) + dmg(cands[j], st) + dmg(cands[k], st) >= need) { best = listOf(cands[i], cands[j], cands[k]); bestCost = cost }
            }
            return best
        }

        // ── 1·2단계: 처치 → 흐트러뜨리기 (체력 숫자를 아는 적만) ──
        while (remaining.isNotEmpty()) {
            data class Goal(val st: State, val need: Int, val combo: List<Int>, val kill: Boolean, val value: Int)
            val goals = mutableListOf<Goal>()
            for (st in states) {
                if (!st.alive || !st.hpKnown) continue
                cheapestCombo(st, st.hp)?.let { goals += Goal(st, st.hp, it, true, 3) }
                if (!st.staggered) st.nextThreshold()?.let { th ->
                    cheapestCombo(st, st.hp - th)?.let { goals += Goal(st, st.hp - th, it, false, 2) }
                }
            }
            // 가치 큰 것 먼저(처치 > 흐트러뜨리기), 같으면 더 싼 조합
            val g = goals.maxWithOrNull(compareBy<Goal> { it.value }.thenBy { -it.combo.sumOf { s -> board.skills[s].strength } }) ?: break
            val names = g.combo.joinToString("+") { "수감자${board.skills[it].sinner} 스킬${board.skills[it].slot}" }
            val why = if (g.kill) "$names → 적${g.st.enemy.id} 처치 (필요 ${g.need})" else "$names → 적${g.st.enemy.id} 흐트러뜨리기 (선까지 ${g.need})"
            g.combo.forEach { apply(it, g.st, why) }
        }

        // ── 3단계: 나머지. 강한 스킬은 멀쩡한 적, 약한 스킬은 흐트러진 적 ──
        // "약한 스킬" 기준: 전체 스킬 위력의 아래쪽 중앙값 이하 (두 개면 약한 쪽만 약함)
        val all = board.skills.map { it.strength }.sorted()
        val median = if (all.isEmpty()) 0 else all[(all.size - 1) / 2]
        val alive = { states.filter { it.alive } }
        for (s in remaining.sortedByDescending { board.skills[it].strength }) {
            val hittable = alive().filter { canHit(s, it.enemy.id) }
            if (hittable.isEmpty()) continue
            val weak = board.skills[s].strength <= median
            val healthy = hittable.filter { !it.staggered }
            val staggered = hittable.filter { it.staggered }
            // 체력 낮은 적 우선(5% 단위), 비슷하면 빈 슬롯이 있는 적(지정 순서가 꼬일 여지가 없음)
            val order = compareBy<State> { if (options.focusLowHp) (it.ratio * 20).toInt() else 0 }
                .thenByDescending { hitQuality(s, it.enemy.id) }
            val pick = when {
                weak && options.preferStaggered && staggered.isNotEmpty() -> staggered.minWithOrNull(order)!!   // 약한 스킬: 2배로 들어가는 곳
                !weak && healthy.isNotEmpty() -> healthy.minWithOrNull(order)!!                               // 강한 스킬: 선에 가까운 멀쩡한 적
                staggered.isNotEmpty() && options.preferStaggered -> staggered.minWithOrNull(order)!!
                else -> hittable.minWithOrNull(order)!!
            }
            out[s] = pick.enemy.id
            if (pick.hpKnown) pick.hp = (pick.hp - dmg(s, pick)).coerceAtLeast(0)
        }
        return Result(out, notes)
    }
}

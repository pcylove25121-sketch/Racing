package com.pcylove.idleauto.limbus

/**
 * 림버스 컴퍼니 "향상된 자동전투"의 두뇌.
 * 화면 인식과 분리된 순수 로직이라, 어떤 판을 넣으면 어떤 배치가 나오는지 그대로 테스트할 수 있어요.
 *
 * 반영한 게임 규칙 (공략/위키 조사 기준, 패치로 달라질 수 있음):
 *  - 합 예측 5단계: 매우 우세(90~100%) / 우세(60~90%) / 보통(40~60%) / 열세(10~40%) / 매우 열세(0~10%).
 *  - 나를 노리는 적 스킬과는 속도와 상관없이 합을 할 수 있다.
 *  - 다른 수감자를 노리는 적 스킬은 내 속도가 그 적 스킬보다 "빠를 때만" 합을 뺏어올 수 있다.
 *    느리거나 같으면 합이 아니라 일방 공격 판정이 되고, 그 적 공격은 원래 대상이 그대로 맞는다.
 *  - 적 스킬 하나에 우리 스킬이 여럿 붙으면 "마지막에 지정한" 하나만 합을 하고 나머지는 일방 공격이 된다.
 *  - 흐트러진 적은 행동하지 못하고 받는 피해가 2~3배가 된다. 때리면 전부 일방 공격이다.
 *  - 아무도 막지 않은 적 공격은 그 대상이 일방적으로 맞는다.
 *  - 열세·매우 열세인 합은 공격 대신 방어 스킬(회피/가드)로 받는 편이 낫다.
 *  - 기본 '승률' 오토의 약점: 속도 빠른 수감자가 합 승률이 낮아도 그 수감자로 합을 걸고, 한두 적에게 공격이 몰린다.
 *
 * 사용자 지정 규칙:
 *  1. 매우 우세 → 우세 순으로 합을 잡는다. 열세·매우 열세 합은 하지 않는다. (보통은 옵션)
 *  2. 적 스킬 하나에는 우리 합 하나만 붙인다.
 *  3. 남는 스킬은 흐트러진 적 → 체력 낮은 적 순으로 일방 공격한다.
 */

/** 합 예측. 게임 한국어 표기 그대로 (열세는 '불리'로도 표기되는 자료가 있어 둘 다 받아요). */
enum class Clash(val label: String, val score: Int, val winRate: IntRange) {
    DOMINATING("매우 우세", 100, 90..100),
    FAVORED("우세", 60, 60..89),
    NEUTRAL("보통", 10, 40..59),
    STRUGGLING("열세", -60, 10..39),
    HOPELESS("매우 열세", -100, 0..9),
    UNKNOWN("?", -1000, 0..100);

    val isBad: Boolean get() = this == STRUGGLING || this == HOPELESS || this == UNKNOWN

    companion object {
        fun fromLabel(s: String): Clash = when (s.trim().replace(" ", "")) {
            "매우우세" -> DOMINATING
            "우세" -> FAVORED
            "보통", "균형" -> NEUTRAL
            "열세", "불리" -> STRUGGLING
            "매우열세", "매우불리" -> HOPELESS
            else -> UNKNOWN
        }
    }
}

/** 우리 쪽 스킬 하나. speed 는 그 슬롯의 속도 주사위 값, power 는 대미지 추정치(없으면 0). */
data class SinnerSkill(val sinner: Int, val slot: Int, val speed: Int = 0, val power: Int = 0)

/** 적 스킬 슬롯. targetSinner 는 이 적 스킬이 노리는 우리 수감자(없으면 null), speed 는 적 슬롯 속도. */
data class EnemySlot(val enemy: Int, val slot: Int, val targetSinner: Int?, val speed: Int = 0)

/** 적 하나의 상태. hpRatio 0.0~1.0. */
data class Enemy(val id: Int, val staggered: Boolean = false, val hpRatio: Float = 1f)

/** 한 턴의 판 전체. predictions 의 키는 (스킬 인덱스, 적 슬롯 인덱스). 합이 가능한 쌍에만 값이 있어요. */
data class Board(
    val skills: List<SinnerSkill>,
    val enemySlots: List<EnemySlot>,
    val enemies: List<Enemy>,
    val predictions: Map<Pair<Int, Int>, Clash>,
) {
    fun enemy(id: Int): Enemy? = enemies.firstOrNull { it.id == id }

    /** 이 적 스킬이 나를 노리는가 */
    fun targetsMe(skillIdx: Int, slotIdx: Int): Boolean =
        enemySlots[slotIdx].targetSinner == skills[skillIdx].sinner

    /** 이 쌍에 스킬을 놓으면 합이 되는가 (나를 노리거나, 내가 더 빨라 뺏어올 수 있거나) */
    fun canClash(skillIdx: Int, slotIdx: Int, equalSpeedSteals: Boolean = false): Boolean {
        val es = enemySlots[slotIdx]
        if (enemy(es.enemy)?.staggered == true) return false
        if (targetsMe(skillIdx, slotIdx)) return true
        val mySpeed = skills[skillIdx].speed
        return if (equalSpeedSteals) mySpeed >= es.speed else mySpeed > es.speed
    }

    fun prediction(skillIdx: Int, slotIdx: Int): Clash = predictions[skillIdx to slotIdx] ?: Clash.UNKNOWN
}

sealed class Target {
    /** 적 슬롯과 합 */
    data class ClashWith(val slotIndex: Int, val predicted: Clash) : Target()
    /** 적 슬롯을 겨눠 일방 공격 (합이 성립하지 않는 자리) */
    data class Attack(val slotIndex: Int) : Target()
    /** 나를 노리는 적 슬롯을 방어 스킬(회피/가드)로 받기 */
    data class Defend(val slotIndex: Int) : Target()
    /** 놓을 곳이 없음 */
    data object Skip : Target()
}

data class Assignment(val skillIndex: Int, val target: Target)

data class Plan(
    val assignments: List<Assignment>,
    /**
     * 화면에서 실제로 지정해야 하는 순서(스킬 인덱스).
     * 같은 적 슬롯에 일방 공격과 합이 함께 붙으면 "마지막에 지정한 것이 합"이 되므로 합은 반드시 뒤에 지정해요.
     */
    val applyOrder: List<Int>,
    val notes: List<String>,
) {
    fun target(skillIdx: Int): Target = assignments.first { it.skillIndex == skillIdx }.target
}

data class PlanOptions(
    /** 좋은 합을 잡을 때 보통 합도 허용할지. 기본은 매우 우세·우세만. */
    val allowNeutral: Boolean = false,
    /** 아무도 막지 못한 "나를 노리는" 적 공격을 보통 합으로라도 막을지. 그냥 두면 일방적으로 맞아요. */
    val coverWithNeutral: Boolean = true,
    /** 막을 방법이 열세·매우 열세밖에 없으면 그 수감자는 방어 스킬(회피/가드)로 받을지 */
    val defendOnBad: Boolean = true,
    /** 남는 스킬을 흐트러진 적에게 먼저 쓸지 */
    val preferStaggered: Boolean = true,
    /** 체력 낮은 적부터 집중할지 */
    val focusLowHp: Boolean = true,
    /** 속도가 같을 때도 합을 뺏어올 수 있다고 볼지 (자료마다 달라 기본은 '못 뺏는다') */
    val equalSpeedSteals: Boolean = false,
)

object LimbusPlanner {

    fun plan(board: Board, options: PlanOptions = PlanOptions()): Plan {
        val n = board.skills.size
        val m = board.enemySlots.size
        val notes = mutableListOf<String>()
        if (n == 0) return Plan(emptyList(), emptyList(), notes)

        val result = arrayOfNulls<Assignment>(n)
        val clasherOfSlot = IntArray(m) { -1 }   // 적 슬롯마다 합을 맡은 우리 스킬 (하나만)
        val attackersOnSlot = IntArray(m)         // 일방 공격으로 붙은 수

        // ── 1단계: 좋은 합 최대 매칭 (적 슬롯 하나에 우리 합 하나) ──
        val minScore = (if (options.allowNeutral) Clash.NEUTRAL else Clash.FAVORED).score
        val weight = Array(n) { s ->
            IntArray(m) { e ->
                val pred = board.prediction(s, e)
                if (!board.canClash(s, e, options.equalSpeedSteals) || pred.score < minScore) NONE
                else pred.score * 1000 +
                    (if (board.enemySlots[e].targetSinner != null) 300 else 0) + // 나(또는 동료)를 노리는 공격을 막는 가치
                    damageBonus(board, e, options) +
                    board.skills[s].power.coerceIn(0, 299)
            }
        }
        for ((s, e) in maxWeightMatching(weight).withIndex()) {
            if (e >= 0) {
                clasherOfSlot[e] = s
                result[s] = Assignment(s, Target.ClashWith(e, board.prediction(s, e)))
                if (!board.targetsMe(s, e)) notes += "${name(board, s)}이(가) 더 빨라서 적${board.enemySlots[e].enemy} 슬롯${board.enemySlots[e].slot} 합을 뺏어옴"
            }
        }

        // ── 2단계: 아직 아무도 막지 않은 "우리를 노리는" 적 공격 처리 ──
        // 2a. 보통 합으로라도 막기 (옵션)
        if (options.coverWithNeutral) {
            val uncovered = (0 until m).filter { e -> clasherOfSlot[e] < 0 && board.enemySlots[e].targetSinner != null }
            val free = (0 until n).filter { result[it] == null }
            if (uncovered.isNotEmpty() && free.isNotEmpty()) {
                val w2 = Array(free.size) { i ->
                    IntArray(uncovered.size) { j ->
                        val s = free[i]; val e = uncovered[j]
                        val pred = board.prediction(s, e)
                        if (board.canClash(s, e, options.equalSpeedSteals) && pred == Clash.NEUTRAL)
                            1000 + damageBonus(board, e, options) else NONE
                    }
                }
                for ((i, j) in maxWeightMatching(w2).withIndex()) if (j >= 0) {
                    val s = free[i]; val e = uncovered[j]
                    clasherOfSlot[e] = s
                    result[s] = Assignment(s, Target.ClashWith(e, Clash.NEUTRAL))
                    notes += "${name(board, s)}: 막을 사람이 없어 보통 합으로 받음"
                }
            }
        }
        // 2b. 그래도 안 막힌 공격은, 노려진 수감자가 방어 스킬로 받기 (옵션)
        if (options.defendOnBad) {
            for (e in 0 until m) {
                if (clasherOfSlot[e] >= 0) continue
                val victim = board.enemySlots[e].targetSinner ?: continue
                val mine = (0 until n).filter { result[it] == null && board.skills[it].sinner == victim }
                if (mine.isEmpty()) continue
                // 내 스킬 중 이 공격과의 예측이 전부 나쁠 때만 (보통 이상이면 1단계/2a 에서 이미 잡혔거나 옵션으로 꺼진 것)
                val allBad = mine.all { board.prediction(it, e).isBad }
                if (!allBad) continue
                val s = mine.minByOrNull { board.skills[it].power } ?: continue // 약한 스킬을 방어로 돌림
                clasherOfSlot[e] = s
                result[s] = Assignment(s, Target.Defend(e))
                notes += "${name(board, s)}: 적${board.enemySlots[e].enemy} 공격이 열세라 방어 스킬로 받음"
            }
        }

        // ── 3단계: 남는 스킬은 일방 공격 ──
        val leftovers = (0 until n).filter { result[it] == null }
            .sortedByDescending { board.skills[it].power } // 센 스킬부터 좋은 자리에
        for (s in leftovers) {
            // 합이 성립하지 않는 자리만 고른다 (성립하면 열세 합이 될 수 있음)
            val best = (0 until m)
                .filter { e -> !board.canClash(s, e, options.equalSpeedSteals) }
                .maxByOrNull { e -> attackScore(board, e, clasherOfSlot[e] >= 0, attackersOnSlot[e], options) }
            if (best != null) {
                attackersOnSlot[best]++
                result[s] = Assignment(s, Target.Attack(best))
                continue
            }
            // 모든 적 슬롯과 합이 성립하는 경우:
            // (1) 이미 합 담당이 있는 슬롯에 추가로 붙여 일방 공격 (합 담당을 뒤에 지정하면 내 스킬은 일방 공격이 됨)
            val pile = (0 until m).filter { clasherOfSlot[it] >= 0 }
                .maxByOrNull { e -> attackScore(board, e, true, attackersOnSlot[e], options) }
            if (pile != null) {
                attackersOnSlot[pile]++
                result[s] = Assignment(s, Target.Attack(pile))
                continue
            }
            // (2) 그것도 없으면 아직 아무도 안 붙은 슬롯 중 가장 덜 나쁜 합 (예측 모름은 맨 뒤)
            val fallback = (0 until m).filter { clasherOfSlot[it] < 0 }
                .maxByOrNull { e -> board.prediction(s, e).score }
            if (fallback != null) {
                clasherOfSlot[fallback] = s
                result[s] = Assignment(s, Target.ClashWith(fallback, board.prediction(s, fallback)))
                notes += "${name(board, s)}: 피할 자리가 없어 ${board.prediction(s, fallback).label} 합이라도 잡음"
            } else {
                result[s] = Assignment(s, Target.Skip)
            }
        }

        val assignments = result.map { it!! }
        // 지정 순서: 일방 공격 → 방어 → 합. (같은 슬롯에서는 마지막 지정이 합을 하므로 합을 맨 뒤에)
        val order = assignments.sortedBy {
            when (it.target) {
                is Target.Attack -> 0
                is Target.Defend -> 1
                is Target.ClashWith -> 2
                Target.Skip -> 3
            }
        }.filter { it.target != Target.Skip }.map { it.skillIndex }

        return Plan(assignments, order, notes)
    }

    private fun name(board: Board, s: Int) = "수감자${board.skills[s].sinner} 스킬${board.skills[s].slot}"

    private fun damageBonus(board: Board, slotIdx: Int, o: PlanOptions): Int {
        val enemy = board.enemy(board.enemySlots[slotIdx].enemy) ?: return 0
        return if (o.focusLowHp) ((1f - enemy.hpRatio) * 200).toInt() else 0
    }

    private fun attackScore(board: Board, slotIdx: Int, hasClasher: Boolean, attackers: Int, o: PlanOptions): Int {
        val enemy = board.enemy(board.enemySlots[slotIdx].enemy) ?: return Int.MIN_VALUE / 2
        var score = 0
        if (o.preferStaggered && enemy.staggered) score += 10_000   // 흐트러진 적: 피해 2~3배, 반격 없음
        if (o.focusLowHp) score += ((1f - enemy.hpRatio) * 1000).toInt()
        if (!hasClasher) score += 200     // 합 담당이 있는 슬롯은 순서가 꼬일 여지가 있어 살짝 피함
        score -= attackers * 50           // 같은 슬롯에 너무 몰리지 않게 (같은 적의 다른 슬롯으로 분산)
        return score
    }

    private const val NONE = Int.MIN_VALUE / 4

    /**
     * 최대 가중치 이분 매칭 (헝가리안, O(k^3)). weight[s][e] 가 NONE 이면 연결 불가.
     * 반환: 행 → 열 인덱스, 없으면 -1.
     */
    internal fun maxWeightMatching(weight: Array<IntArray>): IntArray {
        val n = weight.size
        val m = if (n == 0) 0 else weight[0].size
        val k = maxOf(n, m)
        if (k == 0) return IntArray(n) { -1 }
        val big = 1_000_000L
        val cost = Array(k) { i -> LongArray(k) { j ->
            if (i < n && j < m && weight[i][j] != NONE) big - weight[i][j] else big * 2
        } }
        val u = LongArray(k + 1); val v = LongArray(k + 1)
        val p = IntArray(k + 1); val way = IntArray(k + 1)
        for (i in 1..k) {
            p[0] = i
            var j0 = 0
            val minv = LongArray(k + 1) { Long.MAX_VALUE }
            val used = BooleanArray(k + 1)
            do {
                used[j0] = true
                val i0 = p[j0]; var delta = Long.MAX_VALUE; var j1 = 0
                for (j in 1..k) if (!used[j]) {
                    val cur = cost[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..k) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                }
                j0 = j1
            } while (p[j0] != 0)
            do { val j1 = way[j0]; p[j0] = p[j1]; j0 = j1 } while (j0 != 0)
        }
        val result = IntArray(n) { -1 }
        for (j in 1..k) {
            val i = p[j]
            if (i in 1..n && j <= m && weight[i - 1][j - 1] != NONE) result[i - 1] = j - 1
        }
        return result
    }

    /** 사람이 읽을 수 있는 설명 (패널 표시·로그용). 지정 순서대로 나열해요. */
    fun describe(board: Board, plan: Plan): String {
        val lines = plan.applyOrder.mapIndexed { i, s ->
            val head = "${i + 1}. ${name(board, s)}"
            when (val t = plan.target(s)) {
                is Target.ClashWith -> {
                    val es = board.enemySlots[t.slotIndex]
                    "$head → 적${es.enemy} 슬롯${es.slot} 합 (${t.predicted.label})"
                }
                is Target.Attack -> {
                    val es = board.enemySlots[t.slotIndex]
                    "$head → 적${es.enemy} 일방 공격" + if (board.enemy(es.enemy)?.staggered == true) " (흐트러짐)" else ""
                }
                is Target.Defend -> {
                    val es = board.enemySlots[t.slotIndex]
                    "$head → 적${es.enemy} 슬롯${es.slot} 방어 스킬"
                }
                Target.Skip -> "$head → 놓을 곳 없음"
            }
        }
        val skipped = plan.assignments.filter { it.target == Target.Skip }.map { "${name(board, it.skillIndex)} → 놓을 곳 없음" }
        return (lines + skipped + plan.notes.map { "※ $it" }).joinToString("\n")
    }
}

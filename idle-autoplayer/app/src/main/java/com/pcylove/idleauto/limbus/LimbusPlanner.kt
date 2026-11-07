package com.pcylove.idleauto.limbus

/**
 * 림버스 컴퍼니 "향상된 자동전투"의 두뇌.
 * 화면 인식과 분리된 순수 로직이라, 어떤 판을 넣으면 어떤 배치가 나오는지 그대로 테스트할 수 있어요.
 *
 * 규칙 (사용자 지정):
 *  1. 매우 우세 → 우세 순으로 합을 잡는다. 열세·매우 열세 합은 하지 않는다. (보통은 옵션)
 *  2. 적 스킬 하나에는 우리 스킬 하나만 붙인다.
 *  3. 남는 스킬은 흐트러진 적을 일방 공격한다. 체력이 낮은 적부터 집중한다.
 */

/** 합 예측. 게임 한국어 표기 그대로. */
enum class Clash(val label: String, val score: Int) {
    DOMINATING("매우 우세", 100),
    FAVORED("우세", 60),
    NEUTRAL("보통", 10),
    STRUGGLING("열세", -60),
    HOPELESS("매우 열세", -100),
    UNKNOWN("?", -1000);

    companion object {
        fun fromLabel(s: String): Clash = entries.firstOrNull { it.label == s.trim() } ?: UNKNOWN
    }
}

/** 우리 쪽 스킬 하나 (수감자 번호 + 그 수감자의 몇 번째 슬롯인지). power 는 대미지 추정치(없으면 0). */
data class SinnerSkill(val sinner: Int, val slot: Int, val power: Int = 0)

/** 적 스킬 슬롯. targetSinner 는 이 적 스킬이 노리는 우리 수감자(없으면 null). */
data class EnemySlot(val enemy: Int, val slot: Int, val targetSinner: Int?)

/** 적 하나의 상태. hpRatio 0.0~1.0. */
data class Enemy(val id: Int, val staggered: Boolean = false, val hpRatio: Float = 1f)

/** 한 턴의 판 전체. predictions 의 키는 (스킬 인덱스, 적 슬롯 인덱스). 서로 노리는 쌍에만 값이 있어요. */
data class Board(
    val skills: List<SinnerSkill>,
    val enemySlots: List<EnemySlot>,
    val enemies: List<Enemy>,
    val predictions: Map<Pair<Int, Int>, Clash>,
) {
    fun enemy(id: Int): Enemy? = enemies.firstOrNull { it.id == id }

    /** 서로 노리는 쌍인가 (이 쌍에 스킬을 놓으면 합이 됨) */
    fun isMutual(skillIdx: Int, slotIdx: Int): Boolean =
        enemySlots[slotIdx].targetSinner == skills[skillIdx].sinner
}

sealed class Target {
    /** 적 슬롯과 합 */
    data class ClashWith(val slotIndex: Int, val predicted: Clash) : Target()
    /** 적 슬롯을 겨눠 일방 공격 (그 적 슬롯은 나를 노리지 않음) */
    data class Attack(val slotIndex: Int) : Target()
    /** 둘 다 불가능 (놓을 곳이 없음) */
    data object Skip : Target()
}

data class Assignment(val skillIndex: Int, val target: Target)

data class PlanOptions(
    /** 보통 합도 허용할지. 기본은 매우 우세·우세만. */
    val allowNeutral: Boolean = false,
    /** 남는 스킬을 흐트러진 적에게 먼저 쓸지 */
    val preferStaggered: Boolean = true,
    /** 체력 낮은 적부터 집중할지 */
    val focusLowHp: Boolean = true,
)

object LimbusPlanner {

    fun plan(board: Board, options: PlanOptions = PlanOptions()): List<Assignment> {
        val n = board.skills.size
        val m = board.enemySlots.size
        if (n == 0) return emptyList()

        // ── 1단계: 좋은 합 최대 매칭 (적 슬롯 하나에 우리 스킬 하나) ──
        val minClash = if (options.allowNeutral) Clash.NEUTRAL else Clash.FAVORED
        val weight = Array(n) { s ->
            IntArray(m) { e ->
                val pred = board.predictions[s to e]
                if (pred == null || !board.isMutual(s, e) || pred.score < minClash.score) NONE
                else pred.score * 1000 + damageBonus(board, e, options) + board.skills[s].power.coerceIn(0, 999)
            }
        }
        val match = maxWeightMatching(weight) // match[s] = e or -1

        val result = arrayOfNulls<Assignment>(n)
        val usedSlots = BooleanArray(m)
        for (s in 0 until n) {
            val e = match[s]
            if (e >= 0) {
                usedSlots[e] = true
                result[s] = Assignment(s, Target.ClashWith(e, board.predictions[s to e] ?: Clash.UNKNOWN))
            }
        }

        // ── 2단계: 남는 스킬은 일방 공격 ──
        // 후보: 나를 노리지 않는 적 슬롯(=일방 공격). 흐트러진 적 > 체력 낮은 적 > 아직 아무도 안 붙은 슬롯.
        val leftovers = (0 until n).filter { result[it] == null }
            .sortedByDescending { board.skills[it].power } // 센 스킬부터 좋은 자리에
        for (s in leftovers) {
            val best = (0 until m)
                .filter { e -> !board.isMutual(s, e) } // 서로 노리면 합이 되어 버림(열세일 수 있음) → 제외
                .maxByOrNull { e -> attackScore(board, e, usedSlots[e], options) }
            if (best != null) {
                usedSlots[best] = true
                result[s] = Assignment(s, Target.Attack(best))
            } else {
                // 모든 적 슬롯이 나를 노리는 경우: 가장 덜 나쁜 합이라도 잡는다(안 하면 일방으로 맞기만 함)
                val fallback = (0 until m).filter { !usedSlots[it] }
                    .maxByOrNull { e -> (board.predictions[s to e] ?: Clash.UNKNOWN).score }
                result[s] = if (fallback != null) {
                    usedSlots[fallback] = true
                    Assignment(s, Target.ClashWith(fallback, board.predictions[s to fallback] ?: Clash.UNKNOWN))
                } else Assignment(s, Target.Skip)
            }
        }
        return result.map { it!! }
    }

    private fun damageBonus(board: Board, slotIdx: Int, o: PlanOptions): Int {
        val enemy = board.enemy(board.enemySlots[slotIdx].enemy) ?: return 0
        var bonus = 0
        if (o.focusLowHp) bonus += ((1f - enemy.hpRatio) * 400).toInt() // 체력 낮을수록 +
        return bonus
    }

    private fun attackScore(board: Board, slotIdx: Int, slotUsed: Boolean, o: PlanOptions): Int {
        val enemy = board.enemy(board.enemySlots[slotIdx].enemy) ?: return Int.MIN_VALUE / 2
        var score = 0
        if (o.preferStaggered && enemy.staggered) score += 10_000
        if (o.focusLowHp) score += ((1f - enemy.hpRatio) * 1000).toInt()
        if (!slotUsed) score += 500 // 한 슬롯에 하나만: 비어 있는 슬롯 우선
        return score
    }

    private const val NONE = Int.MIN_VALUE / 4

    /**
     * 최대 가중치 이분 매칭 (헝가리안, O(k^3)). weight[s][e] 가 NONE 이면 연결 불가.
     * 반환: skill → slot 인덱스, 없으면 -1.
     */
    internal fun maxWeightMatching(weight: Array<IntArray>): IntArray {
        val n = weight.size
        val m = if (n == 0) 0 else weight[0].size
        val k = maxOf(n, m)
        if (k == 0) return IntArray(0)
        // 최소 비용 문제로 변환: cost = BIG - weight, 불가는 BIG*2
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

    /** 사람이 읽을 수 있는 설명 (패널 표시·로그용) */
    fun describe(board: Board, plan: List<Assignment>): String = plan.joinToString("\n") { a ->
        val sk = board.skills[a.skillIndex]
        val head = "수감자${sk.sinner} 스킬${sk.slot}"
        when (val t = a.target) {
            is Target.ClashWith -> {
                val es = board.enemySlots[t.slotIndex]
                "$head → 적${es.enemy} 슬롯${es.slot} 합 (${t.predicted.label})"
            }
            is Target.Attack -> {
                val es = board.enemySlots[t.slotIndex]
                val en = board.enemy(es.enemy)
                "$head → 적${es.enemy} 일방 공격" + if (en?.staggered == true) " (흐트러짐)" else ""
            }
            Target.Skip -> "$head → 놓을 곳 없음"
        }
    }
}

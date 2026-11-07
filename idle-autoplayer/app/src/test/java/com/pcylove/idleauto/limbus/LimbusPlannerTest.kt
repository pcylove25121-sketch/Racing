package com.pcylove.idleauto.limbus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimbusPlannerTest {

    private fun clashOf(plan: List<Assignment>, s: Int) = plan.first { it.skillIndex == s }.target

    @Test
    fun `매우 우세와 우세만 합으로 잡고 열세는 피한다`() {
        val skills = listOf(SinnerSkill(1, 1), SinnerSkill(2, 1), SinnerSkill(3, 1))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(10, 2, 2), EnemySlot(11, 1, 3))
        val board = Board(
            skills, slots, listOf(Enemy(10), Enemy(11)),
            mapOf((0 to 0) to Clash.DOMINATING, (1 to 1) to Clash.FAVORED, (2 to 2) to Clash.STRUGGLING)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), clashOf(plan, 0))
        assertEquals(Target.ClashWith(1, Clash.FAVORED), clashOf(plan, 1))
        // 수감자3 은 열세라 합 대신 다른 적 슬롯을 일방 공격 (슬롯 0,1 은 수감자3을 노리지 않음)
        assertTrue(clashOf(plan, 2) is Target.Attack)
    }

    @Test
    fun `적 슬롯 하나에는 우리 스킬 하나만 붙는다`() {
        // 수감자1 의 스킬 두 개가 같은 적 슬롯에 매우 우세 → 하나만 합, 나머지는 다른 곳
        val skills = listOf(SinnerSkill(1, 1), SinnerSkill(1, 2))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(11, 1, null))
        val board = Board(
            skills, slots, listOf(Enemy(10), Enemy(11)),
            mapOf((0 to 0) to Clash.DOMINATING, (1 to 0) to Clash.DOMINATING)
        )
        val plan = LimbusPlanner.plan(board)
        val clashers = plan.filter { it.target is Target.ClashWith }
        assertEquals(1, clashers.size)
        assertEquals(Target.Attack(1), plan.first { it.target !is Target.ClashWith }.target)
    }

    @Test
    fun `남는 스킬은 흐트러진 적을 일방 공격한다`() {
        val skills = listOf(SinnerSkill(1, 1), SinnerSkill(2, 1))
        val slots = listOf(
            EnemySlot(10, 1, 1),      // 수감자1을 노림, 매우 우세
            EnemySlot(11, 1, null),   // 흐트러진 적
            EnemySlot(12, 1, null),   // 멀쩡한 적
        )
        val board = Board(
            skills, slots, listOf(Enemy(10), Enemy(11, staggered = true), Enemy(12)),
            mapOf((0 to 0) to Clash.DOMINATING)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), clashOf(plan, 0))
        assertEquals(Target.Attack(1), clashOf(plan, 1))
    }

    @Test
    fun `일방 공격은 체력 낮은 적을 먼저 친다`() {
        val skills = listOf(SinnerSkill(1, 1))
        val slots = listOf(EnemySlot(10, 1, null), EnemySlot(11, 1, null))
        val board = Board(skills, slots, listOf(Enemy(10, hpRatio = 0.9f), Enemy(11, hpRatio = 0.2f)), emptyMap())
        assertEquals(Target.Attack(1), clashOf(LimbusPlanner.plan(board), 0))
    }

    @Test
    fun `매칭은 전체 합의 질을 최대로 한다`() {
        // 수감자1: 슬롯A 매우우세, 슬롯B 우세 / 수감자2: 슬롯A 우세, 슬롯B 없음
        // 탐욕적으로 1→A 를 잡으면 2 는 합을 못 잡지만, 1→B, 2→A 로 두 개 다 잡는 게 낫다
        val skills = listOf(SinnerSkill(1, 1), SinnerSkill(2, 1))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(10, 2, 1), EnemySlot(11, 1, 2))
        // 슬롯2(인덱스 2)는 수감자2를 노림. 수감자1 입장에서 슬롯0,1 둘 다 서로 노림.
        val board = Board(
            skills, slots, listOf(Enemy(10), Enemy(11)),
            mapOf((0 to 0) to Clash.DOMINATING, (0 to 1) to Clash.FAVORED, (1 to 2) to Clash.FAVORED)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), clashOf(plan, 0))
        assertEquals(Target.ClashWith(2, Clash.FAVORED), clashOf(plan, 1))
    }

    @Test
    fun `모든 적 슬롯이 나를 노리면 가장 덜 나쁜 합이라도 잡는다`() {
        val skills = listOf(SinnerSkill(1, 1))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(10, 2, 1))
        val board = Board(
            skills, slots, listOf(Enemy(10)),
            mapOf((0 to 0) to Clash.HOPELESS, (0 to 1) to Clash.STRUGGLING)
        )
        assertEquals(Target.ClashWith(1, Clash.STRUGGLING), clashOf(LimbusPlanner.plan(board), 0))
    }

    @Test
    fun `보통 허용 옵션`() {
        val skills = listOf(SinnerSkill(1, 1))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(11, 1, null))
        val board = Board(skills, slots, listOf(Enemy(10), Enemy(11)), mapOf((0 to 0) to Clash.NEUTRAL))
        assertTrue(clashOf(LimbusPlanner.plan(board), 0) is Target.Attack)
        assertEquals(Target.ClashWith(0, Clash.NEUTRAL), clashOf(LimbusPlanner.plan(board, PlanOptions(allowNeutral = true)), 0))
    }

    @Test
    fun `설명 문자열`() {
        val skills = listOf(SinnerSkill(1, 1))
        val slots = listOf(EnemySlot(10, 1, 1))
        val board = Board(skills, slots, listOf(Enemy(10)), mapOf((0 to 0) to Clash.DOMINATING))
        assertEquals("수감자1 스킬1 → 적10 슬롯1 합 (매우 우세)", LimbusPlanner.describe(board, LimbusPlanner.plan(board)))
    }
}

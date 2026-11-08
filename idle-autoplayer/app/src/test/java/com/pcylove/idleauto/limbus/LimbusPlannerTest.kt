package com.pcylove.idleauto.limbus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimbusPlannerTest {

    private fun t(plan: Plan, s: Int) = plan.target(s)

    @Test
    fun `매우 우세와 우세만 합으로 잡고 열세는 피한다`() {
        val skills = listOf(SinnerSkill(1, 1), SinnerSkill(2, 1), SinnerSkill(3, 1))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(10, 2, 2), EnemySlot(11, 1, 3))
        val board = Board(
            skills, slots, listOf(Enemy(10), Enemy(11)),
            mapOf((0 to 0) to Clash.DOMINATING, (1 to 1) to Clash.FAVORED, (2 to 2) to Clash.STRUGGLING)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), t(plan, 0))
        assertEquals(Target.ClashWith(1, Clash.FAVORED), t(plan, 1))
        // 수감자3 은 열세 → 공격 대신 그 공격을 방어 스킬로 받는다
        assertEquals(Target.Defend(2), t(plan, 2))
    }

    @Test
    fun `열세일 때 방어 옵션을 끄면 일방 공격으로 돌린다`() {
        val skills = listOf(SinnerSkill(1, 1), SinnerSkill(3, 1))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(11, 1, 3))
        val board = Board(
            skills, slots, listOf(Enemy(10), Enemy(11)),
            mapOf((0 to 0) to Clash.DOMINATING, (1 to 1) to Clash.STRUGGLING)
        )
        val plan = LimbusPlanner.plan(board, PlanOptions(defendOnBad = false))
        // 수감자3 이 합 없이 일방 공격할 수 있는 자리는 슬롯0 (수감자1을 노리고, 속도 0 이라 못 뺏음)
        assertEquals(Target.Attack(0), t(plan, 1))
    }

    @Test
    fun `적 슬롯 하나에는 우리 합 하나만 붙는다`() {
        val skills = listOf(SinnerSkill(1, 1), SinnerSkill(1, 2))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(11, 1, null))
        val board = Board(
            skills, slots, listOf(Enemy(10), Enemy(11)),
            mapOf((0 to 0) to Clash.DOMINATING, (1 to 0) to Clash.DOMINATING)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(1, plan.assignments.count { it.target is Target.ClashWith })
        assertEquals(Target.Attack(1), plan.assignments.first { it.target !is Target.ClashWith }.target)
    }

    @Test
    fun `남는 스킬은 흐트러진 적을 일방 공격한다`() {
        val skills = listOf(SinnerSkill(1, 1), SinnerSkill(2, 1))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(11, 1, null), EnemySlot(12, 1, null))
        val board = Board(
            skills, slots, listOf(Enemy(10), Enemy(11, staggered = true), Enemy(12)),
            mapOf((0 to 0) to Clash.DOMINATING)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), t(plan, 0))
        assertEquals(Target.Attack(1), t(plan, 1))
    }

    @Test
    fun `일방 공격은 체력 낮은 적을 먼저 친다`() {
        val skills = listOf(SinnerSkill(1, 1))
        val slots = listOf(EnemySlot(10, 1, null), EnemySlot(11, 1, null))
        val board = Board(skills, slots, listOf(Enemy(10, hpRatio = 0.9f), Enemy(11, hpRatio = 0.2f)), emptyMap())
        assertEquals(Target.Attack(1), t(LimbusPlanner.plan(board), 0))
    }

    @Test
    fun `매칭은 전체 합의 질을 최대로 한다`() {
        // 수감자1: 슬롯0 매우우세, 슬롯1 우세 / 수감자2: 슬롯2 우세 만 가능 → 둘 다 합을 잡는 배치
        val skills = listOf(SinnerSkill(1, 1), SinnerSkill(2, 1))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(10, 2, 1), EnemySlot(11, 1, 2))
        val board = Board(
            skills, slots, listOf(Enemy(10), Enemy(11)),
            mapOf((0 to 0) to Clash.DOMINATING, (0 to 1) to Clash.FAVORED, (1 to 2) to Clash.FAVORED)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), t(plan, 0))
        assertEquals(Target.ClashWith(2, Clash.FAVORED), t(plan, 1))
    }

    @Test
    fun `빠른 수감자가 느린 동료를 노리는 적 공격의 합을 뺏어온다`() {
        // 적 슬롯(속도 3)이 수감자2(속도 2)를 노림. 수감자2 는 열세, 수감자1(속도 5)은 매우 우세.
        val skills = listOf(SinnerSkill(1, 1, speed = 5), SinnerSkill(2, 1, speed = 2))
        val slots = listOf(EnemySlot(10, 1, targetSinner = 2, speed = 3), EnemySlot(11, 1, null, speed = 1))
        val board = Board(
            skills, slots, listOf(Enemy(10), Enemy(11)),
            mapOf((0 to 0) to Clash.DOMINATING, (1 to 0) to Clash.STRUGGLING)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), t(plan, 0))
        // 수감자2 는 이제 보호받으니 방어 대신 남은 적을 일방 공격. 슬롯1 은 속도 1 < 2 라 합이 성립해 버리므로(예측 없음=UNKNOWN) 피하고,
        // 슬롯0 은 이미 합 담당이 있어 거기 추가로 붙어 일방 공격.
        assertTrue(t(plan, 1) is Target.Attack)
        assertTrue(plan.notes.any { it.contains("뺏어옴") })
    }

    @Test
    fun `속도가 느리거나 같으면 합을 뺏어오지 못한다`() {
        val skills = listOf(SinnerSkill(1, 1, speed = 3))
        val slots = listOf(EnemySlot(10, 1, targetSinner = 2, speed = 3))
        val board = Board(skills, slots, listOf(Enemy(10)), mapOf((0 to 0) to Clash.DOMINATING))
        // 같은 속도 → 못 뺏음 → 합이 아니라 일방 공격 판정
        assertEquals(Target.Attack(0), t(LimbusPlanner.plan(board), 0))
        // 옵션으로 '같으면 뺏는다'로 바꾸면 합
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), t(LimbusPlanner.plan(board, PlanOptions(equalSpeedSteals = true)), 0))
    }

    @Test
    fun `아무도 못 막는 공격은 보통 합으로라도 막는다`() {
        val skills = listOf(SinnerSkill(1, 1))
        val slots = listOf(EnemySlot(10, 1, targetSinner = 1), EnemySlot(11, 1, null))
        val board = Board(skills, slots, listOf(Enemy(10), Enemy(11)), mapOf((0 to 0) to Clash.NEUTRAL))
        assertEquals(Target.ClashWith(0, Clash.NEUTRAL), t(LimbusPlanner.plan(board), 0))
        // 옵션을 끄면 보통 합을 피하고 다른 적을 일방 공격
        assertEquals(Target.Attack(1), t(LimbusPlanner.plan(board, PlanOptions(coverWithNeutral = false)), 0))
    }

    @Test
    fun `모든 적 슬롯이 나를 노리고 전부 나쁘면 방어로 받는다`() {
        val skills = listOf(SinnerSkill(1, 1))
        val slots = listOf(EnemySlot(10, 1, 1), EnemySlot(10, 2, 1))
        val board = Board(
            skills, slots, listOf(Enemy(10)),
            mapOf((0 to 0) to Clash.HOPELESS, (0 to 1) to Clash.STRUGGLING)
        )
        assertTrue(t(LimbusPlanner.plan(board), 0) is Target.Defend)
        // 방어 옵션이 꺼져 있으면 가장 덜 나쁜 합
        assertEquals(Target.ClashWith(1, Clash.STRUGGLING), t(LimbusPlanner.plan(board, PlanOptions(defendOnBad = false)), 0))
    }

    @Test
    fun `지정 순서는 일방 공격 다음에 합이 온다`() {
        // 수감자1 스킬1 은 슬롯0 과 매우 우세 합, 스킬2 는 같은 슬롯0 에 일방 공격으로 붙는 상황
        val skills = listOf(SinnerSkill(1, 1, power = 10), SinnerSkill(1, 2, power = 5))
        val slots = listOf(EnemySlot(10, 1, 1))
        val board = Board(skills, slots, listOf(Enemy(10)), mapOf((0 to 0) to Clash.DOMINATING, (1 to 0) to Clash.STRUGGLING))
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), t(plan, 0))
        assertEquals(Target.Attack(0), t(plan, 1))
        assertEquals(listOf(1, 0), plan.applyOrder) // 일방 공격(스킬2) 먼저, 합(스킬1) 마지막
    }

    @Test
    fun `설명 문자열`() {
        val skills = listOf(SinnerSkill(1, 1))
        val slots = listOf(EnemySlot(10, 1, 1))
        val board = Board(skills, slots, listOf(Enemy(10)), mapOf((0 to 0) to Clash.DOMINATING))
        assertEquals("1. 수감자1 스킬1 → 적10 슬롯1 합 (매우 우세)", LimbusPlanner.describe(board, LimbusPlanner.plan(board)))
    }

    @Test
    fun `예측 글자 파싱`() {
        assertEquals(Clash.DOMINATING, Clash.fromLabel("매우 우세"))
        assertEquals(Clash.STRUGGLING, Clash.fromLabel("불리"))
        assertEquals(Clash.HOPELESS, Clash.fromLabel("매우열세"))
        assertEquals(Clash.UNKNOWN, Clash.fromLabel("???"))
    }

    @Test
    fun `약한 스킬로도 매우 우세면 약한 스킬이 합을 하고 강한 스킬은 흐트러진 적을 친다`() {
        val weak = SinnerSkill(1, 1, power = 5)
        val strong = SinnerSkill(1, 2, power = 18)
        val slots = listOf(EnemySlot(10, 1, targetSinner = 1), EnemySlot(11, 1, null))
        val board = Board(
            listOf(weak, strong), slots, listOf(Enemy(10), Enemy(11, staggered = true)),
            mapOf((0 to 0) to Clash.DOMINATING, (1 to 0) to Clash.DOMINATING)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), t(plan, 0)) // 약한 스킬이 합
        // 강한 스킬은 흐트러진 적(이미 2배)에 쓰면 낭비 → 멀쩡한 적10 에 일방 공격 (합 담당 뒤에 덧붙음)
        assertEquals(Target.Attack(0), t(plan, 1))
        assertEquals(listOf(1, 0), plan.applyOrder)
        // 옵션을 끄면 강한 스킬이 합
        val plan2 = LimbusPlanner.plan(board, PlanOptions(saveStrongSkills = false))
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), t(plan2, 1))
    }

    @Test
    fun `약한 스킬이 우세고 강한 스킬이 매우 우세면 등급이 우선한다`() {
        val weak = SinnerSkill(1, 1, power = 5)
        val strong = SinnerSkill(1, 2, power = 18)
        val slots = listOf(EnemySlot(10, 1, targetSinner = 1), EnemySlot(11, 1, null))
        val board = Board(
            listOf(weak, strong), slots, listOf(Enemy(10), Enemy(11, staggered = true)),
            mapOf((0 to 0) to Clash.FAVORED, (1 to 0) to Clash.DOMINATING)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), t(plan, 1))
        assertEquals(Target.Attack(1), t(plan, 0)) // 약한 스킬은 흐트러진 적
    }

    @Test
    fun `위력을 못 읽으면 스킬 등급으로 강약을 가른다`() {
        val s1 = SinnerSkill(1, 1, rank = 1)
        val s3 = SinnerSkill(1, 2, rank = 3)
        val slots = listOf(EnemySlot(10, 1, targetSinner = 1), EnemySlot(11, 1, null))
        val board = Board(
            listOf(s1, s3), slots, listOf(Enemy(10), Enemy(11, staggered = true)),
            mapOf((0 to 0) to Clash.DOMINATING, (1 to 0) to Clash.DOMINATING)
        )
        val plan = LimbusPlanner.plan(board)
        assertEquals(Target.ClashWith(0, Clash.DOMINATING), t(plan, 0)) // 스킬1(약함)이 합
        assertEquals(Target.Attack(0), t(plan, 1))                       // 스킬3(강함)은 멀쩡한 적에게
    }

    @Test
    fun `스킬 위력 추정`() {
        // 기본 4, 코인 +3 × 2개, 정신력 0 → 4 + 3×2×0.5 = 7
        assertEquals(7, SkillPower.estimate(4, 3, 2, 0))
        // 정신력 45 → 앞면 95% → 4 + 3×2×0.95 = 9.7 → 9
        assertEquals(9, SkillPower.estimate(4, 3, 2, 45))
        // 음수 코인(-2 × 3개) 은 뒷면이 좋음: 정신력 -45 → 뒷면 95% → 6 + (-2)×0.95×3 = 0.3 → 0 (최소 0)
        assertEquals(0, SkillPower.estimate(6, -2, 3, -45))
        // 코인 없음
        assertEquals(10, SkillPower.estimate(10, 5, 0))
        assertEquals(0.95f, SkillPower.headsChance(45), 0.001f)
        assertEquals(0.05f, SkillPower.headsChance(-45), 0.001f)
    }

    // ───────── 일방 공격 배분 (AttackAllocator) ─────────

    @Test
    fun `처치할 수 있으면 가장 약한 스킬로 끝낸다 (흐트러진 적은 2배)`() {
        val weak = SinnerSkill(1, 1, power = 6)
        val strong = SinnerSkill(2, 1, power = 20)
        // 적10: 흐트러짐, 체력 10 → 약한 스킬 6×2 = 12 로 처치 가능. 적11: 멀쩡, 체력 100
        val enemies = listOf(Enemy(10, staggered = true, hp = 10, maxHp = 100), Enemy(11, hp = 100, maxHp = 100))
        val slots = listOf(EnemySlot(10, 1, null), EnemySlot(11, 1, null))
        val plan = LimbusPlanner.plan(Board(listOf(weak, strong), slots, enemies, emptyMap()))
        assertEquals(Target.Attack(0), t(plan, 0)) // 약한 스킬이 처치
        assertEquals(Target.Attack(1), t(plan, 1)) // 강한 스킬은 멀쩡한 적
        assertTrue(plan.notes.any { it.contains("처치") })
    }

    @Test
    fun `강한 스킬로 멀쩡한 적을 흐트러짐 선 아래로 밀어 넣는다`() {
        val weak = SinnerSkill(1, 1, power = 5)
        val strong = SinnerSkill(2, 1, power = 18)
        // 적10: 체력 70, 선 55 → 15 필요 → 강한 스킬(18)만 가능. 적11: 흐트러짐, 체력 80 (처치 불가)
        val enemies = listOf(
            Enemy(10, hp = 70, maxHp = 100, staggerThresholds = listOf(55, 20)),
            Enemy(11, staggered = true, hp = 80, maxHp = 100),
        )
        val slots = listOf(EnemySlot(10, 1, null), EnemySlot(11, 1, null))
        val plan = LimbusPlanner.plan(Board(listOf(weak, strong), slots, enemies, emptyMap()))
        assertEquals(Target.Attack(0), t(plan, 1)) // 강한 스킬 → 선 넘기기
        // 적10 이 이번 턴에 흐트러져서(체력 52) 약한 스킬도 2배로 들어감 → 체력이 더 낮은 적10 에 집중
        assertEquals(Target.Attack(0), t(plan, 0))
        assertTrue(plan.notes.any { it.contains("흐트러뜨리기") })
    }

    @Test
    fun `선을 넘긴 뒤 같은 턴의 약한 스킬은 2배로 들어가 처치까지 이어진다`() {
        val a = SinnerSkill(1, 1, power = 10)
        val b = SinnerSkill(2, 1, power = 10)
        val c = SinnerSkill(3, 1, power = 10)
        // 체력 36, 선 30: 첫 스킬 10 → 26 (흐트러짐) → 다음 10×2=20 → 6 → 다음 20 → 처치. 세 개면 끝난다.
        val enemies = listOf(Enemy(10, hp = 36, maxHp = 100, staggerThresholds = listOf(30)))
        val slots = listOf(EnemySlot(10, 1, null))
        val plan = LimbusPlanner.plan(Board(listOf(a, b, c), slots, enemies, emptyMap()))
        assertTrue(plan.assignments.all { it.target == Target.Attack(0) })
        assertTrue(plan.notes.any { it.contains("흐트러뜨리기") })
        assertTrue(plan.notes.any { it.contains("처치") })
    }

    @Test
    fun `물리 내성을 반영해 조합을 고른다`() {
        val slash = SinnerSkill(1, 1, power = 10, attackType = AttackType.SLASH)
        val blunt = SinnerSkill(2, 1, power = 10, attackType = AttackType.BLUNT)
        // 적10 체력 15: 참격 내성(×0.5)=5, 타격 취약(×2)=20 → 타격 하나로 처치
        val enemies = listOf(
            Enemy(10, hp = 15, maxHp = 100, resists = mapOf(AttackType.SLASH to Resist.INEFFECTIVE, AttackType.BLUNT to Resist.FATAL)),
            Enemy(11, hp = 100, maxHp = 100),
        )
        val slots = listOf(EnemySlot(10, 1, null), EnemySlot(11, 1, null))
        val plan = LimbusPlanner.plan(Board(listOf(slash, blunt), slots, enemies, emptyMap()))
        assertEquals(Target.Attack(0), t(plan, 1)) // 타격이 처치
        assertEquals(Target.Attack(1), t(plan, 0)) // 참격은 다른 적
    }

    @Test
    fun `체력 숫자를 몰라도 강한 스킬은 멀쩡한 적, 약한 스킬은 흐트러진 적`() {
        val weak = SinnerSkill(1, 1, power = 5)
        val strong = SinnerSkill(2, 1, power = 15)
        val enemies = listOf(Enemy(10, staggered = true, hpRatio = 0.5f), Enemy(11, hpRatio = 0.4f), Enemy(12, hpRatio = 0.9f))
        val slots = listOf(EnemySlot(10, 1, null), EnemySlot(11, 1, null), EnemySlot(12, 1, null))
        val plan = LimbusPlanner.plan(Board(listOf(weak, strong), slots, enemies, emptyMap()))
        assertEquals(Target.Attack(0), t(plan, 0)) // 약한 → 흐트러진 적
        assertEquals(Target.Attack(1), t(plan, 1)) // 강한 → 멀쩡한 적 중 체력 낮은 쪽
    }
}

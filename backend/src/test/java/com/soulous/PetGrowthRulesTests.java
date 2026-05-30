package com.soulous;

import com.soulous.pet.Pet;
import com.soulous.pet.PetGrowthRules;
import com.soulous.pet.PetStage;
import com.soulous.pet.PetStatus;
import com.soulous.task.StudyTask;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【宠物成长规则测试类：验证 PetGrowthRules 的核心逻辑，包括经验奖励计算、
 * 心情对经验值的加成/惩罚、等级提升与溢出经验处理、反馈状态对宠物属性的影响、
 * 以及任务生命周期中的状态切换。所有测试直接操作 Pet 实体对象，无需 Spring 上下文。】
 */
class PetGrowthRulesTests {

    /**
     * 【测试场景：当经验值加上奖励足以升级时，宠物应正确升级，
     * 溢出的经验应结转到下一级，成长阶段从 EGG 变为 CHILD，
     * 状态变为 EXCITED，心情和饱腹感应被上限钳制到 100。】
     */
    @Test
    void rewardCanLevelUpAndCarryRemainingExp() {
        var pet = new Pet();
        pet.level = 1;
        pet.currentExp = 95;
        pet.nextLevelExp = 100;
        pet.mood = 95;
        pet.satiety = 98;

        var task = new StudyTask();
        task.baseExp = 30;

        var result = PetGrowthRules.applyReward(pet, task, 40);

        assertThat(result.leveledUp()).isTrue();
        assertThat(result.previousLevel()).isEqualTo(1);
        assertThat(result.currentLevel()).isEqualTo(2);
        assertThat(pet.level).isEqualTo(2);
        assertThat(pet.currentExp).isEqualTo(43);
        assertThat(pet.nextLevelExp).isEqualTo(170);
        assertThat(pet.growthStage).isEqualTo(PetStage.CHILD);
        assertThat(pet.status).isEqualTo(PetStatus.EXCITED);
        assertThat(pet.mood).isEqualTo(100);
        assertThat(pet.satiety).isEqualTo(100);
    }

    /**
     * 【测试场景：高额奖励使宠物状态变为 PROUD（自豪），但不足以触发升级时，
     * 等级保持不变，成长阶段仍为 EGG，经验值按公式正确计算。】
     */
    @Test
    void strongRewardMakesPetProudWithoutLevelUp() {
        var pet = new Pet();
        var task = new StudyTask();
        task.baseExp = 50;

        PetGrowthRules.applyReward(pet, task, 40);

        assertThat(pet.level).isEqualTo(1);
        assertThat(pet.currentExp).isEqualTo(48);
        assertThat(pet.status).isEqualTo(PetStatus.PROUD);
        assertThat(pet.growthStage).isEqualTo(PetStage.EGG);
    }

    /**
     * 【测试场景：当宠物心情值 >= 80（happy）时，经验奖励应获得 20% 加成。
     * 例如 baseExp=20、mood=90 时，实际获得 24 经验。】
     */
    @Test
    void happyMoodGivesTwentyPercentBonusExp() {
        var pet = new Pet();
        pet.mood = 90;
        var task = new StudyTask();
        task.baseExp = 20;

        var result = PetGrowthRules.applyReward(pet, task, 20);

        assertThat(result.expAmount()).isEqualTo(24);
        assertThat(pet.currentExp).isEqualTo(24);
    }

    /**
     * 【测试场景：当宠物心情值在 30-80 之间（normal）时，经验奖励保持基础值不变，
     * 无加成也无惩罚。】
     */
    @Test
    void normalMoodKeepsBaseExp() {
        var pet = new Pet();
        pet.mood = 50;
        var task = new StudyTask();
        task.baseExp = 20;

        var result = PetGrowthRules.applyReward(pet, task, 20);

        assertThat(result.expAmount()).isEqualTo(20);
        assertThat(pet.currentExp).isEqualTo(20);
    }

    /**
     * 【测试场景：当宠物心情值 <= 30（sad）时，经验奖励应被扣除 20%。
     * 例如 baseExp=20、mood=20 时，实际仅获得 16 经验。】
     */
    @Test
    void sadMoodGivesTwentyPercentPenalty() {
        var pet = new Pet();
        pet.mood = 20;
        var task = new StudyTask();
        task.baseExp = 20;

        var result = PetGrowthRules.applyReward(pet, task, 20);

        assertThat(result.expAmount()).isEqualTo(16);
        assertThat(pet.currentExp).isEqualTo(16);
    }

    /**
     * 【测试场景：心情阈值边界使用包含（inclusive）判断——
     * mood=80 应触发 happy 加成（+20%），mood=30 应触发 sad 惩罚（-20%），
     * 确保边界值的处理逻辑正确。】
     */
    @Test
    void moodBoundariesUseInclusiveThresholds() {
        var task = new StudyTask();
        task.baseExp = 20;

        var happy = new Pet();
        happy.mood = 80;
        PetGrowthRules.applyReward(happy, task, 10);
        assertThat(happy.currentExp).isEqualTo(12);

        var sad = new Pet();
        sad.mood = 30;
        PetGrowthRules.applyReward(sad, task, 10);
        assertThat(sad.currentExp).isEqualTo(8);
    }

    /**
     * 【测试场景：当 AI 审核返回"需要更多反馈"时，宠物的心情和饱腹感应被降低，
     * 但经验值和等级进度不受影响（currentExp 和 nextLevelExp 保持不变），
     * 状态变为 SLEEPY（疲惫），且心情和饱腹感的下限为 0。】
     */
    @Test
    void needMoreFeedbackLowersEnergyAndKeepsLevelProgress() {
        var pet = new Pet();
        pet.level = 1;
        pet.currentExp = 60;
        pet.nextLevelExp = 100;
        pet.mood = 5;
        pet.satiety = 1;

        PetGrowthRules.applyNeedsMoreFeedback(pet);

        assertThat(pet.currentExp).isEqualTo(60);
        assertThat(pet.nextLevelExp).isEqualTo(100);
        assertThat(pet.mood).isEqualTo(1);
        assertThat(pet.satiety).isEqualTo(0);
        assertThat(pet.status).isEqualTo(PetStatus.SLEEPY);
    }

    /**
     * 【测试场景：提交被拒绝后，心情和饱腹感应被钳制到 0（不允许负值），
     * 宠物状态标记为 SAD（伤心）。】
     */
    @Test
    void rejectedFeedbackClampsMoodAndMarksSad() {
        var pet = new Pet();
        pet.mood = 3;
        pet.satiety = 2;

        PetGrowthRules.applyRejectedFeedback(pet);

        assertThat(pet.mood).isZero();
        assertThat(pet.satiety).isZero();
        assertThat(pet.status).isEqualTo(PetStatus.SAD);
    }

    /**
     * 【测试场景：任务生命周期中宠物状态的正确切换——
     * 开始任务时状态变为 WORKING、心情+1、饱腹感-1；
     * 提交复核时状态变为 REVIEWING、饱腹感再-1。】
     */
    @Test
    void taskLifecycleStatesUseWorkingAndReviewing() {
        var pet = new Pet();

        PetGrowthRules.applyTaskStarted(pet);
        assertThat(pet.status).isEqualTo(PetStatus.WORKING);
        assertThat(pet.mood).isEqualTo(81);
        assertThat(pet.satiety).isEqualTo(79);

        PetGrowthRules.applySubmittedForReview(pet);
        assertThat(pet.status).isEqualTo(PetStatus.REVIEWING);
        assertThat(pet.satiety).isEqualTo(78);
    }
}

package com.soulous.checkin;

/**
 * 【打卡相关 DTO】
 */
public final class CheckinDtos {
    private CheckinDtos() {}

    /**
     * 【今日打卡状态】
     *
     * @param checkedInToday 今天是否已打卡
     * @param streak         当前连续天数（已打卡=含今天；未打卡=昨天的连续数，断签则为 0）
     * @param balance        当前金币余额
     */
    public record CheckinStatus(boolean checkedInToday, int streak, int balance) {}

    /**
     * 【打卡结果】
     *
     * @param claimed    本次是否真正领取（false=今天已领，幂等返回）
     * @param streak     连续天数
     * @param expReward  本次发放经验（无宠物时为 0）
     * @param coinReward 本次发放金币
     * @param balance    领取后金币余额
     * @param pet        领取后出战宠物快照（PetService.view 的 Map；未领养时为 null），
     *                   前端据此局部刷新宠物经验/等级，免去签到后整页重拉
     */
    public record CheckinResult(boolean claimed, int streak, int expReward, int coinReward, int balance, Object pet) {}
}

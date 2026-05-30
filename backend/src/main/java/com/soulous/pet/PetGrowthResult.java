package com.soulous.pet;

/**
 * 【宠物成长结果记录：封装一次成长操作（任务完成或专注完成）的计算结果。
 *  使用 Java record 实现不可变数据对象，供调用方获取成长详情。】
 *
 * @param expAmount 【本次实际获得的经验值（经过心情系数修正后）】
 * @param previousLevel 【操作前的宠物等级】
 * @param currentLevel 【操作后的宠物等级（如果升级则大于 previousLevel）】
 * @param leveledUp 【是否触发了升级，true 表示本次操作导致宠物升级】
 */
public record PetGrowthResult(int expAmount, int previousLevel, int currentLevel, boolean leveledUp) {}

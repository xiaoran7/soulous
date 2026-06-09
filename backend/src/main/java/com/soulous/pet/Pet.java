package com.soulous.pet;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 【宠物实体类：代表用户拥有的一只虚拟成长宠物（一个用户可拥有多只，对应宠物市场的不同品种）。
 *  映射到 owned_pet 表（取代旧的 pet 一对一表）。每只宠物独立保有自己的等级/经验/心情/饱腹感，
 *  互不共享；active=true 的为「出战」宠物，所有学习奖励只作用于出战宠物。
 *  通过 PetGrowthRules 驱动状态变化。】
 */
@Entity
@Table(name = "owned_pet")
public class Pet {
    /** 【主键 ID，自增生成】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    /** 【关联的用户账户，多对一（一个用户可拥有多只宠物），不可为空】 */
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    public UserAccount user;
    /** 【宠物品种（市场目录），决定形象/稀有度。可为空（兼容旧数据）】 */
    @ManyToOne
    @JoinColumn(name = "species_id")
    public PetSpecies species;
    /** 【是否出战：每个用户同一时刻只有一只 active=true，奖励作用于它】 */
    @jakarta.persistence.Column(nullable = false)
    public boolean active = true;
    /** 【获得时间（领养/购买）】 */
    public LocalDateTime acquiredAt = LocalDateTime.now();
    /** 【宠物名称，默认为用户名，最长32字符】 */
    public String name;
    /** 【宠物头像 URL，可为空表示使用默认头像】 */
    public String avatarUrl;
    /** 【当前等级，最小为1，影响成长阶段判定】 */
    public Integer level = 1;
    /** 【当前经验值，累计到 nextLevelExp 时升级，然后重置为溢出部分】 */
    public Integer currentExp = 0;
    /** 【升到下一级所需经验值，随等级递增：100 + level * 35】 */
    public Integer nextLevelExp = 100;
    /** 【心情值，范围 0-100，默认 80。影响经验加成系数：>=80 加成1.2x，<=30 减成0.8x】 */
    public Integer mood = 80;
    /** 【饱腹感，范围 0-100，默认 80。通过喂食恢复，各种操作会消耗】 */
    public Integer satiety = 80;
    /** 【成长阶段：EGG(蛋) → CHILD(幼年) → GROWING(成长中) → ADULT(成年)，由等级决定】 */
    @Enumerated(EnumType.STRING)
    public PetStage growthStage = PetStage.EGG;
    /** 【当前状态：反映宠物的情绪和活动，如 WORKING、HAPPY、SAD 等】 */
    @Enumerated(EnumType.STRING)
    public PetStatus status = PetStatus.NORMAL;
    /** 【创建时间，记录宠物诞生时刻】 */
    public LocalDateTime createdAt = LocalDateTime.now();
    /** 【最后更新时间，每次状态变更时刷新】 */
    public LocalDateTime updatedAt = LocalDateTime.now();
}

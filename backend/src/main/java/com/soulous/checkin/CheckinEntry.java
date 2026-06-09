package com.soulous.checkin;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 【每日打卡记录：每个用户每天最多一条（(user, checkin_date) 唯一）。
 *  记录当天的连续天数与发放的经验/金币奖励快照，用于连续打卡 streak 计算与展示。】
 */
@Entity
@Table(name = "daily_checkin",
        uniqueConstraints = @UniqueConstraint(name = "uq_daily_checkin_user_date", columnNames = {"user_id", "checkin_date"}))
public class CheckinEntry {
    /** 【主键，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属用户】 */
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    public UserAccount user;

    /** 【打卡日期】 */
    @Column(nullable = false)
    public LocalDate checkinDate;

    /** 【连续打卡天数（含当天）】 */
    @Column(nullable = false)
    public int streak;

    /** 【当天发放的经验奖励快照】 */
    @Column(nullable = false)
    public int expReward;

    /** 【当天发放的金币奖励快照】 */
    @Column(nullable = false)
    public int coinReward;

    /** 【创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}

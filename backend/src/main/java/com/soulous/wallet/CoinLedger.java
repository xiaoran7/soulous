package com.soulous.wallet;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 【金币流水：每一笔金币变动（入账/出账）都落一条，便于「我的」页展示与对账。
 *  amount 正数=入账、负数=出账；balanceAfter 记变动后余额快照，方便回溯。】
 */
@Entity
@Table(name = "coin_ledger")
public class CoinLedger {
    /** 【主键，自增】 */
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【所属用户】 */
    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    public UserAccount user;

    /** 【变动金额：正=入账，负=出账】 */
    @Column(nullable = false)
    public int amount;

    /** 【变动后余额快照】 */
    @Column(nullable = false)
    public int balanceAfter;

    /** 【来源：TASK / FOCUS / CHECKIN / PURCHASE / ADJUST 等】 */
    public String source;

    /** 【关联实体类型（可空），如 SUBMISSION / PET_SPECIES】 */
    public String refType;

    /** 【关联实体 ID（可空）】 */
    public Long refId;

    /** 【人类可读的变动说明】 */
    public String reason;

    /** 【创建时间】 */
    public LocalDateTime createdAt = LocalDateTime.now();
}

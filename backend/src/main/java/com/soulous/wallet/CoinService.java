package com.soulous.wallet;

import com.soulous.auth.UserAccount;
import com.soulous.auth.UserRepository;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【金币钱包服务：账号级金币余额的入账/出账与流水记录。
 *  金币是账号级共享资源（与每只宠物独立的经验不同），用于宠物市场购买等。
 *  所有变动都写 {@link CoinLedger} 流水，余额落在 {@code user_account.coin_balance}。】
 */
@Service
public class CoinService {
    private final UserRepository users;
    private final CoinLedgerRepository ledger;

    public CoinService(UserRepository users, CoinLedgerRepository ledger) {
        this.users = users;
        this.ledger = ledger;
    }

    /**
     * 【入账：增加金币并记流水。amount<=0 时视为无操作直接返回，避免脏流水。
     *  从库重新加载用户保证并发下余额一致。】
     *
     * @return 变动后的用户实体
     */
    @Transactional
    public UserAccount grant(UserAccount user, int amount, String source, String refType, Long refId, String reason) {
        var fresh = reload(user);
        if (amount <= 0) return fresh;
        fresh.coinBalance = fresh.coinBalance + amount;
        fresh.updatedAt = LocalDateTime.now();
        users.save(fresh);
        writeLedger(fresh, amount, fresh.coinBalance, source, refType, refId, reason);
        return fresh;
    }

    /**
     * 【出账：扣减金币并记流水。amount 必须为正；余额不足抛 {@link BadRequestException}。】
     *
     * @return 变动后的用户实体
     */
    @Transactional
    public UserAccount spend(UserAccount user, int amount, String source, String refType, Long refId, String reason) {
        if (amount <= 0) throw new BadRequestException("金额必须为正");
        var fresh = reload(user);
        if (fresh.coinBalance < amount) throw new BadRequestException("金币不足");
        fresh.coinBalance = fresh.coinBalance - amount;
        fresh.updatedAt = LocalDateTime.now();
        users.save(fresh);
        writeLedger(fresh, -amount, fresh.coinBalance, source, refType, refId, reason);
        return fresh;
    }

    /** 【当前余额】 */
    @Transactional(readOnly = true)
    public int balance(UserAccount user) {
        return reload(user).coinBalance;
    }

    /** 【最近 50 条流水】 */
    @Transactional(readOnly = true)
    public List<CoinLedger> recent(UserAccount user) {
        return ledger.findTop50ByUserOrderByIdDesc(user);
    }

    /** 【流水 JSON 视图，避免直接暴露 JPA 实体】 */
    public static Map<String, Object> view(CoinLedger l) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", l.id);
        m.put("amount", l.amount);
        m.put("balanceAfter", l.balanceAfter);
        m.put("source", l.source);
        m.put("refType", l.refType);
        m.put("refId", l.refId);
        m.put("reason", l.reason == null ? "" : l.reason);
        m.put("createdAt", l.createdAt);
        return m;
    }

    private UserAccount reload(UserAccount user) {
        return users.findById(user.id).orElseThrow(() -> new UnauthorizedException("Invalid user"));
    }

    private void writeLedger(UserAccount user, int amount, int balanceAfter,
                             String source, String refType, Long refId, String reason) {
        var l = new CoinLedger();
        l.user = user;
        l.amount = amount;
        l.balanceAfter = balanceAfter;
        l.source = source;
        l.refType = refType;
        l.refId = refId;
        l.reason = reason;
        ledger.save(l);
    }
}

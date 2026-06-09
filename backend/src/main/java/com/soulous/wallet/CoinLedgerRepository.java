package com.soulous.wallet;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 【金币流水仓库】 */
public interface CoinLedgerRepository extends JpaRepository<CoinLedger, Long> {
    /** 【某用户最近 50 条流水，按 id 倒序（自增、等同插入顺序，避免同毫秒 createdAt 排序歧义）】 */
    List<CoinLedger> findTop50ByUserOrderByIdDesc(UserAccount user);
}

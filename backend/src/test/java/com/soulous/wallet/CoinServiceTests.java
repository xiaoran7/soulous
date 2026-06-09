package com.soulous.wallet;

import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【CoinService 端到端：入账累加余额并记流水、出账扣减、余额不足抛错、非正入账为空操作。
 *  使用 H2 内存库（Flyway 跑 wallet 迁移）。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:wallet-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class CoinServiceTests {
    @Autowired UserService users;
    @Autowired CoinService coins;

    @Test
    void grantAccumulatesBalanceAndWritesLedger() {
        var user = registerFresh("grant");
        assertThat(coins.balance(user)).isZero();

        coins.grant(user, 30, "TASK", "SUBMISSION", 1L, "完成任务");
        coins.grant(user, 20, "CHECKIN", null, null, "每日打卡");

        assertThat(coins.balance(user)).isEqualTo(50);
        var ledger = coins.recent(user);
        assertThat(ledger).hasSize(2);
        // 最近在前：最后一笔 +20，余额快照 50
        assertThat(ledger.get(0).amount).isEqualTo(20);
        assertThat(ledger.get(0).balanceAfter).isEqualTo(50);
        assertThat(ledger.get(1).amount).isEqualTo(30);
        assertThat(ledger.get(1).balanceAfter).isEqualTo(30);
    }

    @Test
    void spendDeductsAndRejectsWhenInsufficient() {
        var user = registerFresh("spend");
        coins.grant(user, 100, "TASK", null, null, "攒币");

        coins.spend(user, 60, "PURCHASE", "PET_SPECIES", 7L, "购买宠物");
        assertThat(coins.balance(user)).isEqualTo(40);
        assertThat(coins.recent(user).get(0).amount).isEqualTo(-60);

        assertThatThrownBy(() -> coins.spend(user, 999, "PURCHASE", null, null, "买不起"))
                .isInstanceOf(BadRequestException.class);
        assertThat(coins.balance(user)).isEqualTo(40); // 失败不扣减
    }

    @Test
    void nonPositiveGrantIsNoop() {
        var user = registerFresh("noop");
        coins.grant(user, 0, "ADJUST", null, null, "零");
        coins.grant(user, -5, "ADJUST", null, null, "负");
        assertThat(coins.balance(user)).isZero();
        assertThat(coins.recent(user)).isEmpty();
    }

    private UserAccount registerFresh(String prefix) {
        var unique = prefix + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", prefix, unique + "@example.com"));
        return users.byToken(auth.token());
    }
}

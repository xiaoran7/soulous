package com.soulous.pet;

import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.common.exception.BadRequestException;
import com.soulous.wallet.CoinService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 【宠物市场端到端：免费领养首只、非入门款拒绝、金币购买、余额不足拒绝、切换出战、每宠独立升级。
 *  使用 H2 内存库（Flyway 跑 pet_market 迁移并 seed 品种）。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:petmarket-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class PetMarketServiceTests {
    @Autowired UserService users;
    @Autowired PetService pets;
    @Autowired CoinService coins;

    @Test
    void adoptStarterOnceThenRejectSecond() {
        var user = fresh("adopt");
        var pet = pets.adoptStarter(user, "feixue");
        assertThat(pet.active).isTrue();
        assertThat(pet.species.slug).isEqualTo("feixue");
        assertThat(pets.getActiveOrNull(user)).isNotNull();

        assertThatThrownBy(() -> pets.adoptStarter(user, "clawd")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void cannotAdoptNonStarter() {
        var user = fresh("nonstarter");
        assertThatThrownBy(() -> pets.adoptStarter(user, "guga")).isInstanceOf(BadRequestException.class);
    }

    @Test
    void buySpendsCoinsKeepsFirstActive() {
        var user = fresh("buy");
        pets.adoptStarter(user, "feixue");      // 首只，免费，出战
        coins.grant(user, 200, "TEST", null, null, "测试发币");

        var ikun = pets.buy(user, "ikun");      // 价格 150
        assertThat(coins.balance(user)).isEqualTo(50);
        assertThat(ikun.active).isFalse();        // 非首只，不自动出战
        assertThat(pets.getActiveOrNull(user).species.slug).isEqualTo("feixue");
        assertThat(pets.owned(user)).hasSize(2);
    }

    @Test
    void buyRejectedWhenInsufficientCoins() {
        var user = fresh("poor");
        pets.adoptStarter(user, "feixue");
        assertThatThrownBy(() -> pets.buy(user, "tianyi")).isInstanceOf(BadRequestException.class); // 300 金币，余额 0
    }

    @Test
    void setActiveSwitchesAndLevelingIsIndependent() {
        var user = fresh("switch");
        pets.adoptStarter(user, "feixue");
        coins.grant(user, 200, "TEST", null, null, "测试发币");
        var ikun = pets.buy(user, "ikun");

        // 给出战(feixue)加经验
        pets.addCheckinExp(user, 50, 1.0, "刷经验");
        int feixueExp = pets.getActiveOrNull(user).currentExp;
        assertThat(feixueExp).isGreaterThan(0);

        // 切换到 ikun：它应从 0 经验开始（每宠独立，不共享）
        pets.setActive(user, ikun.id);
        assertThat(pets.getActiveOrNull(user).species.slug).isEqualTo("ikun");
        assertThat(pets.getActiveOrNull(user).currentExp).isZero();

        // 给 ikun 加经验后，feixue 的经验不变
        pets.addCheckinExp(user, 30, 1.0, "刷经验");
        var owned = pets.owned(user);
        var feixue = owned.stream().filter(p -> p.species.slug.equals("feixue")).findFirst().orElseThrow();
        var ikunAfter = owned.stream().filter(p -> p.species.slug.equals("ikun")).findFirst().orElseThrow();
        assertThat(feixue.currentExp).isEqualTo(feixueExp); // 未被影响
        assertThat(ikunAfter.currentExp).isGreaterThan(0);
    }

    private UserAccount fresh(String prefix) {
        var unique = prefix + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", prefix, unique + "@example.com"));
        return users.byToken(auth.token());
    }
}

package com.soulous.checkin;

import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.pet.PetService;
import com.soulous.wallet.CoinService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【CheckinService 端到端：首次打卡发奖+streak=1、同日幂等、连续日 streak 递增并放大奖励。
 *  使用 H2 内存库（Flyway 跑 daily_checkin 迁移）。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:checkin-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class CheckinServiceTests {
    @Autowired UserService users;
    @Autowired CheckinService checkin;
    @Autowired CoinService coins;
    @Autowired PetService pets;
    @Autowired CheckinRepository repo;

    @Test
    void firstCheckinGrantsRewardsAndStreakOne() {
        var user = registerFresh("first");
        var before = checkin.status(user);
        assertThat(before.checkedInToday()).isFalse();
        assertThat(before.streak()).isZero();

        var r = checkin.checkin(user);
        assertThat(r.claimed()).isTrue();
        assertThat(r.streak()).isEqualTo(1);
        assertThat(r.coinReward()).isEqualTo(10);   // BASE_COINS
        assertThat(r.expReward()).isEqualTo(15);     // BASE_EXP * streakMultiplier(1)=1.0
        assertThat(coins.balance(user)).isEqualTo(10);
        assertThat(pets.get(user).currentExp).isGreaterThan(0); // 宠物经验已增长

        var after = checkin.status(user);
        assertThat(after.checkedInToday()).isTrue();
        assertThat(after.streak()).isEqualTo(1);
    }

    @Test
    void sameDayCheckinIsIdempotent() {
        var user = registerFresh("idem");
        checkin.checkin(user);
        var second = checkin.checkin(user);
        assertThat(second.claimed()).isFalse();
        assertThat(coins.balance(user)).isEqualTo(10); // 未二次发奖
    }

    @Test
    void consecutiveDayIncrementsStreakAndScalesReward() {
        var user = registerFresh("streak");
        // 手动补一条「昨天 streak=3」的记录，模拟连续打卡
        var yesterday = new CheckinEntry();
        yesterday.user = user;
        yesterday.checkinDate = LocalDate.now().minusDays(1);
        yesterday.streak = 3;
        yesterday.expReward = 0;
        yesterday.coinReward = 0;
        repo.save(yesterday);

        var r = checkin.checkin(user);
        assertThat(r.streak()).isEqualTo(4);
        assertThat(r.coinReward()).isEqualTo(16); // 10 + min(10,(4-1)*2)=16
        assertThat(coins.balance(user)).isEqualTo(16);
    }

    private UserAccount registerFresh(String prefix) {
        var unique = prefix + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", prefix, unique + "@example.com"));
        var user = users.byToken(auth.token());
        pets.adoptStarter(user, "feixue"); // 新模型下用户默认无宠物，测试需先领养出战宠物
        return user;
    }
}

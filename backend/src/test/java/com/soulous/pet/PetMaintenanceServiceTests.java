package com.soulous.pet;

import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import com.soulous.checkin.CheckinService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【PetMaintenanceService 端到端：久未打卡的宠物被衰减（不掉级），当日活跃的不被衰减。
 *  使用 H2 内存库。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:petmaint-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class PetMaintenanceServiceTests {
    @Autowired UserService users;
    @Autowired PetMaintenanceService maintenance;
    @Autowired PetService pets;
    @Autowired PetRepository petRepo;
    @Autowired CheckinService checkin;

    @Test
    void decaysPetOfInactiveUser() {
        var user = registerFresh("inactive");
        var pet = pets.get(user);
        pet.createdAt = LocalDateTime.now().minusDays(10); // 无打卡记录 → 以创建日为活跃基准
        pet.mood = 80;
        pet.satiety = 80;
        petRepo.save(pet);
        int levelBefore = pet.level;

        int decayed = maintenance.decayInactive(LocalDate.now());

        assertThat(decayed).isGreaterThanOrEqualTo(1);
        var after = pets.get(user);
        assertThat(after.mood).isLessThan(80);
        assertThat(after.satiety).isLessThan(80);
        assertThat(after.level).isEqualTo(levelBefore); // 不掉级
    }

    @Test
    void doesNotDecayActiveUser() {
        var user = registerFresh("active");
        checkin.checkin(user); // 今天打卡 → 活跃
        int moodBefore = pets.get(user).mood;
        int satietyBefore = pets.get(user).satiety;

        maintenance.decayInactive(LocalDate.now());

        var after = pets.get(user);
        assertThat(after.mood).isEqualTo(moodBefore);
        assertThat(after.satiety).isEqualTo(satietyBefore);
    }

    private UserAccount registerFresh(String prefix) {
        var unique = prefix + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", prefix, unique + "@example.com"));
        var user = users.byToken(auth.token());
        pets.adoptStarter(user, "feixue"); // 新模型下用户默认无宠物，测试需先领养出战宠物
        return user;
    }
}

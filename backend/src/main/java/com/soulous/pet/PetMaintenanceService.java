package com.soulous.pet;

import com.soulous.checkin.CheckinRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 【宠物维护服务：每日定时对「久未打卡」的用户施加温和的断签衰减惩罚（心情/饱腹感下降，不掉等级）。
 *  活跃判定基于用户最近一次每日打卡日期；从未打卡则以宠物创建日为基准。
 *  衰减只降状态、不动经验/等级，目的是督促回归而非劝退。】
 */
@Service
public class PetMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(PetMaintenanceService.class);

    private final PetRepository pets;
    private final CheckinRepository checkins;
    /** 【是否启用断签衰减】 */
    private final boolean enabled;
    /** 【判定为「断签」的不活跃天数阈值（含），默认 2 天】 */
    private final int inactiveDays;

    public PetMaintenanceService(PetRepository pets, CheckinRepository checkins,
                                 @Value("${soulous.pet.decay.enabled:true}") boolean enabled,
                                 @Value("${soulous.pet.decay.inactive-days:2}") int inactiveDays) {
        this.pets = pets;
        this.checkins = checkins;
        this.enabled = enabled;
        this.inactiveDays = Math.max(1, inactiveDays);
    }

    /** 【每日定时入口：默认凌晨 4 点跑一次断签衰减】 */
    @Scheduled(cron = "${soulous.pet.decay.cron:0 0 4 * * *}")
    public void runDailyDecay() {
        if (!enabled) return;
        int decayed = decayInactive(LocalDate.now());
        if (decayed > 0) log.info("Pet inactivity decay applied to {} pet(s)", decayed);
    }

    /**
     * 【对所有久未活跃的宠物施加一次衰减，返回受影响数量。
     *  独立于定时入口，便于测试直接调用。】
     *
     * @param today 【基准日期（通常为今天）】
     * @return 被衰减的宠物数量
     */
    @Transactional
    public int decayInactive(LocalDate today) {
        int decayed = 0;
        for (Pet pet : pets.findAll()) {
            if (pet.user == null) continue;
            var last = checkins.findTopByUserOrderByCheckinDateDesc(pet.user);
            LocalDate lastActive = last.map(c -> c.checkinDate)
                    .orElseGet(() -> pet.createdAt == null ? today : pet.createdAt.toLocalDate());
            long gapDays = ChronoUnit.DAYS.between(lastActive, today);
            if (gapDays >= inactiveDays) {
                PetGrowthRules.applyInactivityDecay(pet);
                pet.updatedAt = LocalDateTime.now();
                pets.save(pet);
                decayed++;
            }
        }
        return decayed;
    }
}

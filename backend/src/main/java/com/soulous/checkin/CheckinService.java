package com.soulous.checkin;

import com.soulous.auth.UserAccount;
import com.soulous.checkin.CheckinDtos.CheckinResult;
import com.soulous.checkin.CheckinDtos.CheckinStatus;
import com.soulous.common.exception.NotFoundException;
import com.soulous.pet.PetGrowthRules;
import com.soulous.pet.PetService;
import com.soulous.wallet.CoinService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 【每日打卡服务：每天首次打卡发放金币 + 给出战宠物发放经验（叠加连续打卡 streak 倍率）。
 *  连续打卡天数越多奖励越高；同一天重复打卡幂等（返回当天已发放结果，不重复发奖）。
 *  无宠物（如未领养）时仅发金币、跳过经验。】
 */
@Service
public class CheckinService {
    /** 【每日基础经验（再乘 streak 与心情倍率）】 */
    static final int BASE_EXP = 15;
    /** 【每日基础金币】 */
    static final int BASE_COINS = 10;

    private final CheckinRepository repo;
    private final CoinService coins;
    private final PetService pets;

    public CheckinService(CheckinRepository repo, CoinService coins, PetService pets) {
        this.repo = repo;
        this.coins = coins;
        this.pets = pets;
    }

    /** 【今日打卡状态：是否已打卡 + 当前连续天数 + 余额】 */
    @Transactional(readOnly = true)
    public CheckinStatus status(UserAccount user) {
        var today = LocalDate.now();
        var todayRow = repo.findByUserAndCheckinDate(user, today);
        int streak = todayRow.map(c -> c.streak)
                .orElseGet(() -> repo.findByUserAndCheckinDate(user, today.minusDays(1)).map(c -> c.streak).orElse(0));
        return new CheckinStatus(todayRow.isPresent(), streak, coins.balance(user));
    }

    /**
     * 【执行每日打卡：当天首次发奖；重复打卡幂等返回。
     *  连续天数 = 昨天的 streak + 1（断签则从 1 重新开始）。】
     */
    @Transactional
    public CheckinResult checkin(UserAccount user) {
        var today = LocalDate.now();
        var existing = repo.findByUserAndCheckinDate(user, today);
        if (existing.isPresent()) {
            var e = existing.get();
            return new CheckinResult(false, e.streak, e.expReward, e.coinReward, coins.balance(user),
                    PetService.view(pets.getActiveOrNull(user)));
        }

        int prevStreak = repo.findByUserAndCheckinDate(user, today.minusDays(1)).map(c -> c.streak).orElse(0);
        int streak = prevStreak + 1;
        double mult = PetGrowthRules.streakMultiplier(streak);
        int coinReward = BASE_COINS + Math.min(10, (streak - 1) * 2);
        int expReward = (int) Math.round(BASE_EXP * mult);

        coins.grant(user, coinReward, "CHECKIN", "CHECKIN", null, "每日打卡 第 " + streak + " 天");
        // 无宠物（未领养）时跳过经验，仅发金币
        try {
            pets.addCheckinExp(user, BASE_EXP, mult, "每日打卡 第 " + streak + " 天");
        } catch (NotFoundException ignore) {
            expReward = 0;
        }

        var entry = new CheckinEntry();
        entry.user = user;
        entry.checkinDate = today;
        entry.streak = streak;
        entry.expReward = expReward;
        entry.coinReward = coinReward;
        repo.save(entry);

        // 顺带回传更新后的出战宠物快照，前端签到成功即可局部刷新经验/等级，无需整页重拉。
        return new CheckinResult(true, streak, expReward, coinReward, coins.balance(user),
                PetService.view(pets.getActiveOrNull(user)));
    }
}

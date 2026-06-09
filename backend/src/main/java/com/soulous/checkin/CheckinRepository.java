package com.soulous.checkin;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/** 【每日打卡仓库】 */
public interface CheckinRepository extends JpaRepository<CheckinEntry, Long> {
    /** 【查某用户某天的打卡记录】 */
    Optional<CheckinEntry> findByUserAndCheckinDate(UserAccount user, LocalDate checkinDate);

    /** 【某用户最近一次打卡记录（用于断签衰减判断）】 */
    Optional<CheckinEntry> findTopByUserOrderByCheckinDateDesc(UserAccount user);
}

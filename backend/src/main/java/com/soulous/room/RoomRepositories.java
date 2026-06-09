package com.soulous.room;

import com.soulous.auth.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 【自习室仓库】 */
interface StudyRoomRepository extends JpaRepository<StudyRoom, Long> {
    List<StudyRoom> findAllByOrderByCreatedAtDesc();
}

/** 【自习室成员仓库】 */
interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {
    Optional<RoomMember> findByRoomAndUser(StudyRoom room, UserAccount user);

    List<RoomMember> findByUser(UserAccount user);

    /** 【房间内在线成员（lastSeen 晚于阈值）】 */
    List<RoomMember> findByRoomAndLastSeenAtAfterOrderByJoinedAtAsc(StudyRoom room, LocalDateTime since);

    /** 【房间内在线成员数】 */
    long countByRoomAndLastSeenAtAfter(StudyRoom room, LocalDateTime since);

    /** 【房间内全部成员数（含离线），用于空房清理】 */
    long countByRoom(StudyRoom room);
}

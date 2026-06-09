package com.soulous.room;

import com.soulous.auth.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 【自习室成员：一个用户在某房间的成员关系 + 在线/计时心跳状态。
 *  lastSeenAt 在心跳时刷新，超过在线窗口视为离线。focusing/focusSeconds 表示其当前是否在专注及累计秒数。】
 */
@Entity
@Table(name = "room_member",
        uniqueConstraints = @UniqueConstraint(name = "uq_room_member", columnNames = {"room_id", "user_id"}))
public class RoomMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "room_id")
    public StudyRoom room;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    public UserAccount user;

    public LocalDateTime joinedAt = LocalDateTime.now();

    /** 【最近心跳时间，判断在线】 */
    @Column(nullable = false)
    public LocalDateTime lastSeenAt = LocalDateTime.now();

    /** 【当前是否在专注计时】 */
    @Column(nullable = false)
    public boolean focusing = false;

    /** 【本次会话累计专注秒数（前端上报）】 */
    @Column(nullable = false)
    public int focusSeconds = 0;
}

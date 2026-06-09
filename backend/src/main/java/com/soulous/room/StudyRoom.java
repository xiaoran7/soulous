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

import java.time.LocalDateTime;

/**
 * 【共享自习室：用户创建的轻量房间。成员在线状态/计时由 RoomMember 心跳维护，
 *  房间只承载「一起自习」的氛围，不做实时音视频/聊天。】
 */
@Entity
@Table(name = "study_room")
public class StudyRoom {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 【房间名】 */
    @Column(nullable = false)
    public String name;

    /** 【创建者】 */
    @ManyToOne(optional = false)
    @JoinColumn(name = "owner_id")
    public UserAccount owner;

    public LocalDateTime createdAt = LocalDateTime.now();
}

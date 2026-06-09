package com.soulous.room;

import com.soulous.auth.UserAccount;
import com.soulous.common.exception.BadRequestException;
import com.soulous.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【共享自习室服务（轻量在线状态）：建房/进房/退房/心跳；成员在线靠心跳的 lastSeenAt 在窗口内判定。
 *  一个用户同一时刻只在一个房间。房间只展示「谁在线/在专注 + 各自计时」，不做实时聊天/音视频。】
 */
@Service
public class RoomService {
    /** 【在线判定窗口（秒）：lastSeen 在此窗口内视为在线。前端心跳间隔应小于此值】 */
    static final long ONLINE_WINDOW_SECONDS = 90;
    /** 【房间名最大长度】 */
    private static final int MAX_NAME = 40;

    private final StudyRoomRepository rooms;
    private final RoomMemberRepository members;

    RoomService(StudyRoomRepository rooms, RoomMemberRepository members) {
        this.rooms = rooms;
        this.members = members;
    }

    private LocalDateTime onlineSince() {
        return LocalDateTime.now().minusSeconds(ONLINE_WINDOW_SECONDS);
    }

    /** 【房间广场：列出全部房间 + 各自在线人数】 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listRooms() {
        var since = onlineSince();
        return rooms.findAllByOrderByCreatedAtDesc().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id);
            m.put("name", r.name);
            m.put("ownerName", displayName(r.owner));
            m.put("onlineCount", members.countByRoomAndLastSeenAtAfter(r, since));
            return m;
        }).toList();
    }

    /** 【创建房间并自动进入】 */
    @Transactional
    public Map<String, Object> create(UserAccount user, String name) {
        var clean = (name == null || name.isBlank()) ? displayName(user) + "的自习室" : trunc(name.trim(), MAX_NAME);
        var room = new StudyRoom();
        room.name = clean;
        room.owner = user;
        room.createdAt = LocalDateTime.now();
        rooms.save(room);
        joinInternal(user, room);
        return detail(user, room.id);
    }

    /** 【加入房间：先退出其他房间（同时只在一个房间）】 */
    @Transactional
    public Map<String, Object> join(UserAccount user, Long roomId) {
        var room = rooms.findById(roomId).orElseThrow(() -> new NotFoundException("自习室不存在"));
        members.findByUser(user).forEach(m -> {
            if (!m.room.id.equals(roomId)) {
                var other = m.room;
                members.delete(m);
                if (members.countByRoom(other) == 0) rooms.delete(other); // 退出后空房清理
            }
        });
        joinInternal(user, room);
        return detail(user, roomId);
    }

    /** 【退出房间：成员移除；房间若已无任何成员则删除，避免空房堆积】 */
    @Transactional
    public void leave(UserAccount user, Long roomId) {
        var room = rooms.findById(roomId).orElse(null);
        if (room == null) return;
        members.findByRoomAndUser(room, user).ifPresent(members::delete);
        if (members.countByRoom(room) == 0) rooms.delete(room);
    }

    /** 【心跳：刷新在线时间 + 上报专注状态/秒数，返回房间最新详情】 */
    @Transactional
    public Map<String, Object> heartbeat(UserAccount user, Long roomId, boolean focusing, int focusSeconds) {
        var room = rooms.findById(roomId).orElseThrow(() -> new NotFoundException("自习室不存在"));
        var m = members.findByRoomAndUser(room, user).orElseThrow(() -> new BadRequestException("你还没加入该自习室"));
        m.lastSeenAt = LocalDateTime.now();
        m.focusing = focusing;
        m.focusSeconds = Math.max(0, focusSeconds);
        members.save(m);
        return detail(user, roomId);
    }

    /** 【房间详情：在线成员列表 + 各自专注状态/计时】 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(UserAccount user, Long roomId) {
        var room = rooms.findById(roomId).orElseThrow(() -> new NotFoundException("自习室不存在"));
        var online = members.findByRoomAndLastSeenAtAfterOrderByJoinedAtAsc(room, onlineSince());
        var memberViews = online.stream().map(m -> {
            var mv = new LinkedHashMap<String, Object>();
            mv.put("userId", m.user.id);
            mv.put("name", displayName(m.user));
            mv.put("focusing", m.focusing);
            mv.put("focusSeconds", m.focusSeconds);
            mv.put("self", m.user.id.equals(user.id));
            return mv;
        }).toList();
        var out = new LinkedHashMap<String, Object>();
        out.put("id", room.id);
        out.put("name", room.name);
        out.put("ownerName", displayName(room.owner));
        out.put("members", memberViews);
        out.put("onlineCount", memberViews.size());
        out.put("joined", online.stream().anyMatch(m -> m.user.id.equals(user.id)));
        return out;
    }

    private void joinInternal(UserAccount user, StudyRoom room) {
        var m = members.findByRoomAndUser(room, user).orElseGet(() -> {
            var x = new RoomMember();
            x.room = room;
            x.user = user;
            x.joinedAt = LocalDateTime.now();
            return x;
        });
        m.lastSeenAt = LocalDateTime.now();
        members.save(m);
    }

    private static String displayName(UserAccount u) {
        if (u == null) return "匿名";
        return (u.nickname == null || u.nickname.isBlank()) ? u.username : u.nickname;
    }

    private static String trunc(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}

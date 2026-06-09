package com.soulous.room;

import com.soulous.auth.UserService;
import com.soulous.common.web.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 【共享自习室 REST 控制器：房间广场 / 建房 / 进房 / 退房 / 心跳。需登录。
 *  在线状态走前端轮询心跳（轻量，不引入 WebSocket）。】
 */
@RestController
@RequestMapping("/api/rooms")
class RoomController extends BaseController {
    private final RoomService rooms;

    RoomController(UserService users, RoomService rooms) {
        super(users);
        this.rooms = rooms;
    }

    /** 【房间广场：列出全部房间 + 在线人数】 */
    @GetMapping
    List<Map<String, Object>> list(HttpServletRequest request) {
        current(request);
        return rooms.listRooms();
    }

    /** 【创建并进入房间】 */
    @PostMapping
    Map<String, Object> create(HttpServletRequest request, @RequestBody(required = false) CreateRoomRequest body) {
        return rooms.create(current(request), body == null ? null : body.name());
    }

    /** 【房间详情（在线成员）】 */
    @GetMapping("/{id}")
    Map<String, Object> detail(HttpServletRequest request, @PathVariable Long id) {
        return rooms.detail(current(request), id);
    }

    /** 【加入房间】 */
    @PostMapping("/{id}/join")
    Map<String, Object> join(HttpServletRequest request, @PathVariable Long id) {
        return rooms.join(current(request), id);
    }

    /** 【心跳：刷新在线 + 上报专注状态/秒数】 */
    @PostMapping("/{id}/heartbeat")
    Map<String, Object> heartbeat(HttpServletRequest request, @PathVariable Long id,
                                  @RequestBody(required = false) HeartbeatRequest body) {
        boolean focusing = body != null && body.focusing();
        int seconds = body == null ? 0 : body.focusSeconds();
        return rooms.heartbeat(current(request), id, focusing, seconds);
    }

    /** 【退出房间】 */
    @DeleteMapping("/{id}/leave")
    Map<String, Object> leave(HttpServletRequest request, @PathVariable Long id) {
        rooms.leave(current(request), id);
        return Map.of("left", true, "id", id);
    }

    /** 【建房请求体】 */
    record CreateRoomRequest(String name) {}

    /** 【心跳请求体】 */
    record HeartbeatRequest(boolean focusing, int focusSeconds) {}
}

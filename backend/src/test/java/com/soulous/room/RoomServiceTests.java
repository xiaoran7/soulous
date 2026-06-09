package com.soulous.room;

import com.soulous.auth.RegisterRequest;
import com.soulous.auth.UserAccount;
import com.soulous.auth.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【RoomService 端到端：建房自动进入、他人加入后在线成员可见、心跳上报专注状态、退房空房清理。
 *  使用 H2 内存库。】
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:room-test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "soulous.upload-dir=target/test-uploads"
})
class RoomServiceTests {
    @Autowired UserService users;
    @Autowired RoomService rooms;

    @Test
    @SuppressWarnings("unchecked")
    void createJoinHeartbeatAndPresence() {
        var alice = fresh("alice");
        var bob = fresh("bob");

        var room = rooms.create(alice, "图书馆三楼");
        Long roomId = (Long) room.get("id");
        assertThat(room.get("name")).isEqualTo("图书馆三楼");
        assertThat(room.get("onlineCount")).isEqualTo(1); // 建房者自动在线

        // bob 加入 → 在线 2 人
        var joined = rooms.join(bob, roomId);
        assertThat(joined.get("onlineCount")).isEqualTo(2);

        // bob 心跳上报专注 25 分钟
        rooms.heartbeat(bob, roomId, true, 1500);
        var detail = rooms.detail(alice, roomId);
        var members = (List<Map<String, Object>>) detail.get("members");
        assertThat(members).hasSize(2);
        var bobView = members.stream().filter(m -> "bob".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(bobView.get("focusing")).isEqualTo(true);
        assertThat(bobView.get("focusSeconds")).isEqualTo(1500);

        // 广场能看到该房间且在线 2
        var plaza = rooms.listRooms();
        assertThat(plaza).anySatisfy(r -> {
            assertThat(r.get("id")).isEqualTo(roomId);
            assertThat(r.get("onlineCount")).isEqualTo(2L);
        });
    }

    @Test
    void joiningAnotherRoomLeavesPrevious() {
        var u = fresh("mover");
        var r1 = rooms.create(u, "房间一");
        var r2id = (Long) rooms.create(fresh("host2"), "房间二").get("id");

        rooms.join(u, r2id);
        // u 现在只在房间二；房间一只剩 0 在线（u 已退出，房主 host... r1 owner 是 u 本人 → r1 现在无成员被删）
        var plaza = rooms.listRooms();
        assertThat(plaza).noneSatisfy(r -> assertThat(r.get("name")).isEqualTo("房间一"));
    }

    @Test
    void leaveRemovesEmptyRoom() {
        var u = fresh("lonely");
        var roomId = (Long) rooms.create(u, "独自一人").get("id");
        rooms.leave(u, roomId);
        assertThat(rooms.listRooms()).noneSatisfy(r -> assertThat(r.get("id")).isEqualTo(roomId));
    }

    private UserAccount fresh(String prefix) {
        var unique = prefix + System.nanoTime();
        var auth = users.register(new RegisterRequest(unique, "Passw0rd!", prefix, unique + "@example.com"));
        return users.byToken(auth.token());
    }
}

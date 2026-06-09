-- 【中文：共享自习室（MySQL 版本）—— study_room 房间 + room_member 成员（含在线心跳/专注状态）。】
-- Shared study rooms: rooms + members with heartbeat presence.
CREATE TABLE study_room (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_study_room_owner FOREIGN KEY (owner_id) REFERENCES user_account(id),
    KEY idx_study_room_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE room_member (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    focusing BOOLEAN NOT NULL DEFAULT FALSE,
    focus_seconds INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_room_member_room FOREIGN KEY (room_id) REFERENCES study_room(id),
    CONSTRAINT fk_room_member_user FOREIGN KEY (user_id) REFERENCES user_account(id),
    CONSTRAINT uq_room_member UNIQUE (room_id, user_id),
    KEY idx_room_member_room (room_id),
    KEY idx_room_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

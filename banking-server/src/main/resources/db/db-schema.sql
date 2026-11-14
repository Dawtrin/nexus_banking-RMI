-- NEXUS Distributed Chat schema v2 (MySQL 8 / MariaDB 10.5+).
-- Applied automatically at server start when database.schema.auto=true (default); every statement is idempotent.
-- Manual use: CREATE DATABASE nexus_chat CHARACTER SET utf8mb4; then run this file against that database
-- (see scripts/init-db.sql). Timestamps are stored as UTC DATETIME(3).

CREATE TABLE IF NOT EXISTS chat_rooms (
    room_id      VARCHAR(100) NOT NULL,
    room_name    VARCHAR(200) NOT NULL,
    description  VARCHAR(500) NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS origin_sequences (
    origin_server_id VARCHAR(64) NOT NULL,
    last_seq         BIGINT      NOT NULL,
    PRIMARY KEY (origin_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_messages (
    local_sequence    BIGINT       NOT NULL AUTO_INCREMENT,
    message_id        VARCHAR(64)  NOT NULL,
    room_id           VARCHAR(100) NOT NULL,
    sender_id         VARCHAR(100) NOT NULL,
    sender_name       VARCHAR(100) NOT NULL,
    content           MEDIUMTEXT   NOT NULL,
    message_type      VARCHAR(16)  NOT NULL DEFAULT 'CHAT',
    created_at        DATETIME(3)  NOT NULL,
    origin_server_id  VARCHAR(64)  NOT NULL,
    origin_sequence   BIGINT       NOT NULL,
    client_message_id VARCHAR(64)  NULL,
    sender_avatar     MEDIUMTEXT   NULL,
    PRIMARY KEY (local_sequence),
    UNIQUE KEY uq_message_id (message_id),
    UNIQUE KEY uq_origin_seq (origin_server_id, origin_sequence),
    UNIQUE KEY uq_client_msg (origin_server_id, sender_id, client_message_id),
    KEY idx_room_local (room_id, local_sequence),
    KEY idx_room_display (room_id, created_at, origin_server_id, origin_sequence),
    CONSTRAINT fk_msg_room FOREIGN KEY (room_id) REFERENCES chat_rooms (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS replication_outbox (
    outbox_id        BIGINT       NOT NULL AUTO_INCREMENT,
    message_id       VARCHAR(64)  NOT NULL,
    target_peer_id   VARCHAR(64)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(3)  NOT NULL,
    last_error       VARCHAR(500) NULL,
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (outbox_id),
    UNIQUE KEY uq_outbox_msg_peer (message_id, target_peer_id),
    KEY idx_outbox_due (target_peer_id, status, next_attempt_at, outbox_id),
    CONSTRAINT fk_outbox_msg FOREIGN KEY (message_id) REFERENCES chat_messages (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS replication_state (
    peer_server_id            VARCHAR(64)  NOT NULL,
    last_replicated_origin_seq BIGINT      NOT NULL DEFAULT 0,
    last_success_at           DATETIME(3)  NULL,
    last_sync_status          VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
    last_error                VARCHAR(500) NULL,
    updated_at                DATETIME(3)  NOT NULL,
    PRIMARY KEY (peer_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS user_profiles (
    username       VARCHAR(100) NOT NULL,
    display_name   VARCHAR(100) NOT NULL,
    email          VARCHAR(200) NOT NULL DEFAULT '',
    status         VARCHAR(16)  NOT NULL DEFAULT 'ONLINE',
    created_at     DATETIME(3)  NOT NULL,
    last_seen_at   DATETIME(3)  NOT NULL,
    avatar         MEDIUMTEXT   NULL,
    PRIMARY KEY (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_log (
    audit_id    BIGINT       NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(3)  NOT NULL,
    server_id   VARCHAR(64)  NOT NULL,
    actor       VARCHAR(100) NOT NULL,
    action      VARCHAR(64)  NOT NULL,
    target      VARCHAR(200) NOT NULL,
    result      VARCHAR(16)  NOT NULL,
    details     VARCHAR(1000) NOT NULL DEFAULT '',
    PRIMARY KEY (audit_id),
    KEY idx_audit_time (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

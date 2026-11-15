-- NEXUS Distributed Core-Banking schema v1 (MySQL 8 / MariaDB 10.5+).
-- Applied automatically at server start when database.schema.auto=true (default); every statement is idempotent.
-- Manual use: CREATE DATABASE nexus_banking CHARACTER SET utf8mb4; then run this file against that database
-- (see scripts/init-db.sql). Timestamps are stored as UTC DATETIME(3). Each branch node owns its OWN database.

CREATE TABLE IF NOT EXISTS bank_accounts (
    account_number VARCHAR(20)   NOT NULL,
    owner_name     VARCHAR(100)  NOT NULL,
    balance        DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    branch_id      VARCHAR(32)   NOT NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
    daily_limit    DECIMAL(18,2) NOT NULL DEFAULT 20000000.00,
    pin_hash       VARCHAR(200)  NOT NULL,
    created_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (account_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS origin_sequences (
    origin_server_id VARCHAR(64) NOT NULL,
    last_seq         BIGINT      NOT NULL,
    PRIMARY KEY (origin_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bank_transactions (
    local_sequence    BIGINT        NOT NULL AUTO_INCREMENT,
    transaction_id    VARCHAR(64)   NOT NULL,
    account_number    VARCHAR(20)   NOT NULL,
    transaction_type  ENUM('DEPOSIT','WITHDRAW','TRANSFER','TRANSFER_IN') NOT NULL,
    amount            DECIMAL(18,2) NOT NULL,
    balance_after     DECIMAL(18,2) NOT NULL,
    reference_account VARCHAR(20)   NULL,
    note              VARCHAR(200)  NOT NULL DEFAULT '',
    created_at        DATETIME(3)   NOT NULL,
    origin_server_id  VARCHAR(64)   NOT NULL,
    origin_sequence   BIGINT        NOT NULL,
    client_request_id VARCHAR(64)   NULL,
    PRIMARY KEY (local_sequence),
    UNIQUE KEY uq_transaction_id (transaction_id),
    UNIQUE KEY uq_origin_seq (origin_server_id, origin_sequence),
    UNIQUE KEY uq_client_request (client_request_id),
    KEY idx_account_time (account_number, created_at),
    KEY idx_origin (origin_server_id, origin_sequence),
    CONSTRAINT fk_txn_account FOREIGN KEY (account_number) REFERENCES bank_accounts (account_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS replication_outbox (
    outbox_id        BIGINT       NOT NULL AUTO_INCREMENT,
    transaction_id   VARCHAR(64)  NOT NULL,
    target_peer_id   VARCHAR(64)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count    INT          NOT NULL DEFAULT 0,
    next_attempt_at  DATETIME(3)  NOT NULL,
    last_error       VARCHAR(500) NULL,
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    PRIMARY KEY (outbox_id),
    UNIQUE KEY uq_outbox_txn_peer (transaction_id, target_peer_id),
    KEY idx_outbox_due (target_peer_id, status, next_attempt_at, outbox_id),
    CONSTRAINT fk_outbox_txn FOREIGN KEY (transaction_id) REFERENCES bank_transactions (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS replication_state (
    peer_server_id             VARCHAR(64)  NOT NULL,
    last_replicated_origin_seq BIGINT       NOT NULL DEFAULT 0,
    last_success_at            DATETIME(3)  NULL,
    last_sync_status           VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
    last_error                 VARCHAR(500) NULL,
    updated_at                 DATETIME(3)  NOT NULL,
    PRIMARY KEY (peer_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_log (
    audit_id    BIGINT        NOT NULL AUTO_INCREMENT,
    occurred_at DATETIME(3)   NOT NULL,
    server_id   VARCHAR(64)   NOT NULL,
    actor       VARCHAR(100)  NOT NULL,
    action      VARCHAR(64)   NOT NULL,
    target      VARCHAR(200)  NOT NULL,
    result      VARCHAR(16)   NOT NULL,
    details     VARCHAR(1000) NOT NULL DEFAULT '',
    prev_hash   CHAR(64)      NOT NULL,
    entry_hash  CHAR(64)      NOT NULL,
    PRIMARY KEY (audit_id),
    KEY idx_audit_time (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

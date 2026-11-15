# NEXUS Banking (RMI) - Distributed Core-Banking & ATM Cluster

Đồ án môn Hệ phân tán: hệ thống **ngân hàng phân tán 2 chi nhánh active-active** (Hà Nội - TP.HCM) trên Java 21, Maven multi-module,
Java RMI (TLS/mTLS tùy chọn), MySQL/MariaDB, JavaFX. Tái cấu trúc từ `nexus_chat-RMI` (giữ nguyên lõi phân tán, đổi toàn bộ nghiệp vụ).

| Module | Vai trò |
|---|---|
| `banking-contract` | Giao diện RMI (`BankingService`, `BankingCallback`, `BankingReplicationService`, `BankingAdminService`, `BankingMonitoringService`), DTO, `Money`, socket factory TLS |
| `banking-server` | Node chi nhánh: sổ cái nguyên tử, idempotency, replication, session, audit hash-chain |
| `banking-client` | **NEXUS BANKING** - Internet Banking / ATM cho khách hàng (thẻ, số dư realtime, nạp/rút/chuyển, lịch sử, mạng lưới chi nhánh) |
| `banking-control` | **NEXUS CONTROL** - trung tâm điều hành: mesh topology, phiên, tài khoản (khóa/hạn mức), sổ cái + đối soát, kiểm toán |

## Kiến trúc & bảo đảm

```
 Client(s) ──RMI──►  Node A (Hà Nội)  ◄══ replicate/catch-up (RMI + shared secret) ══►  Node B (TP.HCM) ◄──RMI── Client(s)
                       │  MySQL A (riêng)                                                  │  MySQL B (riêng)
        RMI callback ◄─┘  push số dư / đá phiên                                            └─► RMI callback
```
* **Active-Active multi-master**: cả hai node nhận giao dịch độc lập.
* **Giao dịch nguyên tử**: kiểm tra trạng thái/số dư/hạn mức ngày, trừ tiền (`UPDATE ... WHERE balance >= ?`), ghi bút toán và dòng outbox **trong một transaction**. Chuyển khoản = bút toán kép (`TRANSFER` + `TRANSFER_IN`).
* **Idempotency**: `client_request_id UNIQUE`; gửi lại cùng mã trả giao dịch gốc, không trừ 2 lần. Cùng mã bị gửi tới cả hai chi nhánh khi đứt mạng vẫn chỉ trừ một lần.
* **Transactional Outbox + Catch-up**: bút toán đồng bộ qua `replication_outbox` (retry lùi, dead-letter) và kéo bù theo `origin_sequence`; nhất quán cuối cùng, phát hiện tài khoản âm sau hợp nhất.
* **Single session toàn cụm**: đăng nhập mới đá phiên cũ qua RMI peer eviction + callback.
* **Push realtime**: `BankingCallback` (RMI callback) báo biến động số dư; không bao giờ chặn giao dịch, tự rơi về polling khi callback không tới được.
* **Kiểm toán bất biến**: `audit_log` liên kết hash SHA-256 (+ trigger append-only), Control xác minh được chuỗi.
* **Bảo mật**: PIN băm PBKDF2-HMAC-SHA256 + salt, chống dò PIN (khóa tạm), phiên chỉ thao tác trên tài khoản của chính mình, admin token, secret liên chi nhánh, TLS/mTLS tùy chọn.

## Chạy nhanh
Xem **`KICH_BAN_DEMO_NGAN_HANG_2_LAPTOP.md`** (build, CSDL, chạy 2 node, 3 kịch bản demo + kịch bản bổ sung).

```
mvn clean verify                    # build + test
scripts\init-db.sql                 # tạo 2 CSDL (mỗi chi nhánh một CSDL)
scripts\run-node-a.ps1 -Db ...      # Node A        scripts\run-node-b.ps1 -Db ...   # Node B
scripts\run-control.ps1             # NEXUS CONTROL scripts\run-client.ps1           # NEXUS BANKING
```
Không có MySQL? Chạy node không `-Db` (chế độ RAM demo, **không bền vững**, được ghi rõ trong log/UI).

## Lược đồ CSDL (`banking-server/src/main/resources/db/db-schema.sql`)
`bank_accounts`, `bank_transactions` (sổ cái), `replication_outbox`, `replication_state`, `origin_sequences`, `audit_log`.

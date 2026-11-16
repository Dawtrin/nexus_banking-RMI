# 🏦 NEXUS Banking (RMI) - Distributed Core-Banking & ATM Cluster

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Java RMI](https://img.shields.io/badge/Architecture-Java_RMI-007396?style=for-the-badge&logo=java&logoColor=white)]()
[![MySQL](https://img.shields.io/badge/MySQL-8.0_InnoDB-4479A1?style=for-the-badge&logo=mysql&logoColor=white)](https://www.mysql.com/)
[![JavaFX](https://img.shields.io/badge/GUI-JavaFX_21-2E7D32?style=for-the-badge&logo=javafx&logoColor=white)]()
[![Status](https://img.shields.io/badge/Cluster-Active--Active-success?style=for-the-badge)]()

> 🌟 **Project #3 trong Bộ ba Hệ thống Phân tán NEXUS (The NEXUS Distributed Systems Trilogy):**
> 
> * 💬 **NEXUS Chat (RMI)**
> * 🎬 **NEXUS Cinema (RMI)**
> * 🏦 **NEXUS Banking (RMI)** *(Dự án này)*: Hệ thống Ngân hàng lõi & ATM phân tán 2 chi nhánh đa nguồn (Active-Active Multi-Master), giải quyết trọn vẹn:
>   * ⚡ **Giao dịch phân tán & Chuẩn ACID**: Trừ tiền nguyên tử, bút toán kép (`Double-entry Ledger`).
>   * 🛡️ **Chống chi tiêu trùng (Double Spending & Idempotency)**: Đảm bảo giao dịch chỉ thực thi duy nhất 1 lần khi mạng chập chờn hoặc retry.
>   * 🔄 **Chịu lỗi đứt cáp (CAP Theorem / High Availability)**: Transactional Outbox Pattern + Catch-up Replication tự động kéo bù số dư.
>   * ⛓️ **Chuỗi kiểm toán bất biến (Audit Hash-Chain)**: Xâu chuỗi nhật ký bằng mã băm SHA-256 (tamper-evident) phát hiện ngay lập tức nếu CSDL bị sửa trộm.

---

Đồ án môn Hệ phân tán: hệ thống **ngân hàng phân tán 2 chi nhánh active-active** (Hà Nội - TP.HCM) trên Java 21, Maven multi-module,
Java RMI (TLS/mTLS tùy chọn), MySQL/MariaDB, JavaFX.

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

## Hướng dẫn Chạy & Demo Chi tiết
👉 **Xem file [`HUONG_DAN_CHAY_DEMO.md`](HUONG_DAN_CHAY_DEMO.md)** (Hướng dẫn từng bước chạy trên 1 máy, trên 2 laptop với máy ảo VirtualBox, và 5 kịch bản demo chi tiết).

```powershell
# Chạy nhanh trên 1 máy:
.\scripts\run-node-a.ps1 -Db       # Node A (Hà Nội - Port 1099)
.\scripts\run-node-b.ps1 -Db       # Node B (TP.HCM - Port 2099)
.\scripts\run-control.ps1          # NEXUS CONTROL (Mã token: nexus-admin-secret)
.\scripts\run-client.ps1           # NEXUS BANKING ATM (PIN demo: 123456)
```

## Lược đồ CSDL (`banking-server/src/main/resources/db/db-schema.sql`)
`bank_accounts`, `bank_transactions` (sổ cái), `replication_outbox`, `replication_state`, `origin_sequences`, `audit_log`.

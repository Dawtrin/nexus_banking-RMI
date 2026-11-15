# Kịch bản demo NEXUS BANKING - 2 laptop, 2 chi nhánh

Hệ thống: **2 node server active-active** (Node A = Chi nhánh Hà Nội, Node B = Chi nhánh TP.HCM), mỗi node có **CSDL MySQL riêng**,
đồng bộ **sổ cái 2 chiều** qua Java RMI (Transactional Outbox + Catch-up), ứng dụng khách **NEXUS BANKING** và trung tâm điều hành **NEXUS CONTROL** (JavaFX).

---

## 0. Chuẩn bị (làm trước buổi demo)

| Máy | Chạy | IP ví dụ |
|---|---|---|
| Laptop 1 | MySQL + **Node A** (Hà Nội) + NEXUS CONTROL | `192.168.1.10` |
| Laptop 2 | MySQL + **Node B** (TP.HCM) + NEXUS BANKING (client) | `192.168.1.20` |

Cùng một mạng LAN/Wi-Fi. Mở tường lửa Windows cho **Java** (cổng 1099, 1100 trên Laptop 1; 1099, 1100 trên Laptop 2; client cần nhận kết nối ngược cho callback).

**Bước 1 - Build (cả 2 máy):** `mvn -DskipTests package`

**Bước 2 - Tạo CSDL (mỗi máy):** `mysql -u root -p < scripts/init-db.sql`
(Laptop 1 dùng `nexus_banking_a`, Laptop 2 dùng `nexus_banking_b`.)

**Bước 3 - Chạy node:**
```powershell
# Laptop 1 (Ha Noi)
$env:NEXUS_REPLICATION_SECRET = 'nexus-cluster-shared-secret-16chars'   # PHAI GIONG nhau o 2 may
$env:NEXUS_ADMIN_TOKEN = 'nexus-admin-secret'
$env:DB_USERNAME = 'root'; $env:DB_PASSWORD = ''
.\scripts\run-node-a.ps1 -Db -AdvertisedHost 192.168.1.10 -PeerHost 192.168.1.20

# Laptop 2 (TP.HCM)
$env:NEXUS_REPLICATION_SECRET = 'nexus-cluster-shared-secret-16chars'
$env:NEXUS_ADMIN_TOKEN = 'nexus-admin-secret'
$env:DB_USERNAME = 'root'; $env:DB_PASSWORD = ''
.\scripts\run-node-b.ps1 -Db -AdvertisedHost 192.168.1.20 -PeerHost 192.168.1.10
```
Log đúng khi thấy: `persistence MYSQL_DURABLE` và `[REPLICATION] Peer 'server-b' is reachable`.
(Muốn mã hóa: tạo chứng chỉ bằng `scripts\generate-dev-certs.ps1` rồi thêm `-Ssl` ở cả node và client.)

**Bước 4 - NEXUS CONTROL (Laptop 1):** `.\scripts\run-control.ps1` → tên điều hành viên `risk-officer`, token `nexus-admin-secret`,
Chi nhánh A `192.168.1.10:1099`, Chi nhánh B `192.168.1.20:1099` → *Kết nối*. Tổng quan phải hiện 2 chi nhánh **Online**.

**Bước 5 - Ứng dụng khách (Laptop 2):** `.\scripts\run-client.ps1` → *Kết nối nâng cao*: A `192.168.1.10:1099`, B `192.168.1.20:1099`.

### Tài khoản demo (PIN **123456**)
| Số tài khoản | Chủ TK | Số dư | Hạn mức/ngày | Ghi chú |
|---|---|---|---|---|
| 1001000001 | Nguyễn Văn An | 50.000.000 | 20.000.000 | dùng nhiều nhất |
| 1001000002 | Trần Thị Bình | 25.000.000 | 20.000.000 | |
| 2002000001 | Lê Hoàng Cường | 80.000.000 | 30.000.000 | |
| 2002000002 | Phạm Minh Dũng | 12.500.000 | 10.000.000 | |
| 3003000001 | Võ Thanh Em | 5.000.000 | 5.000.000 | demo vượt số dư/hạn mức |
| 9009000001 | Công ty NEXUS | 1.000.000.000 | 500.000.000 | |
| 4004000001 | Hoàng Văn Khóa | 1.000.000 | 5.000.000 | **đang bị khóa** |

> Mẹo trình bày: trên màn hình client, chip "Tài khoản demo" tự điền số tài khoản + PIN.

---

## Kịch bản 1 - Rút tiền KHÔNG bị trừ 2 lần (chống Double Spending)

**Ý nghĩa kỹ thuật:** mỗi lệnh có `client_request_id` (UUID sinh một lần khi khách bấm xác nhận). Server kiểm tra trùng mã trong cùng giao dịch
CSDL (cột `client_request_id` là `UNIQUE`). Mạng chập chờn → client gửi lại **cùng mã** → server trả giao dịch gốc, **không trừ tiền lần 2**.

**Thao tác:**
1. Client: đăng nhập `1001000001`. Ghi lại số dư (50.000.000 ₫).
2. Mô phỏng mạng chập chờn: ngay khi bấm *Xác nhận rút tiền 2.000.000*, **rút cáp mạng / tắt Wi-Fi của Laptop 2 khoảng 5 giây** rồi bật lại
   (hoặc chạy test tự động bên dưới để chứng minh nhanh).
3. Client hiển thị "đang xử lý", tự **kết nối lại (có thể sang chi nhánh còn lại)** và gửi lại với cùng mã yêu cầu.
4. Biên lai hiện **"đã được ghi nhận trước đó - KHÔNG thực hiện lần 2"** hoặc thành công bình thường.

**Kết quả cần chỉ cho giảng viên:**
- Số dư giảm **đúng 1 lần** (48.000.000 ₫), lịch sử chỉ có **1** dòng "Rút tiền".
- NEXUS CONTROL → **Kiểm toán**: có dòng `IDEMPOTENT_REPLAY` (nếu server nhận lại yêu cầu trùng) - bằng chứng hệ thống đã chặn trừ lần 2.
- Nếu mất mạng quá lâu, client báo **"chưa xác định được kết quả"** + nút **Thử lại an toàn**: bấm bao nhiêu lần cũng chỉ trừ 1 lần.

**Chứng minh bằng test tự động** (không cần dựng mạng):
```
mvn -pl banking-server test -Dtest=BankingServiceTest#retryWithSameRequestIdNeverDebitsTwice+concurrentRetriesOfOneRequestDebitExactlyOnce
mvn -pl banking-client test -Dtest=BankingClientCoreTest#aLostResponseIsRetriedWithTheSameRequestIdAndDebitsOnlyOnce
```
Test thứ hai mô phỏng đúng ca khó nhất: **server đã trừ tiền rồi nhưng gói trả lời bị mất**.

**Biến thể - rút quá số dư/hạn mức:** tài khoản `3003000001` rút 6.000.000 → "Số dư không đủ"; rút 4.000.000 rồi 2.000.000 → lần 2 bị từ chối, **số dư không âm**
(kiểm tra `balance >= amount` là một câu `UPDATE ... WHERE balance >= ?` nguyên tử + khóa dòng `SELECT ... FOR UPDATE`).

---

## Kịch bản 2 - Đứt mạng giữa 2 chi nhánh, giao dịch tiếp, nối lại tự bù sổ cái (Catch-up sync)

**Ý nghĩa kỹ thuật:** giao dịch và dòng `replication_outbox` được ghi **trong cùng một transaction CSDL** (Transactional Outbox). Khi đường liên chi nhánh đứt,
bút toán xếp hàng PENDING; khi nối lại, node tự gửi bù và **kéo bù** (pull catch-up theo `origin_sequence`) → số dư 2 bên đạt **nhất quán cuối cùng**.

**Thao tác:**
1. NEXUS CONTROL → **Sổ cái**: panel đối soát báo xanh **"SỐ DƯ MỌI TÀI KHOẢN KHỚP NHAU"** (hai chi nhánh cùng *digest*).
2. **Cắt liên kết giữa 2 máy**: tắt Wi-Fi Laptop 2 (hoặc `Disable-NetAdapter`), hoặc đơn giản dừng Node B (`Ctrl+C`).
   NEXUS CONTROL → **Sơ đồ mạng lưới**: liên kết chuyển **đỏ**, chi nhánh B "không khả dụng".
3. Khi đứt mạng, **vẫn giao dịch ở Chi nhánh A** (client đăng nhập `1001000001`, ưu tiên "Hà Nội"): nạp 1.000.000, rút 500.000, chuyển 300.000 cho `2002000001`.
   - Lịch sử hiện nhãn **"◔ Chờ đồng bộ"**; Tổng quan Control: "Còn N bút toán chờ đồng bộ".
4. (Tùy chọn, rất thuyết phục) Cũng giao dịch ở B bằng tài khoản khác (ví dụ `2002000001`) trong lúc đứt.
5. **Nối lại mạng** (bật Wi-Fi / chạy lại Node B). Không cần thao tác nào khác.

**Kết quả cần chỉ:**
- Sau vài giây: nhãn chuyển **"✓ Đã đồng bộ"**, Control → **Sổ cái** hiện lại **KHỚP NHAU**; cột "Có ở" của mọi bút toán là **A + B**.
- Chạy `.\scripts\show-db.ps1 -Database nexus_banking_b`: bảng `bank_transactions` của B **có đủ** bút toán phát sinh khi đứt mạng; `replication_outbox` của A rỗng (đã DELIVERED).
- Nếu Node B **khởi động lại**: dữ liệu vẫn còn (MySQL), `origin_sequence` tiếp tục không trùng.

**Giải thích khi bị hỏi "hai chi nhánh cùng tiêu một số dư lúc đứt mạng thì sao?":** đây là bài toán *partition tolerance*. Hệ thống ưu tiên **sẵn sàng** (cả hai vẫn phục vụ) và chấp nhận
**nhất quán cuối cùng**. Nếu hai chi nhánh cùng rút tiền từ một tài khoản vượt tổng số dư, sau khi đồng bộ hệ thống **phát hiện tài khoản âm**, ghi `OVERDRAFT_DETECTED` vào sổ kiểm toán
và Control báo đỏ để đối soát thủ công (giống "stand-in processing" của ATM thực tế). Test: `ReplicationTest#spendingTheSameMoneyOnBothBranchesIsDetectedAsAnOverdraft`.
Cùng một yêu cầu bị gửi tới cả hai chi nhánh trong lúc đứt mạng chỉ trừ **một lần** (`DUPLICATE_SUPPRESSED`): `ReplicationTest#theSameClientRequestExecutedOnBothBranches...`.

---

## Kịch bản 3 - Đăng nhập cùng lúc trên 2 máy bị đá phiên (Single Session)

**Thao tác:**
1. Laptop 2: client đăng nhập `1001000001` ưu tiên **TP.HCM** (phiên ở Node B).
2. Laptop 1: mở thêm client (hoặc chạy lại `run-client.ps1`), đăng nhập **cùng** `1001000001` ưu tiên **Hà Nội** (phiên ở Node A).

**Kết quả:**
- Client ở Laptop 2 **lập tức** hiện hộp thoại **"Phiên đăng nhập đã bị đóng - Tài khoản của bạn vừa được đăng nhập trên một thiết bị khác (IP, chi nhánh)"** (đẩy qua RMI callback, không phải chờ polling) và quay về màn hình đăng nhập.
- NEXUS CONTROL → **Phiên làm việc**: chỉ còn **1** phiên cho tài khoản đó (cột "Đẩy số dư": RMI callback).
- Cơ chế: node nhận đăng nhập mới **gọi RMI sang node còn lại** (`evictAccountSession`) để đóng phiên cũ (peer eviction). Cùng một máy kết nối lại (rớt mạng) **không** bị coi là thiết bị khác.

---

## Kịch bản bổ sung (nếu còn thời gian)

**A. Cập nhật số dư thời gian thực (RMI callback).** Client đăng nhập `2002000001`. Trên máy khác, `1001000001` chuyển cho `2002000001` 2.500.000 ở chi nhánh bất kỳ:
số dư trên màn hình client nhận **nhảy số**, hiện toast "+2.500.000 ₫", dòng lịch sử mang nhãn "Từ chi nhánh khác" nếu đi qua chi nhánh còn lại.

**B. Can thiệp khẩn cấp (Control → Tài khoản).** Chọn `1001000001` → **Khóa + ngắt kết nối** (nhập lý do): client đang mở bị đóng phiên ngay, đăng nhập lại báo "Tài khoản đang bị khóa", mọi giao dịch bị từ chối. *Mở khóa*, *Đổi hạn mức*, *Mở tài khoản mới* đều áp dụng cho **cả hai chi nhánh**.

**C. Sổ kiểm toán bất biến.** Control → **Kiểm toán** → *Xác minh toàn vẹn (hash-chain)*: cả hai chi nhánh báo **TOÀN VẸN**. Muốn minh họa phát hiện giả mạo:
```sql
-- (Trigger append-only sẽ chặn UPDATE; tạm gỡ để minh họa kẻ có quyền DBA sửa lịch sử)
DROP TRIGGER trg_audit_no_update;
UPDATE audit_log SET details='khong co gi' WHERE audit_id = 2;
```
Bấm lại *Xác minh* → **BỊ PHÁ VỠ tại bút toán kiểm toán #2**.

**D. Giao dịch lớn.** Rút/chuyển ≥ 10.000.000 ₫ ghi `LARGE_WITHDRAW`/`LARGE_TRANSFER` vào sổ kiểm toán (ai, khi nào, bao nhiêu).

**E. Failover khách hàng.** Client đang dùng Node B, dừng Node B: trong ~2-3 giây client tự chuyển sang Node A (chip "Đang kết nối lại..." → "Chi nhánh Hà Nội").

---

## Chạy bộ test (chứng cứ tự động)

```
mvn clean verify                      # toàn bộ test (test MySQL tự bỏ qua nếu chưa cấu hình)
# Có MySQL thật:
$env:NEXUS_TEST_DB_HOST='127.0.0.1'; $env:NEXUS_TEST_DB_USER='root'; $env:NEXUS_TEST_DB_PASSWORD=''
mvn -pl banking-server test           # chạy cả JdbcBankingStoreDbTest + TwoNodeDbTest (2 CSDL riêng, đứt mạng, restart)
```

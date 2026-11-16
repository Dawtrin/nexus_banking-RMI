# 🏦 HƯỚNG DẪN CHẠY & DEMO HỆ THỐNG NEXUS BANKING (RMI)
**Hệ thống:** Core-Banking & ATM Phân Tán 2 Chi Nhánh Active-Active  
**Công nghệ:** Java 21 · Java RMI · MySQL/MariaDB · JavaFX · Transactional Outbox · Hash-Chain Audit

---

## 📌 NGUYÊN TẮC BẤT DI BẤT DỊCH
> **Luôn khởi động SERVER trước (chờ kết nối CSDL và RMI sẵn sàng), sau đó mới bật CLIENT hoặc CONTROL (Giao diện đồ họa).**

---

# PHẦN 1: HƯỚNG DẪN CHẠY TRÊN 1 MÁY (LOCAL DEMO)
*(Dùng để tự luyện tập kịch bản demo hoặc chạy thử nghiệm nhanh một mình)*

### 1. Chuẩn bị Cơ sở dữ liệu:
* Bật **Laragon** (hoặc XAMPP) $\rightarrow$ Bấm **Start All** (MySQL chạy cổng `3306`).
* *(CSDL `nexus_banking_a` và `nexus_banking_b` đã được tạo sẵn).*

---

### 2. Các tab lệnh chạy (Mở trong IntelliJ Terminal hoặc PowerShell riêng):

Mở thư mục dự án:
```powershell
cd C:\Users\Lenovo\IdeaProjects\nexus_banking-RMI
```

#### 🔹 Tab 1: Khởi động Server Chi nhánh Hà Nội (Node A - Port 1099)
```powershell
.\scripts\run-node-a.ps1 -Db
```
* **Dấu hiệu thành công:** Thấy thông báo:  
  `Branch node 'server-a' (Chi nhánh Hà Nội) started: registry 1099, persistence MYSQL_DURABLE`

#### 🔹 Tab 2: Khởi động Server Chi nhánh TP.HCM (Node B - Port 2099)
```powershell
.\scripts\run-node-b.ps1 -Db
```
* **Dấu hiệu thành công:** Cả 2 tab Server sẽ báo:  
  `[REPLICATION] Peer 'server-a' is reachable` $\rightarrow$ 2 server đã kết nối đồng bộ 2 chiều qua RMI.

#### 🔹 Tab 3: Mở App Khách 1 / Cây ATM Hà Nội
```powershell
.\scripts\run-client.ps1
```
* Bấm vào chip **"An (HN)"** hoặc nhập:
  * **Số tài khoản:** `1001000001`
  * **Mã PIN:** `123456`
* Bấm **Đăng nhập** (Số dư ban đầu: `50.000.000 ₫`).

#### 🔹 Tab 4: Mở App Khách 2 / Cây ATM TP.HCM
Mở thêm 1 tab PowerShell mới:
```powershell
.\scripts\run-client.ps1
```
* Bấm vào chip **"Bình (HN)"** hoặc **"Cường (HCM)"**:
  * **Số tài khoản:** `1001000002` (Trần Thị Bình)
  * **Mã PIN:** `123456`
* Bấm **Đăng nhập** (Số dư ban đầu: `25.000.000 ₫`).

#### 🔹 Tab 5: Mở Trung tâm Điều hành Ngân hàng (NEXUS CONTROL)
```powershell
.\scripts\run-control.ps1
```
* **Tên điều hành:** `admin` (hoặc `risk-officer`)
* **Mã token:** `nexus-admin-secret`
* Bấm **Kết nối** $\rightarrow$ Quản trị cụm, xem sơ đồ mạng lưới 2 chi nhánh, đối soát số dư và xác minh chuỗi Hash-Chain.

---

# PHẦN 2: HƯỚNG DẪN CHẠY TRÊN 2 MÁY LAPTOP
### (MÔ HÌNH: MÁY THẬT CHẠY SERVER + CONTROL, MÁY ẢO VIRTUALBOX CHẠY CLIENT ATM)

```text
       [ ĐIỆN THOẠI PHÁT WI-FI HOTSPOT DÙNG CHUNG ]
              /                              \
             /                                \
    [ LAPTOP 1 ]                             [ LAPTOP 2 ]
  ┌───────────────────────────┐            ┌───────────────────────────┐
  │ 💻 MÁY THẬT 1:            │            │ 💻 MÁY THẬT 2:            │
  │ • Server A (Chi nhánh HN) │◄──────────►│ • Server B (Chi nhánh HCM)│
  │ • NEXUS CONTROL (Admin UI)│  RMI Link  │                           │
  ├───────────────────────────┤            ├───────────────────────────┤
  │ 🖥️ MÁY ẢO VIRTUALBOX 1:   │            │ 🖥️ MÁY ẢO VIRTUALBOX 2:   │
  │ • Client ATM Khách An     │            │ • Client ATM Khách Bình   │
  └───────────────────────────┘            └───────────────────────────┘
```

---

### ⚙️ BƯỚC 1: Cấu hình Mạng cho Máy ảo VirtualBox (BẮT BUỘC)
> ⚠️ **LƯU Ý CỐT LÕI:** VirtualBox mặc định để mạng là `NAT`. Nếu để `NAT` thì Server không thể gọi ngược (RMI Callback) vào máy ảo để cập nhật số dư tức thì. Do đó **bắt buộc đổi sang Bridged Adapter**:

1. Trong VirtualBox: Chọn máy ảo $\rightarrow$ Bấm **Settings** (Cài đặt) $\rightarrow$ Chọn mục **Network** (Mạng).
2. Tại **Attached to:** Đổi từ `NAT` sang **`Bridged Adapter`** (Cầu nối).
3. Tại **Name:** Chọn đúng tên Card mạng Wi-Fi mà Laptop đang dùng.
4. Bật máy ảo lên như bình thường.

> ❓ **HỎI: CÓ CẦN BIẾT HAY ĐIỀN IP CỦA MÁY ẢO KHÔNG?**  
> 👉 **TRẢ LỜI: HOÀN TOÀN KHÔNG CẦN!**  
> * Client bên trong máy ảo (`CallbackExporter.java`) đã được lập trình sẵn cơ chế tự động dò tìm IP của máy ảo và gửi cho Server.
> * Bạn **chỉ cần biết IP của 2 Máy thật** để điền vào ô kết nối!

---

### 🌐 BƯỚC 2: Kết nối Mạng & Lấy IP của 2 Laptop Thật
1. Dùng điện thoại **Bật điểm phát sóng di động (Personal Hotspot)** và cho cả Laptop 1 & Laptop 2 kết nối chung vào Wi-Fi này (tránh bị tường lửa công ty/trường học chặn cổng).
2. Mở PowerShell trên từng máy thật, gõ lệnh:
   ```powershell
   ipconfig
   ```
   Tìm dòng `IPv4 Address`. Giả sử ta có:
   * **Máy thật 1 (Laptop 1):** `192.168.43.10`
   * **Máy thật 2 (Laptop 2):** `192.168.43.20`

---

### 🗄️ BƯỚC 3: Chuẩn bị CSDL MySQL trên 2 Laptop
* **Laptop 1:** Bật Laragon, đảm bảo có CSDL `nexus_banking_a`.
* **Laptop 2:** Bật Laragon, đảm bảo có CSDL `nexus_banking_b`.  
  *(Nếu máy 2 chưa có DB, mở MySQL gõ: `CREATE DATABASE IF NOT EXISTS nexus_banking_b CHARACTER SET utf8mb4;`)*

---

### 🚀 BƯỚC 4: Lệnh Chạy Cụ Thể Trên Từng Máy

#### 💻 TẠI LAPTOP 1:
1. **Trên Máy thật 1: Khởi động Server Chi nhánh Hà Nội (Node A):**
   ```powershell
   cd C:\Users\Lenovo\IdeaProjects\nexus_banking-RMI
   .\scripts\run-node-a.ps1 -Db -AdvertisedHost 192.168.43.10 -PeerHost 192.168.43.20
   ```
   *(Thay đúng IP máy thật 1 và máy thật 2 của bạn).*

2. **Trên Máy thật 1: Mở NEXUS CONTROL (để giám sát):**
   ```powershell
   .\scripts\run-control.ps1
   ```
   * Node A: `192.168.43.10:1099` | Node B: `192.168.43.20:2099`
   * Token: `nexus-admin-secret` $\rightarrow$ Bấm **Kết nối**.

3. **Trong Máy ảo VirtualBox 1: Mở Client Khách hàng:**
   ```powershell
   .\scripts\run-client.ps1
   ```
   * Bấm vào mục **Kết nối nâng cao**:
     * **Node A:** `192.168.43.10:1099` (IP của Máy thật 1)
     * **Node B:** `192.168.43.20:2099` (IP của Máy thật 2)
     * Chọn Chi nhánh ưu tiên: **Hà Nội**.
   * Đăng nhập: Số tài khoản `1001000001` (Nguyễn Văn An), PIN `123456`.

---

#### 💻 TẠI LAPTOP 2:
1. **Trên Máy thật 2: Khởi động Server Chi nhánh TP.HCM (Node B):**
   ```powershell
   cd C:\Users\Lenovo\IdeaProjects\nexus_banking-RMI
   .\scripts\run-node-b.ps1 -Db -AdvertisedHost 192.168.43.20 -PeerHost 192.168.43.10
   ```
   *(Server B sẽ tự động bắt tay với Server A qua mạng Wi-Fi).*

2. **Trong Máy ảo VirtualBox 2: Mở Client Khách hàng:**
   ```powershell
   .\scripts\run-client.ps1
   ```
   * Bấm vào mục **Kết nối nâng cao**:
     * **Node A:** `192.168.43.10:1099`
     * **Node B:** `192.168.43.20:2099`
     * Chọn Chi nhánh ưu tiên: **TP.HCM**.
   * Đăng nhập: Số tài khoản `1001000002` (Trần Thị Bình) hoặc `2002000001` (Lê Hoàng Cường), PIN `123456`.

---

# PHẦN 3: KỊCH BẢN DEMO THUYẾT TRÌNH CHI TIẾT

### 1️⃣ Kịch bản 1: Chuyển tiền liên chi nhánh & Nhảy số Realtime
1. Đặt 2 màn hình máy ảo cạnh nhau:
   * Máy ảo 1 (HN): Đang mở tài khoản **Nguyễn Văn An** (Số dư: `50.000.000 ₫`).
   * Máy ảo 2 (HCM): Đang mở tài khoản **Trần Thị Bình** (Số dư: `25.000.000 ₫`).
2. Tại Máy ảo 1: Chọn tab **Chuyển khoản**:
   * Nhập số tài khoản nhận: `1001000002` (Hệ thống tự nhận diện: *Trần Thị Bình*).
   * Số tiền: `5.000.000` ₫ $\rightarrow$ Nhập PIN `123456` $\rightarrow$ Bấm **Xác nhận**.
3. **Quan sát kết quả:**
   * Máy ảo 1: Bị trừ 5 triệu, số dư còn `45.000.000 ₫`.
   * **Máy ảo 2:** Nhờ cơ chế RMI Callback, số dư trên màn hình máy ảo 2 **lập tức nhảy lên `30.000.000 ₫`** ngay thời gian thực kèm thông báo biến động số dư.

---

### 2️⃣ Kịch bản 2: Đứt cáp giữa 2 chi nhánh $\rightarrow$ Giao dịch tiếp $\rightarrow$ Tự động Catch-up & Đối soát
1. Mở cửa sổ **NEXUS CONTROL** (trên Máy thật 1) $\rightarrow$ Tab **Sổ cái**:
   * Hệ thống báo xanh: `SỐ DƯ MỌI TÀI KHOẢN KHỚP NHAU` (Digest 2 bên đồng nhất).
2. **Cắt liên kết:** Tắt Wi-Fi Laptop 2 (hoặc bấm `Ctrl + C` tắt Server B trên Máy thật 2).
   * Trên NEXUS CONTROL, tab **Sơ đồ mạng lưới** báo liên kết chuyển sang màu đỏ (Node B Offline).
3. **Giao dịch độc lập khi đứt mạng:**
   * Tại Máy ảo 1: Khách An vẫn nạp/rút tiền bình thường (Hệ thống đảm bảo tính High Availability).
   * Bút toán được tự động lưu vào bảng `replication_outbox` với trạng thái `PENDING` ("Chờ đồng bộ").
4. **Nối lại mạng:** Bật lại Wi-Fi hoặc chạy lại Server B trên Máy thật 2.
5. **Quan sát kết quả:**
   * Server B tự động kích hoạt **Pull Catch-up** kéo toàn bộ bút toán theo `origin_sequence` từ Server A về.
   * Mở NEXUS CONTROL > **Sổ cái**: Tự động chuyển lại trạng thái xanh **KHỚP NHAU**. CSDL tại Laptop 2 đã được cập nhật đồng bộ hoàn toàn!

---

### 3️⃣ Kịch bản 3: Chống chi tiêu trùng (Double Spending / Idempotency)
* Khi khách hàng bấm xác nhận rút tiền, client tự động sinh một mã duy nhất `client_request_id` (UUID).
* Nếu gặp sự cố mạng hoặc client gửi lại yêu cầu: Server kiểm tra ràng buộc `UNIQUE (client_request_id)` trong CSDL $\rightarrow$ trả về giao dịch cũ mà **tuyệt đối không trừ tiền lần 2**.
* Trên NEXUS CONTROL, tab **Kiểm toán** sẽ ghi nhận sự kiện `IDEMPOTENT_REPLAY` để chứng minh hệ thống đã chặn thành công việc trừ tiền lặp lại.

---

### 4️⃣ Kịch bản 4: Xác minh tính toàn vẹn chuỗi kiểm toán (Audit Hash-Chain)
1. Trên **NEXUS CONTROL**, vào tab **Kiểm toán** (Audit Log).
2. Bấm nút **"Xác minh chuỗi hash"**:
   * Hệ thống tính toán lại toàn bộ chuỗi băm SHA-256 từ `prev_hash` sang `entry_hash` của từng dòng log.
   * Thông báo: *Toàn bộ chuỗi kiểm toán hợp lệ, không có dữ liệu nào bị can thiệp trái phép.*

---

### 5️⃣ Kịch bản 5: Đá phiên đăng nhập xuyên chi nhánh (Single Session Eviction)
1. Đang đăng nhập tài khoản An ở Máy ảo 1.
2. Sang Máy ảo 2, cũng đăng nhập đúng tài khoản An (`1001000001`).
3. Màn hình Máy ảo 1 sẽ **lập tức bị ngắt kết nối** và hiện thông báo: *"Phiên của bạn đã bị ngắt do phát hiện đăng nhập từ nơi khác"*.

---

# PHẦN 4: BẢNG DANH SÁCH TÀI KHOẢN DEMO
*(Mã PIN chung cho tất cả tài khoản: **`123456`** | Token Admin: **`nexus-admin-secret`**)*

| Số tài khoản | Chủ tài khoản | Chi nhánh gốc | Số dư ban đầu | Hạn mức ngày | Mục đích demo |
| :---: | :--- | :---: | :---: | :---: | :--- |
| **`1001000001`** | **Nguyễn Văn An** | Hà Nội | `50.000.000 ₫` | `20.000.000 ₫` | Tài khoản chính test chuyển/rút |
| **`1001000002`** | **Trần Thị Bình** | Hà Nội | `25.000.000 ₫` | `20.000.000 ₫` | Nhận chuyển khoản realtime |
| **`2002000001`** | **Lê Hoàng Cường** | TP.HCM | `80.000.000 ₫` | `30.000.000 ₫` | Tài khoản thuộc chi nhánh miền Nam |
| **`2002000002`** | **Phạm Minh Dũng** | TP.HCM | `12.500.000 ₫` | `10.000.000 ₫` | Khách hàng cá nhân |
| **`3003000001`** | **Võ Thanh Em** | Chung | `5.000.000 ₫` | `5.000.000 ₫` | Test rút tiền **vượt số dư / quá hạn mức** |
| **`4004000001`** | **Hoàng Văn Khóa** | Chung | `1.000.000 ₫` | `5.000.000 ₫` | Test tài khoản bị **LOCKED (Khóa thẻ)** |
| **`9009000001`** | **Công ty NEXUS** | Trụ sở | `1.000.000.000 ₫` | `500.000.000 ₫` | Tài khoản doanh nghiệp số dư lớn |

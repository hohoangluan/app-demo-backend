# Your Eyes — ứng dụng điện thoại

App Android nhận lệnh từ App Communication Server qua FCM, thực thi trên điện thoại
(gọi điện, SOS, nhạc Spotify, Google Maps, Grab, vị trí, nghe/từ chối cuộc gọi) và báo kết quả về server.
Luồng chi tiết: [`../docs/architecture.md`](../docs/architecture.md).

## Cấu trúc (`app/src/main/java/com/youreyes/app`)

```text
command/   FcmPushReceiver -> CommandExecutionService -> CommandDispatcher -> CommandStore (SQLite)
           ReportFlusher gửi lại report chưa tới server
actions/   ActionRegistry + handler theo nhóm: Call, Music, Navigation, Ride, Status
network/   DeviceApiClient (API server), GlassesServerClient, GlassesBleProvisioner, DTO
core/      AppConfig (SharedPreferences), Timestamps
launch/    mở màn hình từ nền bằng full-screen notification
media/     Spotify App Remote          service/  listener để điều khiển media session
telephony/ theo dõi cuộc gọi đến       ui/       màn hình Compose
```

## Cấu hình build

Đặt trong `local.properties` (không commit) hoặc truyền `-Pyoureyes.<key>=...`:

| Khóa | Mặc định | Ý nghĩa |
|---|---|---|
| `youreyes.serverUrl` | `https://app.visioncare-host.uk` | Server mặc định trên form đăng ký |
| `youreyes.deviceApiToken` | trống | Token Device API thô (server lưu hash) |
| `youreyes.glassesServerUrl` | `https://api.visioncare-host.uk` | Server kính (Wi-Fi, firmware) |
| `youreyes.glassesServerToken` | trống | Token server kính |
| `youreyes.spotifyClientId` | client id demo | Spotify App Remote (không phải secret) |

Cần `app/google-services.json` của dự án Firebase. Bản debug cho phép HTTP thường
(ví dụ `http://10.0.2.2:8000` từ emulator); bản release chỉ HTTPS.

## Build và kiểm thử

```powershell
Set-Location mobile
.\gradlew.bat test lint assembleDebug
```

`scripts/bootstrap-android.ps1` cài JDK 17 + Android SDK cục bộ nếu máy chưa có.
Unit test JVM nằm ở `app/src/test`: pipeline lệnh (`command/CommandPipelineTest`: chống trùng,
gửi lại report, giữ `details`, bỏ sau 8 lần), mapping action, và UI state.

## Dùng trên máy

1. Cấp quyền khi app hỏi (gọi điện, danh bạ, SMS, vị trí, thông báo, hiển thị trên ứng dụng khác,
   bỏ tối ưu pin). Bật "Truy cập thông báo" cho Your Eyes để `music_play/stop` điều khiển Spotify.
2. Hồ sơ → chạm dòng "phiên bản" 7 lần → nhập server/token → "Lưu Cấu Hình & Kết Nối Server".
3. Hồ sơ → Kính Your Eyes: ghép `device_id` của kính.
4. An toàn: đặt người thân nhận cuộc gọi + SMS khẩn cấp (số hoặc tên trong danh bạ).

App thật cần cài sẵn: Spotify (đã đăng nhập), Google Maps, Grab. Thiếu app nào thì action
tương ứng báo lỗi rõ ràng (`MUSIC_ACCOUNT_NOT_CONNECTED`, `NAVIGATION_PROVIDER_ERROR`,
`RIDE_PROVIDER_ERROR`) thay vì báo thành công giả.

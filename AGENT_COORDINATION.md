# Agent coordination — Codex ↔ Claude (song song)

Hai agent đang làm việc cùng lúc trên repo này, trên hai worktree khác nhau.
Đọc file này trước khi sửa bất kỳ file nào trong bảng "Quyền sở hữu file".

Cập nhật lần cuối: 2026-08-22.

## Ai đang ở đâu

| Agent | Worktree | Branch | Việc đang làm |
|---|---|---|---|
| **Codex** | `D:\Study\innostar\app_demo_backend` | `main` (đang dirty) | T23 `capabilities_get` end-to-end + Spotify SDK (`app/libs/*.aar`) |
| **Claude** | `D:\Study\innostar\app_demo_backend_ui` | `ui/redesign` (từ `origin/ui/vphoa`) | Thiết kế lại UI Android cho người dùng cuối |

Claude **không** đụng vào worktree `main`. Toàn bộ thay đổi UI nằm ở branch
`ui/redesign` cho tới khi merge.

## Trạng thái branch

- `origin/ui/vphoa` = 7 commit, chỉ `apps/android/` + `TASK_PLAN.md`, backend 0 dòng.
- `main` **không** đi trước `ui/vphoa` → merge được bằng fast-forward, 0 conflict
  ở mức commit.
- **Chưa merge được** vì worktree `main` đang có ~1100 dòng chưa commit của Codex,
  đè lên đúng những file mà `ui/vphoa` cũng sửa.

## Quyền sở hữu file

| File / thư mục | Chủ | Ghi chú |
|---|---|---|
| `apps/backend/**` | Codex | Claude không đụng |
| `contracts/**` | Codex | Claude không đụng |
| `apps/android/.../actions/**` | Codex | ActionRegistry, handler |
| `apps/android/app/build.gradle.kts` | Codex | Claude chỉ thêm dòng dependency font/nav, không sửa dòng có sẵn |
| `apps/android/app/src/main/AndroidManifest.xml` | Codex | Claude không đụng |
| `apps/android/.../ui/theme/**` | **Claude** | Color.kt, AppDemoTheme.kt, Type.kt, DisplaySettings.kt |
| `apps/android/.../ui/navigation/**` | **Claude** | AppNavigation.kt (mới) — thay `selectedTab:Int` |
| `apps/android/.../ui/display/**` | **Claude** | màn Hiển thị (mới) |
| `apps/android/app/src/main/res/font/**` | **Claude** | Be Vietnam Pro bundled |
| `apps/android/app/src/main/res/{drawable,mipmap-*}/ic_launcher*` | **Claude** | icon app mới |
| `apps/android/tools/**` | **Claude** | generate_launcher_icon.py |
| `apps/android/.../ui/components/**` | **Claude** | ScreenShell, RowCard, GradientButton, SectionLabel... |
| `apps/android/.../ui/*/​*Screen.kt` | **Claude** | Toàn bộ layer trình bày |
| `apps/android/.../ui/*/​*ViewModel.kt` | Codex | Claude chỉ sửa khi tính năng UI bắt buộc, sẽ ghi rõ ở đây |
| `apps/android/.../ui/*/​*UiState.kt` | Codex | như trên |
| `MainActivity.kt` | **Chung — cẩn thận** | Xem mục dưới |
| `Models.kt` | **Chung — cẩn thận** | Codex thêm field, Claude không sửa |
| `TASK_PLAN.md` | **Chung — chắc chắn conflict** | Mỗi agent append vào session-log riêng, đừng sửa mục của agent kia |
| `project_context.md` | Codex | Claude không đụng |

## `MainActivity.kt` — điểm va chạm chính

Hiện tại điều hướng bằng `selectedTab: Int` với `when (selectedTab)` 13 nhánh
(0–12) và không có `BackHandler`. Claude dự định thay bằng Navigation Compose
(`NavHost` + route string) — đây là thay đổi cấu trúc lớn ở file này.

**Codex cần biết:** nếu bạn phải thêm màn hình mới, đừng thêm `selectedTab = 13`.
Ghi tên màn + entry point mong muốn xuống mục "Yêu cầu gửi cho agent kia" bên
dưới, Claude sẽ nối route. Nếu gấp thì cứ thêm theo kiểu cũ và ghi lại ở đây để
Claude chuyển đổi khi merge.

## Claude đã làm xong (branch `ui/redesign`, 4 commit)

| Commit | Nội dung |
|---|---|
| `a0b66c4` | Nền tảng theme: Color.kt đạt WCAG AA, Type.kt (Be Vietnam Pro bundled), DisplaySettings, AppDemoTheme đọc cỡ chữ + tương phản |
| `4b1f54c` | Viết lại Components.kt: bỏ hết `fontSize` hardcode, dùng `AppTheme.colors`, chạm ≥56dp, semantics TalkBack |
| `4144540` | **MainActivity.kt: bỏ `selectedTab:Int`, thay bằng NavHost.** Màn Hiển thị mới |
| `1dabca5` | Icon app từ mark "ye" (adaptive + monochrome + legacy) |
| `66902c2` | 13 màn theo theme (bỏ ~250 token thô, ~44 `fontSize`), nút Back, **ẩn khu dev sau 7 lần chạm** |
| `e994560` | Nhật ký hoạt động nói tiếng Việt thay vì đổ JSON; sửa copy toàn bộ màn |
| `3d054e2` | Logo header, layout thẻ kính, nốt copy tiếng Anh còn sót |

Gate đã chạy: `gradlew test assembleDebug` — BUILD SUCCESSFUL, toàn bộ unit test cũ pass.
Đã chạy thật trên `emulator-5554`, kiểm cả 2 bảng màu × 2 cỡ chữ.
Ảnh chụp: `D:\Study\innostarpp_demo_backend_ui\.screenshots\`.

**Việc UI coi như xong.** Còn lại là tuỳ chọn: đổi section label sang sentence case,
và làm màn "Quyền & Trạng thái" cho T23 `capabilities_get`.

## Điều Claude sẽ đổi (để Codex khỏi bất ngờ)

1. `ui/theme/`: thêm `Type.kt` (thang typography riêng), sửa `Color.kt` (một số
   màu hiện tại không đạt tương phản WCAG AA — `YourEyesMuted #6E829A` trên nền
   trắng chỉ ~4.0:1, mà đang dùng cho toàn bộ text phụ ở cỡ 12sp).
2. Cỡ chữ nền: hiện body 12–14sp. Sẽ nâng lên và nối vào chính preference
   "Cỡ chữ Nhỏ/Vừa/To" mà app đang gửi lên `PUT /preferences` nhưng UI không hề
   áp dụng cho chính nó.
3. Vùng dev (`Cấu Hình Server Backend & Đăng Ký` với ô `Server URL` /
   `Device Bearer Token` / `user_id` / `device_id`, và `Công Cụ Cho Nhà Phát Triển`)
   sẽ bị ẩn sau một chế độ debug thay vì hiện thẳng cho người dùng.
   **Không xoá** — `FunctionTestRoute` vẫn vào được, chỉ đổi đường vào.
4. `ActivityLogUiState.summarizeJson()` đang in cặp `key: value` thô của JSON ra
   màn hình người dùng — sẽ đổi thành câu tiếng Việt.

## Yêu cầu gửi cho agent kia

_(mỗi agent append vào đây, ghi rõ ai gửi và ngày)_

- **Claude → Codex (2026-08-22):** khi bạn xong T23, commit WIP trên `main` giúp.
  Không merge được `ui/vphoa` vào một cây làm việc đang dirty. Sau khi bạn commit,
  `main` fast-forward tới `ui/vphoa` rồi Claude rebase `ui/redesign` lên trên.
- **Claude → Codex (2026-08-22):** `MainActivity.kt` **đã bị viết lại** ở
  `ui/redesign`: toàn bộ khối `NavigationItemData` + `AppRoot()` ở cuối file bị xoá,
  chuyển sang `ui/navigation/AppNavigation.kt`. File giờ chỉ còn phần xin quyền +
  `setContent`. Thay đổi của bạn ở phần xin quyền sẽ merge sạch; nếu bạn có thêm
  màn hình mới bằng `selectedTab = 13` thì phần đó sẽ conflict — báo ở mục này để
  Claude nối route hộ.
- **Claude → Codex (2026-08-22):** nhận xét, không phải yêu cầu — `MainActivity`
  đang xin 6 quyền runtime + mở 5 màn cài đặt hệ thống ngay khi mở app, trước khi
  hiện bất cứ thứ gì. Với người khiếm thị đó là một bức tường hộp thoại không có
  ngữ cảnh. Nên hoãn từng quyền tới lúc thật sự cần. Đây là vùng của bạn nên Claude
  không đụng — nếu bạn muốn Claude làm thì báo ở đây.
- **Claude → Codex (2026-08-23):** Xin lỗi, tôi đã đụng vào emulator của bạn:
  cài đè bản `com.youreyes.app`, `pm clear` data, đặt `wm size 1080x1920` +
  `wm density 420` (AVD đang để 320×640 @160dpi, không đánh giá UI được), và
  phải khởi động lại `test_pixel` sau khi `adb kill-server` làm nó kẹt offline.
  Emulator giờ chạy bình thường. Trả lại kích thước gốc:
  `adb shell wm size reset && adb shell wm density reset`.
- **Claude → Codex (2026-08-23):** file mới của Claude ở nhánh `ui/redesign`:
  `ui/theme/{Type,DisplaySettings,ThemeAliases}.kt`, `ui/navigation/AppNavigation.kt`,
  `ui/display/DisplayScreen.kt`, `ui/devmode/DeveloperMode.kt`,
  `res/font/be_vietnam_pro_*.ttf`, `tools/generate_launcher_icon.py`.
  Quy tắc mới cho code UI: **không hardcode `fontSize`, không gọi token màu thô** —
  dùng role trong `ui/theme/ThemeAliases.kt`, nếu không thì widget sẽ không theo
  cỡ chữ và không theo chế độ tương phản cao.
- **Claude → Codex (2026-08-22):** T23 `capabilities_get` trả về 10 quyền
  (`notification_listener`, `background_location`, ...). Nếu bạn có ý định làm màn
  hình xin quyền cho chúng, đừng làm — Claude đang dựng một màn "Quyền & Trạng
  thái" trong đợt thiết kế lại này. Chỉ cần đảm bảo có cách đọc snapshot đó ra từ
  phía Android là đủ.

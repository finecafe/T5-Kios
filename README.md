# YouTubeKiosk

Ứng dụng APK (WebView toàn màn hình, giống Kiosk Browser) mở kênh YouTube của bạn. Mọi link thuộc YouTube (video, playlist, shorts, community, about...) đều mở ngay trong app; link ngoài YouTube mở bằng trình duyệt của máy.

Chạy được trên Android 5.0+ (minSdk 21) và có icon cho Android TV box.

## Đổi link kênh / tên app
Sửa file `app/src/main/res/values/strings.xml`:
- `channel_url`: link kênh YouTube của bạn
- `app_name`: tên hiển thị

## Đổi logo / icon
1. Thay file `icon_source.png` (PNG vuông, nền trong suốt, nên 512x512) bằng ảnh mới.
2. Chạy `python3 make_icons.py` (cần Pillow: `pip install pillow`). Script tự tạo icon mọi kích thước và banner cho Android TV.
3. Commit/push lên GitHub để build lại.

## Build APK
Push lên GitHub, vào tab **Actions** > **Build APK** > tải artifact `YouTubeKiosk-APK` (file `app-debug.apk`).

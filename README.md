# SONIX GStreamer Lab (ARM64)

تطبيق Android تجريبي خفيف (`arm64-v8a` فقط) لاختبار استخراج واستبدال مسارات الصوت عبر **GStreamer 1.28.9** من اشتراك IPTV واحد (Xtream Codes أو رابط M3U واحد).

## المعمارية

- **Video Pipeline A (`uridecodebin` + `glimagesink`):** تشغيل فيديو القناة الأساسية بتفضيل مفككات MediaCodec العتادية (`amcviddec*`) والتحقق من أول إطار فيديو فعلي قبل إعلان `PLAYING`. يتم إيقاف مسار صوت A وإسقاط حزمه قبل أي مفكك صوت عبر `valve` + إزالة فرع فك الترميز عند تفعيل الصوت الخارجي B.
- **Audio Pipeline B (`input-selector` + `openslessink`):** استخراج الصوت فقط (تجاهل مسارات الفيديو والترجمة عبر `autoplug-select` دون إنشاء أي Video Decoder أو MediaCodec لقناة الصوت)، مع دعم إدارة مصدر نشط ومصدر مرشح للتبديل الآمن B → C دون قطع الفيديو أو الصوت الحالي قبل جاهزية المرشح.
- **تحكم ومزامنة:** دوال مستقلة للفيديو والصوت (`nativeSetVideoPaused` / `nativeSetAudioPaused`)، وتتبع حقيقي لـ `PTS`/`DTS`/`running-time`/`clock` وتعويض الانجراف دون إخفاء غياب البيانات الزمنية، وفحص `max_connections` و`active_cons` دون طلبات PROBE مكررة.
- **توافق التشغيل على Android ARM64:** بناء `arm64-v8a` فقط عبر NDK `27.2.12479018` و`APP_STL := c++_shared` مع تضمين `libc++_shared.so` و`libgstreamer_android.so` و`libsonix_gst.so` وشهادات `ca-certificates.crt` داخل APK.

## البناء والاختبار

يتطلب البناء توفر **GStreamer Android ARM64 SDK (`1.28.9`)** في `GSTREAMER_ROOT_ANDROID` (افتراضياً `/opt/gstreamer-android`) و**NDK `27.2.12479018`** و`pkg-config`.

- **اختبارات الوحدة:**
  ```bash
  gradle :app:testDebugUnitTest
  ```
- **بناء Debug APK (ARM64 فقط):**
  ```bash
  gradle :app:assembleDebug
  ```
  المخرج: `app/build/outputs/apk/debug/app-debug.apk`

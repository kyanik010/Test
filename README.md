# SONIX GStreamer Lab

تطبيق Android تجريبي خفيف لاختبار استخراج واستبدال مسارات الصوت عبر **GStreamer 1.28.9** من اشتراك IPTV واحد (Xtream Codes أو رابط M3U واحد).

## المعمارية

- **Video Pipeline A (`playbin` / `glimagesink` / `ANativeWindow`):** تشغيل فيديو القناة الأساسية مع إمكانية كتم صوتها (`mute`) دون إعادة تشغيل الفيديو.
- **Audio Pipeline B (`souphttpsrc` -> `decodebin` -> `audioconvert` -> `audioresample` -> `openslessink` / `aaudiosink`):** استخراج وتشغيل الصوت من القناة المختارة بشكل مستقل مع ضبط التأخير (ms) والمزامنة.
- **واجهة Jetpack Compose (شاشة واحدة):** حقول اشتراك IPTV، قائمة القنوات، عارض الفيديو، اختيار مصدر الصوت البديل، التحكم بالتأخير، مؤشرات الحالة، وسجل تشخيصي مختصر.

## البنية الأساسية

- `app/src/main/jni/sonix_gstreamer.c` & `Android.mk`: طبقة JNI ومسارات GStreamer المزدوجة.
- `app/prebuiltJniLibs/{arm64-v8a,armeabi-v7a}/`: ثنائيات `libgstreamer_android.so` و`libsonix_gst.so` المبنية مسبقاً للعمل في بيئات CI مثل GitHub Actions.
- `app/src/main/java/com/example/gst/`: جسر GStreamer وإدارة حالتي الفيديو والصوت.
- `app/src/main/java/com/example/iptv/`: عميل Xtream Codes ومحلل قوائم M3U.
- `app/src/main/java/com/example/ui/`: واجهة الشاشة الواحدة بـ Jetpack Compose.
- `.github/workflows/android-apk.yml`: تشغيل اختبارات الوحدة وبناء Debug APK على GitHub Actions (مع دعم Release APK عند توفر أسرار التوقيع).

## البناء والاختبار

- **اختبارات الوحدة:**
  ```bash
  gradle :app:testDebugUnitTest
  ```
- **بناء Debug APK:**
  ```bash
  gradle :app:assembleDebug
  ```
  المخرج: `app/build/outputs/apk/debug/app-debug.apk`

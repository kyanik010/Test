#include <string.h>
#include <stdint.h>
#include <pthread.h>
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <gst/gst.h>
#include <gst/video/videooverlay.h>
#include <gst/video/video.h>

#define LOG_TAG "SONIX_GST"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define GST_PLAY_FLAG_VIDEO        (1 << 0)
#define GST_PLAY_FLAG_AUDIO        (1 << 1)
#define GST_PLAY_FLAG_NATIVE_VIDEO (1 << 6)

typedef struct _SonixGstData {
    jobject app_obj;
    GMainContext *context;
    GMainLoop *main_loop;
    pthread_t loop_thread;
    pthread_mutex_t lock;

    GstClock *shared_clock;

    // Pipeline A: Primary Video + Original Audio (muteable without interrupting video)
    GstElement *video_pipeline;
    GstElement *video_sink;
    ANativeWindow *native_window;
    char video_decoder_name[128];

    // Pipeline B: Independent Audio-Only Extraction Pipeline
    GstElement *audio_pipeline;
    GstElement *audio_src;
    GstElement *audio_queue;
    GstElement *audio_convert;
    GstElement *audio_resample;
    GstElement *audio_volume;
    GstElement *audio_sink;
    char audio_decoder_name[128];
    gboolean first_audio_reported;
    gboolean mute_original_audio;
    gint64 audio_delay_ms;

    jmethodID on_video_state_id;
    jmethodID on_video_format_id;
    jmethodID on_audio_state_id;
    jmethodID on_first_audio_buffer_id;
    jmethodID on_log_id;
} SonixGstData;

static JavaVM *g_jvm = NULL;
static SonixGstData *g_data = NULL;

static JNIEnv *attach_jni(gboolean *needs_detach) {
    JNIEnv *env = NULL;
    *needs_detach = FALSE;
    if (!g_jvm) return NULL;
    jint res = (*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) == JNI_OK) {
            *needs_detach = TRUE;
        } else {
            return NULL;
        }
    }
    return env;
}

static void detach_jni(gboolean needs_detach) {
    if (needs_detach && g_jvm) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
}

static void emit_log(SonixGstData *d, const char *level, const char *msg) {
    if (!d || !d->app_obj || !d->on_log_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env) return;
    jstring jlvl = (*env)->NewStringUTF(env, level ? level : "GST");
    jstring jmsg = (*env)->NewStringUTF(env, msg ? msg : "");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_log_id, jlvl, jmsg);
    (*env)->DeleteLocalRef(env, jlvl);
    (*env)->DeleteLocalRef(env, jmsg);
    detach_jni(det);
}

static void emit_video_state(SonixGstData *d, int code, const char *info) {
    if (!d || !d->app_obj || !d->on_video_state_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env) return;
    jstring jinfo = (*env)->NewStringUTF(env, info ? info : "");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_video_state_id, (jint)code, jinfo);
    (*env)->DeleteLocalRef(env, jinfo);
    detach_jni(det);
}

static void emit_video_format(SonixGstData *d, int w, int h, const char *dec) {
    if (!d || !d->app_obj || !d->on_video_format_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env) return;
    jstring jdec = (*env)->NewStringUTF(env, dec ? dec : "unknown");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_video_format_id, (jint)w, (jint)h, jdec);
    (*env)->DeleteLocalRef(env, jdec);
    detach_jni(det);
}

static void emit_audio_state(SonixGstData *d, int code, const char *info) {
    if (!d || !d->app_obj || !d->on_audio_state_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env) return;
    jstring jinfo = (*env)->NewStringUTF(env, info ? info : "");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_audio_state_id, (jint)code, jinfo);
    (*env)->DeleteLocalRef(env, jinfo);
    detach_jni(det);
}

static void emit_first_audio_buffer(SonixGstData *d, const char *dec) {
    if (!d || !d->app_obj || !d->on_first_audio_buffer_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env) return;
    jstring jdec = (*env)->NewStringUTF(env, dec ? dec : "unknown");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_first_audio_buffer_id, jdec);
    (*env)->DeleteLocalRef(env, jdec);
    detach_jni(det);
}

static void configure_http_source(GstElement *source) {
    if (!source) return;
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(source), "user-agent")) {
        g_object_set(source, "user-agent", "SONIX-TV/1.0", NULL);
    }
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(source), "timeout")) {
        g_object_set(source, "timeout", (guint)15, NULL);
    }
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(source), "retries")) {
        g_object_set(source, "retries", (gint)3, NULL);
    }
}

static void on_source_setup(GstElement *parent, GstElement *source, gpointer user_data) {
    configure_http_source(source);
}

static void on_video_deep_element_added(GstBin *bin, GstBin *sub_bin, GstElement *element, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    GstElementFactory *factory = gst_element_get_factory(element);
    if (!factory) return;
    const gchar *klass = gst_element_factory_get_metadata(factory, GST_ELEMENT_METADATA_KLASS);
    const gchar *fname = gst_plugin_feature_get_name(GST_PLUGIN_FEATURE(factory));
    if (klass && strstr(klass, "Decoder") && strstr(klass, "Video") && fname) {
        snprintf(d->video_decoder_name, sizeof(d->video_decoder_name), "%s", fname);
        LOGI("Video A decoder selected: %s", fname);
    }
}

static void on_video_caps_notify(GObject *pad, GParamSpec *pspec, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    GstCaps *caps = gst_pad_get_current_caps(GST_PAD(pad));
    if (!caps) return;
    GstVideoInfo vinfo;
    if (gst_video_info_from_caps(&vinfo, caps)) {
        emit_video_format(d, vinfo.width, vinfo.height,
                          d->video_decoder_name[0] ? d->video_decoder_name : "MediaCodec/GL");
    }
    gst_caps_unref(caps);
}

static gboolean on_video_bus_message(GstBus *bus, GstMessage *msg, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    switch (GST_MESSAGE_TYPE(msg)) {
        case GST_MESSAGE_ERROR: {
            GError *err = NULL;
            gchar *dbg = NULL;
            gst_message_parse_error(msg, &err, &dbg);
            emit_video_state(d, 4, err ? err->message : "Video pipeline error");
            if (err) g_error_free(err);
            g_free(dbg);
            break;
        }
        case GST_MESSAGE_BUFFERING: {
            gint percent = 0;
            gst_message_parse_buffering(msg, &percent);
            if (percent < 100) {
                emit_video_state(d, 1, "");
            } else {
                emit_video_state(d, 2, d->video_decoder_name);
            }
            break;
        }
        case GST_MESSAGE_STATE_CHANGED: {
            if (GST_MESSAGE_SRC(msg) == GST_OBJECT(d->video_pipeline)) {
                GstState old_s, new_s, pending;
                gst_message_parse_state_changed(msg, &old_s, &new_s, &pending);
                if (new_s == GST_STATE_PLAYING) {
                    emit_video_state(d, 2, d->video_decoder_name[0] ? d->video_decoder_name : "Active");
                } else if (new_s == GST_STATE_PAUSED && old_s == GST_STATE_PLAYING) {
                    emit_video_state(d, 3, "");
                }
            }
            break;
        }
        case GST_MESSAGE_EOS:
            emit_log(d, "VID", "وصل تدفق الفيديو A إلى نهاية البث (EOS)");
            break;
        default:
            break;
    }
    return TRUE;
}

// ---- Pipeline B: Audio-only extraction ----

// Prevent uridecodebin/decodebin in Pipeline B from instantiating ANY video or subtitle decoder!
static gint on_audio_autoplug_select(GstElement *bin, GstPad *pad, GstCaps *caps,
                                     GstElementFactory *factory, gpointer user_data) {
    if (caps && gst_caps_get_size(caps) > 0) {
        GstStructure *st = gst_caps_get_structure(caps, 0);
        const gchar *name = gst_structure_get_name(st);
        if (name && (g_str_has_prefix(name, "video/") ||
                     g_str_has_prefix(name, "text/") ||
                     g_str_has_prefix(name, "subpicture/"))) {
            // Expose raw un-decoded pad so it can be discarded in fakesink without CPU/HW decoding
            return 1; // GST_AUTOPLUG_SELECT_EXPOSE
        }
    }
    return 0; // GST_AUTOPLUG_SELECT_TRY
}

static void on_audio_deep_element_added(GstBin *bin, GstBin *sub_bin, GstElement *element, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    GstElementFactory *factory = gst_element_get_factory(element);
    if (!factory) return;
    const gchar *klass = gst_element_factory_get_metadata(factory, GST_ELEMENT_METADATA_KLASS);
    const gchar *fname = gst_plugin_feature_get_name(GST_PLUGIN_FEATURE(factory));
    if (klass && strstr(klass, "Decoder") && strstr(klass, "Audio") && fname) {
        snprintf(d->audio_decoder_name, sizeof(d->audio_decoder_name), "%s", fname);
        LOGI("Audio B decoder selected: %s", fname);
    }
}

static void on_audio_pad_added(GstElement *src, GstPad *new_pad, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    GstCaps *caps = gst_pad_get_current_caps(new_pad);
    if (!caps) caps = gst_pad_query_caps(new_pad, NULL);
    if (!caps || gst_caps_is_empty(caps)) {
        if (caps) gst_caps_unref(caps);
        return;
    }

    const gchar *media_type = gst_structure_get_name(gst_caps_get_structure(caps, 0));
    if (media_type && g_str_has_prefix(media_type, "audio/")) {
        GstPad *sink_pad = gst_element_get_static_pad(d->audio_queue, "sink");
        if (sink_pad && !gst_pad_is_linked(sink_pad)) {
            if (gst_pad_link(new_pad, sink_pad) == GST_PAD_LINK_OK) {
                LOGI("Audio B pad linked: %s", media_type);
            }
        }
        if (sink_pad) gst_object_unref(sink_pad);
    } else if (media_type) {
        // Discard compressed video / metadata / subtitle pads immediately without decoding
        GstElement *drop_sink = gst_element_factory_make("fakesink", NULL);
        if (drop_sink && d->audio_pipeline) {
            g_object_set(drop_sink, "sync", FALSE, "async", FALSE, NULL);
            gst_bin_add(GST_BIN(d->audio_pipeline), drop_sink);
            gst_element_sync_state_with_parent(drop_sink);
            GstPad *fake_pad = gst_element_get_static_pad(drop_sink, "sink");
            if (fake_pad && gst_pad_link(new_pad, fake_pad) == GST_PAD_LINK_OK) {
                LOGI("Audio B non-audio pad (%s) routed to fakesink (no video decode)", media_type);
            }
            if (fake_pad) gst_object_unref(fake_pad);
        }
    }
    gst_caps_unref(caps);
}

static GstPadProbeReturn on_audio_sink_buffer_probe(GstPad *pad, GstPadProbeInfo *info, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    if (!d->first_audio_reported) {
        d->first_audio_reported = TRUE;
        emit_first_audio_buffer(d, d->audio_decoder_name[0] ? d->audio_decoder_name : "GStreamer Audio");
    }
    return GST_PAD_PROBE_OK;
}

static gboolean on_audio_bus_message(GstBus *bus, GstMessage *msg, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    switch (GST_MESSAGE_TYPE(msg)) {
        case GST_MESSAGE_ERROR: {
            GError *err = NULL;
            gchar *dbg = NULL;
            gst_message_parse_error(msg, &err, &dbg);
            emit_audio_state(d, 3, err ? err->message : "Audio pipeline error");
            if (err) g_error_free(err);
            g_free(dbg);
            break;
        }
        case GST_MESSAGE_BUFFERING: {
            gint percent = 0;
            gst_message_parse_buffering(msg, &percent);
            if (percent < 100) emit_audio_state(d, 1, "");
            break;
        }
        case GST_MESSAGE_EOS:
            emit_audio_state(d, 3, "انتهى تدفق مصدر الصوت الخارجي (EOS)");
            break;
        default:
            break;
    }
    return TRUE;
}

static void *gst_loop_worker(void *arg) {
    SonixGstData *d = (SonixGstData *)arg;
    g_main_context_push_thread_default(d->context);
    d->main_loop = g_main_loop_new(d->context, FALSE);
    g_main_loop_run(d->main_loop);
    g_main_loop_unref(d->main_loop);
    d->main_loop = NULL;
    g_main_context_pop_thread_default(d->context);
    return NULL;
}

static void boost_mediacodec_hardware_decoders(SonixGstData *d) {
    GstRegistry *reg = gst_registry_get();
    GList *factories = gst_registry_get_feature_list(reg, GST_TYPE_ELEMENT_FACTORY);
    int hw_count = 0;
    for (GList *l = factories; l != NULL; l = l->next) {
        GstPluginFeature *f = GST_PLUGIN_FEATURE(l->data);
        const gchar *name = gst_plugin_feature_get_name(f);
        if (name && g_str_has_prefix(name, "amcviddec")) {
            gst_plugin_feature_set_rank(f, GST_RANK_PRIMARY + 20);
            hw_count++;
        }
    }
    gst_plugin_feature_list_free(factories);
    char msg[128];
    snprintf(msg, sizeof(msg), "تم تفضيل مفككات MediaCodec العتادية (عدد المكتشف: %d)", hw_count);
    emit_log(d, "HW", msg);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeInit(JNIEnv *env, jobject thiz) {
    if (!g_data) {
        g_data = (SonixGstData *)calloc(1, sizeof(SonixGstData));
        pthread_mutex_init(&g_data->lock, NULL);
        g_data->app_obj = (*env)->NewGlobalRef(env, thiz);

        jclass cls = (*env)->GetObjectClass(env, thiz);
        g_data->on_video_state_id = (*env)->GetMethodID(env, cls, "onNativeVideoState", "(ILjava/lang/String;)V");
        g_data->on_video_format_id = (*env)->GetMethodID(env, cls, "onNativeVideoFormat", "(IILjava/lang/String;)V");
        g_data->on_audio_state_id = (*env)->GetMethodID(env, cls, "onNativeAudioState", "(ILjava/lang/String;)V");
        g_data->on_first_audio_buffer_id = (*env)->GetMethodID(env, cls, "onNativeFirstExternalAudioBuffer", "(Ljava/lang/String;)V");
        g_data->on_log_id = (*env)->GetMethodID(env, cls, "onNativeLog", "(Ljava/lang/String;Ljava/lang/String;)V");

        g_data->context = g_main_context_new();
        g_data->shared_clock = gst_system_clock_obtain();
        pthread_create(&g_data->loop_thread, NULL, gst_loop_worker, g_data);
        boost_mediacodec_hardware_decoders(g_data);
    }

    gchar *ver = gst_version_string();
    jstring jver = (*env)->NewStringUTF(env, ver ? ver : "GStreamer 1.x");
    g_free(ver);
    return jver;
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeSetSurface(JNIEnv *env, jobject thiz, jobject surface) {
    if (!g_data) return;
    pthread_mutex_lock(&g_data->lock);
    if (g_data->native_window) {
        ANativeWindow_release(g_data->native_window);
        g_data->native_window = NULL;
    }
    if (surface) {
        g_data->native_window = ANativeWindow_fromSurface(env, surface);
    }
    if (g_data->video_sink && GST_IS_VIDEO_OVERLAY(g_data->video_sink)) {
        gst_video_overlay_set_window_handle(
            GST_VIDEO_OVERLAY(g_data->video_sink),
            (guintptr)g_data->native_window
        );
    }
    pthread_mutex_unlock(&g_data->lock);
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativePlayVideo(JNIEnv *env, jobject thiz, jstring jurl) {
    if (!g_data || !jurl) return;
    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);

    pthread_mutex_lock(&g_data->lock);
    if (g_data->video_pipeline) {
        gst_element_set_state(g_data->video_pipeline, GST_STATE_NULL);
        gst_object_unref(g_data->video_pipeline);
        g_data->video_pipeline = NULL;
        g_data->video_sink = NULL;
    }
    g_data->video_decoder_name[0] = '\0';

    GstElement *playbin = gst_element_factory_make("playbin", "video_a_playbin");
    GstElement *vsink = gst_element_factory_make("glimagesink", "video_a_glsink");
    GstElement *asink = gst_element_factory_make("openslessink", "video_a_asink");

    if (playbin && vsink && asink) {
        g_object_set(playbin,
                     "uri", url,
                     "video-sink", vsink,
                     "audio-sink", asink,
                     "mute", g_data->mute_original_audio,
                     "flags", (gint)(GST_PLAY_FLAG_VIDEO | GST_PLAY_FLAG_AUDIO | GST_PLAY_FLAG_NATIVE_VIDEO),
                     NULL);
        gst_pipeline_use_clock(GST_PIPELINE(playbin), g_data->shared_clock);

        g_signal_connect(playbin, "source-setup", G_CALLBACK(on_source_setup), g_data);
        g_signal_connect(playbin, "deep-element-added", G_CALLBACK(on_video_deep_element_added), g_data);

        GstPad *vpad = gst_element_get_static_pad(vsink, "sink");
        if (vpad) {
            g_signal_connect(vpad, "notify::caps", G_CALLBACK(on_video_caps_notify), g_data);
            gst_object_unref(vpad);
        }

        if (g_data->native_window && GST_IS_VIDEO_OVERLAY(vsink)) {
            gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(vsink), (guintptr)g_data->native_window);
        }

        GstBus *bus = gst_element_get_bus(playbin);
        GSource *src = gst_bus_create_watch(bus);
        g_source_set_callback(src, (GSourceFunc)on_video_bus_message, g_data, NULL);
        g_source_attach(src, g_data->context);
        g_source_unref(src);
        gst_object_unref(bus);

        g_data->video_pipeline = playbin;
        g_data->video_sink = vsink;
        gst_element_set_state(playbin, GST_STATE_PLAYING);
    } else {
        emit_video_state(g_data, 4, "فشل إنشاء عناصر GStreamer للفيديو");
        if (playbin) gst_object_unref(playbin);
        if (vsink) gst_object_unref(vsink);
        if (asink) gst_object_unref(asink);
    }
    pthread_mutex_unlock(&g_data->lock);
    (*env)->ReleaseStringUTFChars(env, jurl, url);
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeSetVideoPaused(JNIEnv *env, jobject thiz, jboolean paused) {
    if (!g_data) return;
    pthread_mutex_lock(&g_data->lock);
    if (g_data->video_pipeline) {
        gst_element_set_state(g_data->video_pipeline, paused ? GST_STATE_PAUSED : GST_STATE_PLAYING);
    }
    if (g_data->audio_pipeline) {
        gst_element_set_state(g_data->audio_pipeline, paused ? GST_STATE_PAUSED : GST_STATE_PLAYING);
    }
    pthread_mutex_unlock(&g_data->lock);
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeMuteOriginalAudio(JNIEnv *env, jobject thiz, jboolean muted) {
    if (!g_data) return;
    pthread_mutex_lock(&g_data->lock);
    g_data->mute_original_audio = (gboolean)muted;
    if (g_data->video_pipeline) {
        g_object_set(g_data->video_pipeline, "mute", g_data->mute_original_audio, NULL);
    }
    pthread_mutex_unlock(&g_data->lock);
}

static void stop_external_audio_locked(SonixGstData *d) {
    if (d->audio_pipeline) {
        gst_element_set_state(d->audio_pipeline, GST_STATE_NULL);
        gst_object_unref(d->audio_pipeline);
        d->audio_pipeline = NULL;
        d->audio_src = NULL;
        d->audio_queue = NULL;
        d->audio_convert = NULL;
        d->audio_resample = NULL;
        d->audio_volume = NULL;
        d->audio_sink = NULL;
    }
    d->first_audio_reported = FALSE;
    d->audio_decoder_name[0] = '\0';
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeStartExternalAudio(JNIEnv *env, jobject thiz, jstring jurl, jlong delay_ms) {
    if (!g_data || !jurl) return;
    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);

    pthread_mutex_lock(&g_data->lock);
    stop_external_audio_locked(g_data);
    g_data->audio_delay_ms = (gint64)delay_ms;

    GstElement *pipe = gst_pipeline_new("external_audio_b_pipeline");
    GstElement *src = gst_element_factory_make("uridecodebin", "aud_b_src");
    GstElement *queue = gst_element_factory_make("queue", "aud_b_queue");
    GstElement *conv = gst_element_factory_make("audioconvert", "aud_b_conv");
    GstElement *resamp = gst_element_factory_make("audioresample", "aud_b_resamp");
    GstElement *vol = gst_element_factory_make("volume", "aud_b_vol");
    GstElement *sink = gst_element_factory_make("openslessink", "aud_b_sink");

    if (pipe && src && queue && conv && resamp && vol && sink) {
        g_object_set(src, "uri", url, NULL);
        g_object_set(sink, "sync", TRUE, "ts-offset", (gint64)(delay_ms * GST_MSECOND), NULL);

        gst_bin_add_many(GST_BIN(pipe), src, queue, conv, resamp, vol, sink, NULL);
        gst_element_link_many(queue, conv, resamp, vol, sink, NULL);
        gst_pipeline_use_clock(GST_PIPELINE(pipe), g_data->shared_clock);

        g_signal_connect(src, "source-setup", G_CALLBACK(on_source_setup), g_data);
        g_signal_connect(src, "autoplug-select", G_CALLBACK(on_audio_autoplug_select), g_data);
        g_signal_connect(src, "deep-element-added", G_CALLBACK(on_audio_deep_element_added), g_data);
        g_signal_connect(src, "pad-added", G_CALLBACK(on_audio_pad_added), g_data);

        GstPad *sink_pad = gst_element_get_static_pad(sink, "sink");
        if (sink_pad) {
            gst_pad_add_probe(sink_pad, GST_PAD_PROBE_TYPE_BUFFER, on_audio_sink_buffer_probe, g_data, NULL);
            gst_object_unref(sink_pad);
        }

        GstBus *bus = gst_element_get_bus(pipe);
        GSource *w = gst_bus_create_watch(bus);
        g_source_set_callback(w, (GSourceFunc)on_audio_bus_message, g_data, NULL);
        g_source_attach(w, g_data->context);
        g_source_unref(w);
        gst_object_unref(bus);

        g_data->audio_pipeline = pipe;
        g_data->audio_src = src;
        g_data->audio_queue = queue;
        g_data->audio_convert = conv;
        g_data->audio_resample = resamp;
        g_data->audio_volume = vol;
        g_data->audio_sink = sink;

        gst_element_set_state(pipe, GST_STATE_PLAYING);
    } else {
        emit_audio_state(g_data, 3, "فشل إنشاء عناصر GStreamer للصوت B");
        if (pipe) gst_object_unref(pipe);
    }
    pthread_mutex_unlock(&g_data->lock);
    (*env)->ReleaseStringUTFChars(env, jurl, url);
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeStopExternalAudio(JNIEnv *env, jobject thiz) {
    if (!g_data) return;
    pthread_mutex_lock(&g_data->lock);
    stop_external_audio_locked(g_data);
    pthread_mutex_unlock(&g_data->lock);
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeSetAudioDelayMs(JNIEnv *env, jobject thiz, jlong delay_ms) {
    if (!g_data) return;
    pthread_mutex_lock(&g_data->lock);
    g_data->audio_delay_ms = (gint64)delay_ms;
    if (g_data->audio_sink) {
        g_object_set(g_data->audio_sink, "ts-offset", (gint64)(delay_ms * GST_MSECOND), NULL);
    }
    pthread_mutex_unlock(&g_data->lock);
}

JNIEXPORT jlongArray JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeQuerySyncSnapshot(JNIEnv *env, jobject thiz) {
    jlong values[3] = {0, 0, 0};
    if (g_data) {
        pthread_mutex_lock(&g_data->lock);
        gint64 vpos = 0, apos = 0;
        if (g_data->video_pipeline &&
            gst_element_query_position(g_data->video_pipeline, GST_FORMAT_TIME, &vpos)) {
            values[0] = vpos / GST_MSECOND;
        }
        if (g_data->audio_pipeline &&
            gst_element_query_position(g_data->audio_pipeline, GST_FORMAT_TIME, &apos)) {
            values[1] = apos / GST_MSECOND;
        }
        values[2] = g_data->audio_delay_ms;
        pthread_mutex_unlock(&g_data->lock);
    }
    jlongArray arr = (*env)->NewLongArray(env, 3);
    if (arr) {
        (*env)->SetLongArrayRegion(env, arr, 0, 3, values);
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeShutdown(JNIEnv *env, jobject thiz) {
    if (!g_data) return;
    pthread_mutex_lock(&g_data->lock);
    stop_external_audio_locked(g_data);
    if (g_data->video_pipeline) {
        gst_element_set_state(g_data->video_pipeline, GST_STATE_NULL);
        gst_object_unref(g_data->video_pipeline);
        g_data->video_pipeline = NULL;
    }
    if (g_data->native_window) {
        ANativeWindow_release(g_data->native_window);
        g_data->native_window = NULL;
    }
    if (g_data->main_loop) {
        g_main_loop_quit(g_data->main_loop);
    }
    pthread_mutex_unlock(&g_data->lock);
}

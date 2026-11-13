#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
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

struct _SonixGstData;

typedef struct _AudioSourceSlot {
    struct _SonixGstData *owner;
    gint64 slot_id;
    gboolean is_candidate;
    gboolean first_buffer_seen;
    gboolean failed;
    gint video_decoders_detected;
    gint video_pads_dropped;
    char url[2048];
    char audio_decoder_name[128];
    char container_kind[64];

    GstElement *bin;          /* GstBin holding uridecodebin -> queue -> conv -> resamp -> capsfilter */
    GstElement *src;          /* uridecodebin */
    GstElement *queue;
    GstElement *conv;
    GstElement *resample;
    GstElement *capsfilter;
    GstPad *ghost_src;
    GstPad *selector_pad;     /* input-selector sink_%u pad */
    GstSegment slot_segment;
    gboolean has_segment;
} AudioSourceSlot;

typedef struct _SonixSyncState {
    pthread_mutex_t lock;
    GstSegment video_segment;
    gboolean has_video_segment;
    gint64 video_first_pts_ms;
    gint64 video_last_pts_ms;
    gint64 video_last_dts_ms;
    gint64 video_last_rt_ms;
    gint64 video_arrival_clock_ms;
    gboolean video_has_pts;
    gboolean video_has_dts;
    gint64 video_decoded_frames;

    GstSegment audio_b_segment;
    gboolean has_audio_b_segment;
    gint64 audio_b_first_pts_ms;
    gint64 audio_b_last_pts_ms;
    gint64 audio_b_last_rt_ms;
    gint64 audio_b_arrival_clock_ms;
    gboolean audio_b_has_pts;
    gint64 audio_b_decoded_frames;

    gboolean baseline_calibrated;
    gint64 baseline_pts_offset_ms;
    gint64 initial_arrival_skew_ms;
    gint64 measured_pts_drift_ms;
    gint64 clock_skew_ms;

    gint64 audio_a_decoded_frames;
    gint video_b_decoders_created;
} SonixSyncState;

typedef struct _SonixGstData {
    volatile gint shutting_down;
    jobject app_obj;
    GMainContext *context;
    GMainLoop *main_loop;
    pthread_t loop_thread;
    pthread_mutex_t lock;
    SonixSyncState sync;

    GstClock *shared_clock;

    /* Pipeline A: Custom uridecodebin (Hardware Video + Dynamically Detachable Audio A Branch) */
    GstElement *video_pipeline;
    GstElement *video_src;
    GstElement *video_queue;
    GstElement *video_sink;
    GstElement *audio_a_queue;
    GstElement *audio_a_valve;
    GstElement *audio_a_fakesink;
    GstElement *audio_a_branch;   /* sub-bin: decodebin -> audioconvert -> audioresample -> volume -> openslessink (NULL when B active!) */
    GstElement *audio_a_volume;
    GSource *video_bus_source;
    ANativeWindow *native_window;

    char video_decoder_name[128];
    gboolean video_is_hw_decoder;
    char video_container_kind[64];
    gboolean first_video_frame_reported;
    gint video_a_audio_streams;
    gboolean original_audio_enabled;
    gfloat original_audio_volume;
    gboolean audio_a_decoder_active;

    /* Pipeline B: Single Audio Pipeline with input-selector (Active Slot + Candidate Slot) */
    GstElement *audio_pipeline;
    GstElement *audio_selector;
    GstElement *audio_post_convert;
    GstElement *audio_post_resample;
    GstElement *audio_post_volume;
    GstElement *audio_b_sink;
    GSource *audio_bus_source;
    AudioSourceSlot *active_slot;
    AudioSourceSlot *candidate_slot;
    gint64 audio_delay_ms;
    gint64 auto_drift_comp_ms;

    jmethodID on_video_state_id;
    jmethodID on_video_format_id;
    jmethodID on_video_topology_id;
    jmethodID on_audio_state_id;
    jmethodID on_candidate_promoted_id;
    jmethodID on_candidate_failed_id;
    jmethodID on_log_id;
} SonixGstData;

static pthread_mutex_t g_lifecycle_lock = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *g_jvm = NULL;
static SonixGstData *g_data = NULL;

/* ---- JNI Helpers ---- */

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

static gboolean is_alive(SonixGstData *d) {
    return d != NULL && g_atomic_int_get(&d->shutting_down) == 0 && d->app_obj != NULL;
}

static void emit_log(SonixGstData *d, const char *level, const char *msg) {
    if (!is_alive(d) || !d->on_log_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env || !is_alive(d)) {
        detach_jni(det);
        return;
    }
    jstring jlvl = (*env)->NewStringUTF(env, level ? level : "GST");
    jstring jmsg = (*env)->NewStringUTF(env, msg ? msg : "");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_log_id, jlvl, jmsg);
    (*env)->DeleteLocalRef(env, jlvl);
    (*env)->DeleteLocalRef(env, jmsg);
    detach_jni(det);
}

static void emit_video_state(SonixGstData *d, int code, const char *info) {
    if (!is_alive(d) || !d->on_video_state_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env || !is_alive(d)) {
        detach_jni(det);
        return;
    }
    jstring jinfo = (*env)->NewStringUTF(env, info ? info : "");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_video_state_id, (jint)code, jinfo);
    (*env)->DeleteLocalRef(env, jinfo);
    detach_jni(det);
}

static void emit_video_format(SonixGstData *d, int w, int h, const char *dec, gboolean is_hw) {
    if (!is_alive(d) || !d->on_video_format_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env || !is_alive(d)) {
        detach_jni(det);
        return;
    }
    jstring jdec = (*env)->NewStringUTF(env, dec ? dec : "unknown");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_video_format_id, (jint)w, (jint)h, jdec, (jboolean)is_hw);
    (*env)->DeleteLocalRef(env, jdec);
    detach_jni(det);
}

static void emit_video_topology(SonixGstData *d, const char *container, gint audio_tracks, gboolean audio_a_dec_active) {
    if (!is_alive(d) || !d->on_video_topology_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env || !is_alive(d)) {
        detach_jni(det);
        return;
    }
    jstring jcont = (*env)->NewStringUTF(env, container ? container : "Unknown");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_video_topology_id, jcont, (jint)audio_tracks, (jboolean)audio_a_dec_active);
    (*env)->DeleteLocalRef(env, jcont);
    detach_jni(det);
}

static void emit_audio_state(SonixGstData *d, int code, const char *info) {
    if (!is_alive(d) || !d->on_audio_state_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env || !is_alive(d)) {
        detach_jni(det);
        return;
    }
    jstring jinfo = (*env)->NewStringUTF(env, info ? info : "");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_audio_state_id, (jint)code, jinfo);
    (*env)->DeleteLocalRef(env, jinfo);
    detach_jni(det);
}

static void emit_candidate_promoted(SonixGstData *d, gint64 slot_id, const char *dec, const char *container, gint b_vid_decs) {
    if (!is_alive(d) || !d->on_candidate_promoted_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env || !is_alive(d)) {
        detach_jni(det);
        return;
    }
    jstring jdec = (*env)->NewStringUTF(env, dec ? dec : "unknown");
    jstring jcont = (*env)->NewStringUTF(env, container ? container : "Auto");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_candidate_promoted_id, (jlong)slot_id, jdec, jcont, (jint)b_vid_decs);
    (*env)->DeleteLocalRef(env, jdec);
    (*env)->DeleteLocalRef(env, jcont);
    detach_jni(det);
}

static void emit_candidate_failed(SonixGstData *d, gint64 slot_id, const char *err_kind, const char *msg) {
    if (!is_alive(d) || !d->on_candidate_failed_id) return;
    gboolean det = FALSE;
    JNIEnv *env = attach_jni(&det);
    if (!env || !is_alive(d)) {
        detach_jni(det);
        return;
    }
    jstring jkind = (*env)->NewStringUTF(env, err_kind ? err_kind : "AUDIO");
    jstring jmsg = (*env)->NewStringUTF(env, msg ? msg : "Audio candidate failed");
    (*env)->CallVoidMethod(env, d->app_obj, d->on_candidate_failed_id, (jlong)slot_id, jkind, jmsg);
    (*env)->DeleteLocalRef(env, jkind);
    (*env)->DeleteLocalRef(env, jmsg);
    detach_jni(det);
}

/* ---- Error Classification ---- */

static void classify_gst_error(GError *err, const gchar *dbg, char *out_kind, size_t kind_sz, char *out_msg, size_t msg_sz) {
    const char *raw = (err && err->message) ? err->message : "Unknown GStreamer error";
    const char *dbg_str = dbg ? dbg : "";
    const char *kind = "PIPELINE";

    if (strstr(raw, "403") || strstr(raw, "401") || strstr(raw, "429") || strstr(raw, "509") ||
        strstr(dbg_str, "403") || strstr(dbg_str, "401") || strstr(dbg_str, "429") || strstr(dbg_str, "509")) {
        kind = "HTTP_REJECT";
    } else if (strstr(raw, "TLS") || strstr(raw, "SSL") || strstr(raw, "certificate") ||
               strstr(dbg_str, "TLS") || strstr(dbg_str, "openssl") || strstr(dbg_str, "certificate")) {
        kind = "TLS";
    } else if (err && err->domain == GST_RESOURCE_ERROR) {
        kind = "HTTP";
    } else if (err && (err->domain == GST_STREAM_ERROR || err->domain == GST_CORE_ERROR)) {
        if (strstr(raw, "No decoder") || strstr(raw, "Missing") || strstr(raw, "codec") ||
            err->code == GST_CORE_ERROR_MISSING_PLUGIN || err->code == GST_STREAM_ERROR_CODEC_NOT_FOUND) {
            kind = "DECODER";
        } else {
            kind = "STREAM";
        }
    }

    snprintf(out_kind, kind_sz, "%s", kind);
    if (dbg && dbg[0]) {
        snprintf(out_msg, msg_sz, "[%s] %s | %s", kind, raw, dbg);
    } else {
        snprintf(out_msg, msg_sz, "[%s] %s", kind, raw);
    }
}

static void configure_http_source(GstElement *source) {
    if (!source) return;
    GObjectClass *klass = G_OBJECT_GET_CLASS(source);
    if (g_object_class_find_property(klass, "user-agent")) {
        g_object_set(source, "user-agent", "SONIX-TV/1.0", NULL);
    }
    if (g_object_class_find_property(klass, "timeout")) {
        g_object_set(source, "timeout", (guint)15, NULL);
    }
    if (g_object_class_find_property(klass, "retries")) {
        g_object_set(source, "retries", (gint)2, NULL);
    }
    if (g_object_class_find_property(klass, "compress")) {
        g_object_set(source, "compress", FALSE, NULL);
    }
}

static void on_source_setup(GstElement *parent, GstElement *source, gpointer user_data) {
    configure_http_source(source);
}

static void configure_bounded_queue(GstElement *queue, guint max_buffers, guint max_bytes, guint64 max_time_ns, gint leaky) {
    if (!queue) return;
    g_object_set(queue,
                 "max-size-buffers", max_buffers,
                 "max-size-bytes", max_bytes,
                 "max-size-time", max_time_ns,
                 "leaky", leaky, /* 2 = downstream */
                 NULL);
}

static void set_sink_async_false_if_supported(GstElement *sink) {
    if (!sink) return;
    if (g_object_class_find_property(G_OBJECT_GET_CLASS(sink), "async")) {
        g_object_set(sink, "async", FALSE, NULL);
    }
}

/* ---- Audio A Dynamic Branch Helpers (No Audio A Decoding While External Audio B Active!) ---- */

static void on_audio_a_decode_pad_added(GstElement *src, GstPad *new_pad, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    if (!is_alive(d)) return;

    GstCaps *caps = gst_pad_get_current_caps(new_pad);
    if (!caps) caps = gst_pad_query_caps(new_pad, NULL);
    if (!caps || gst_caps_is_empty(caps)) {
        if (caps) gst_caps_unref(caps);
        return;
    }
    const gchar *media = gst_structure_get_name(gst_caps_get_structure(caps, 0));
    if (media && g_str_has_prefix(media, "audio/")) {
        GstElement *conv = NULL;
        pthread_mutex_lock(&d->lock);
        if (d->audio_a_branch) {
            conv = gst_bin_get_by_name(GST_BIN(d->audio_a_branch), "aud_a_conv");
        }
        pthread_mutex_unlock(&d->lock);
        if (conv) {
            GstPad *sink_pad = gst_element_get_static_pad(conv, "sink");
            if (sink_pad && !gst_pad_is_linked(sink_pad)) {
                gst_pad_link(new_pad, sink_pad);
            }
            if (sink_pad) gst_object_unref(sink_pad);
            gst_object_unref(conv);
        }
    }
    gst_caps_unref(caps);
}

static GstPadProbeReturn on_audio_a_decoded_probe(GstPad *pad, GstPadProbeInfo *info, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    if (!is_alive(d)) return GST_PAD_PROBE_OK;
    if (GST_PAD_PROBE_INFO_TYPE(info) & GST_PAD_PROBE_TYPE_BUFFER) {
        pthread_mutex_lock(&d->sync.lock);
        d->sync.audio_a_decoded_frames++;
        pthread_mutex_unlock(&d->sync.lock);
    }
    return GST_PAD_PROBE_OK;
}

static GstElement *create_audio_a_branch_bin(SonixGstData *d) {
    GstElement *bin = gst_bin_new("audio_a_branch_bin");
    GstElement *dec = gst_element_factory_make("decodebin", "aud_a_decodebin");
    GstElement *conv = gst_element_factory_make("audioconvert", "aud_a_conv");
    GstElement *res = gst_element_factory_make("audioresample", "aud_a_resamp");
    GstElement *vol = gst_element_factory_make("volume", "aud_a_vol");
    GstElement *sink = gst_element_factory_make("openslessink", "aud_a_sink");

    if (!bin || !dec || !conv || !res || !vol || !sink) {
        if (bin) gst_object_unref(bin);
        if (dec) gst_object_unref(dec);
        if (conv) gst_object_unref(conv);
        if (res) gst_object_unref(res);
        if (vol) gst_object_unref(vol);
        if (sink) gst_object_unref(sink);
        return NULL;
    }

    g_object_set(vol, "volume", (gdouble)d->original_audio_volume, NULL);
    g_object_set(sink, "sync", TRUE, NULL);
    set_sink_async_false_if_supported(sink);

    gst_bin_add_many(GST_BIN(bin), dec, conv, res, vol, sink, NULL);
    gst_element_link_many(conv, res, vol, sink, NULL);

    g_signal_connect(dec, "pad-added", G_CALLBACK(on_audio_a_decode_pad_added), d);

    GstPad *conv_sink = gst_element_get_static_pad(conv, "sink");
    if (conv_sink) {
        gst_pad_add_probe(conv_sink, GST_PAD_PROBE_TYPE_BUFFER, on_audio_a_decoded_probe, d, NULL);
        gst_object_unref(conv_sink);
    }

    GstPad *dec_sink = gst_element_get_static_pad(dec, "sink");
    if (dec_sink) {
        GstPad *ghost = gst_ghost_pad_new("sink", dec_sink);
        gst_pad_set_active(ghost, TRUE);
        gst_element_add_pad(bin, ghost);
        gst_object_unref(dec_sink);
    }

    d->audio_a_volume = vol;
    return bin;
}

typedef struct _AsyncUnrefElem {
    GstElement *parent_bin;
    GstElement *elem;
} AsyncUnrefElem;

static gboolean idle_teardown_element_cb(gpointer data) {
    AsyncUnrefElem *req = (AsyncUnrefElem *)data;
    if (req) {
        if (req->elem) {
            gst_element_set_state(req->elem, GST_STATE_NULL);
            if (req->parent_bin) {
                gst_bin_remove(GST_BIN(req->parent_bin), req->elem);
            }
            gst_object_unref(req->elem);
        }
        if (req->parent_bin) {
            gst_object_unref(req->parent_bin);
        }
        free(req);
    }
    return G_SOURCE_REMOVE;
}

static void schedule_element_teardown(SonixGstData *d, GstElement *parent_bin, GstElement *elem) {
    if (!elem) return;
    AsyncUnrefElem *req = (AsyncUnrefElem *)calloc(1, sizeof(AsyncUnrefElem));
    if (!req) return;
    req->parent_bin = parent_bin ? GST_ELEMENT(gst_object_ref(parent_bin)) : NULL;
    req->elem = GST_ELEMENT(gst_object_ref(elem));
    if (d && d->context) {
        g_main_context_invoke(d->context, idle_teardown_element_cb, req);
    } else {
        idle_teardown_element_cb(req);
    }
}

typedef struct _ToggleAudioAReq {
    SonixGstData *d;
    gboolean enable;
} ToggleAudioAReq;

static gboolean mainloop_apply_audio_a_state(gpointer user_data) {
    ToggleAudioAReq *req = (ToggleAudioAReq *)user_data;
    SonixGstData *d = req->d;
    gboolean enable = req->enable;
    free(req);

    if (!is_alive(d)) return G_SOURCE_REMOVE;

    pthread_mutex_lock(&d->lock);
    d->original_audio_enabled = enable;
    GstElement *vpipe = d->video_pipeline;
    GstElement *valve = d->audio_a_valve;
    GstElement *fakesink = d->audio_a_fakesink;

    if (!vpipe || !valve || !fakesink) {
        d->audio_a_decoder_active = FALSE;
        pthread_mutex_unlock(&d->lock);
        return G_SOURCE_REMOVE;
    }

    if (!enable) {
        /* Step 1: Drop incoming compressed audio A packets BEFORE any decoder */
        g_object_set(valve, "drop", TRUE, NULL);
        GstElement *old_branch = d->audio_a_branch;
        d->audio_a_branch = NULL;
        d->audio_a_volume = NULL;
        d->audio_a_decoder_active = FALSE;

        GstPad *valve_src = gst_element_get_static_pad(valve, "src");
        if (old_branch && valve_src) {
            GstPad *old_sink = gst_element_get_static_pad(old_branch, "sink");
            if (old_sink) {
                gst_pad_unlink(valve_src, old_sink);
                gst_object_unref(old_sink);
            }
            GstPad *fake_sink = gst_element_get_static_pad(fakesink, "sink");
            if (fake_sink && !gst_pad_is_linked(fake_sink)) {
                gst_pad_link(valve_src, fake_sink);
            }
            if (fake_sink) gst_object_unref(fake_sink);
        }
        if (valve_src) gst_object_unref(valve_src);
        char container_copy[64];
        snprintf(container_copy, sizeof(container_copy), "%s", d->video_container_kind);
        gint tracks = d->video_a_audio_streams;
        pthread_mutex_unlock(&d->lock);

        if (old_branch) {
            gst_element_set_state(old_branch, GST_STATE_NULL);
            gst_bin_remove(GST_BIN(vpipe), old_branch);
            /* old_branch floating/bin ref already released by gst_bin_remove */
        }
        emit_video_topology(d, container_copy, tracks, FALSE);
        emit_log(d, "AUD-A", "تم تفريغ مفكك الصوت A وإسقاط الحزم قبل فك الترميز (0% CPU لصوت A)");
    } else {
        if (d->audio_a_branch == NULL) {
            GstElement *new_branch = create_audio_a_branch_bin(d);
            if (new_branch) {
                GstPad *valve_src = gst_element_get_static_pad(valve, "src");
                GstPad *fake_sink = gst_element_get_static_pad(fakesink, "sink");
                if (valve_src && fake_sink && gst_pad_is_linked(fake_sink)) {
                    gst_pad_unlink(valve_src, fake_sink);
                }
                if (fake_sink) gst_object_unref(fake_sink);

                gst_bin_add(GST_BIN(vpipe), new_branch);
                gst_element_sync_state_with_parent(new_branch);

                GstPad *branch_sink = gst_element_get_static_pad(new_branch, "sink");
                if (valve_src && branch_sink) {
                    gst_pad_link(valve_src, branch_sink);
                }
                if (branch_sink) gst_object_unref(branch_sink);
                if (valve_src) gst_object_unref(valve_src);

                d->audio_a_branch = new_branch;
                d->audio_a_decoder_active = TRUE;
            }
        }
        g_object_set(valve, "drop", FALSE, NULL);
        char container_copy[64];
        snprintf(container_copy, sizeof(container_copy), "%s", d->video_container_kind);
        gint tracks = d->video_a_audio_streams;
        pthread_mutex_unlock(&d->lock);

        emit_video_topology(d, container_copy, tracks, TRUE);
        emit_log(d, "AUD-A", "تم إعادة تفعيل مسار الصوت الأصلي A دون انقطاع الفيديو");
    }
    return G_SOURCE_REMOVE;
}

/* ---- Video Pipeline A Callbacks ---- */

static gint on_video_autoplug_select(GstElement *bin, GstPad *pad, GstCaps *caps,
                                     GstElementFactory *factory, gpointer user_data) {
    if (!factory) return 0;
    const gchar *klass = gst_element_factory_get_metadata(factory, GST_ELEMENT_METADATA_KLASS);
    if (klass) {
        /* Never instantiate an Audio Decoder inside uridecodebin for Video A:
         * expose compressed audio so our valve can drop it BEFORE any audio decoding! */
        if (strstr(klass, "Decoder") && strstr(klass, "Audio")) {
            return 1; /* GST_AUTOPLUG_SELECT_EXPOSE */
        }
        if (strstr(klass, "Decoder") && strstr(klass, "Subtitle")) {
            return 1;
        }
    }
    if (caps && gst_caps_get_size(caps) > 0) {
        const gchar *name = gst_structure_get_name(gst_caps_get_structure(caps, 0));
        if (name && (g_str_has_prefix(name, "text/") ||
                     g_str_has_prefix(name, "subpicture/") ||
                     g_str_has_prefix(name, "application/x-subtitle"))) {
            return 1;
        }
    }
    return 0; /* GST_AUTOPLUG_SELECT_TRY */
}

static void on_video_deep_element_added(GstBin *bin, GstBin *sub_bin, GstElement *element, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    if (!is_alive(d)) return;
    GstElementFactory *factory = gst_element_get_factory(element);
    if (!factory) return;

    const gchar *klass = gst_element_factory_get_metadata(factory, GST_ELEMENT_METADATA_KLASS);
    const gchar *fname = gst_plugin_feature_get_name(GST_PLUGIN_FEATURE(factory));
    if (!fname) return;

    if (strncmp(fname, "souphttpsrc", 11) == 0) {
        configure_http_source(element);
    }

    if (klass && strstr(klass, "Demuxer")) {
        const char *ck = NULL;
        if (strstr(fname, "hls")) ck = "HLS";
        else if (strstr(fname, "tsdemux") || strstr(fname, "mpegts")) ck = "MPEG-TS";
        else if (strstr(fname, "qtdemux")) ck = "fMP4";
        else if (strstr(fname, "dash")) ck = "DASH";
        if (ck) {
            pthread_mutex_lock(&d->lock);
            snprintf(d->video_container_kind, sizeof(d->video_container_kind), "%s", ck);
            gint tracks = d->video_a_audio_streams;
            gboolean act = d->audio_a_decoder_active;
            pthread_mutex_unlock(&d->lock);
            emit_video_topology(d, ck, tracks, act);
        }
    }

    if (klass && strstr(klass, "Decoder") && strstr(klass, "Video")) {
        gboolean is_hw = (g_str_has_prefix(fname, "amcviddec") || strstr(klass, "Hardware") != NULL);
        pthread_mutex_lock(&d->lock);
        snprintf(d->video_decoder_name, sizeof(d->video_decoder_name), "%s", fname);
        d->video_is_hw_decoder = is_hw;
        pthread_mutex_unlock(&d->lock);

        char msg[192];
        snprintf(msg, sizeof(msg), "مفكك الفيديو A المختار: %s (%s)",
                 fname, is_hw ? "عتادي MediaCodec HW" : "برمجي Software Fallback");
        emit_log(d, "DEC", msg);
    }
}

static void on_video_caps_notify(GObject *pad, GParamSpec *pspec, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    if (!is_alive(d)) return;
    GstCaps *caps = gst_pad_get_current_caps(GST_PAD(pad));
    if (!caps) return;
    GstVideoInfo vinfo;
    if (gst_video_info_from_caps(&vinfo, caps)) {
        char dec_copy[128];
        gboolean is_hw;
        pthread_mutex_lock(&d->lock);
        snprintf(dec_copy, sizeof(dec_copy), "%s",
                 d->video_decoder_name[0] ? d->video_decoder_name : "unknown");
        is_hw = d->video_is_hw_decoder;
        pthread_mutex_unlock(&d->lock);
        emit_video_format(d, vinfo.width, vinfo.height, dec_copy, is_hw);
    }
    gst_caps_unref(caps);
}

static GstPadProbeReturn on_video_sink_probe(GstPad *pad, GstPadProbeInfo *info, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    if (!is_alive(d)) return GST_PAD_PROBE_OK;

    GstPadProbeType ptype = GST_PAD_PROBE_INFO_TYPE(info);
    if (ptype & GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM) {
        GstEvent *ev = GST_PAD_PROBE_INFO_EVENT(info);
        if (ev && GST_EVENT_TYPE(ev) == GST_EVENT_SEGMENT) {
            const GstSegment *seg = NULL;
            gst_event_parse_segment(ev, &seg);
            if (seg) {
                pthread_mutex_lock(&d->sync.lock);
                d->sync.video_segment = *seg;
                d->sync.has_video_segment = TRUE;
                pthread_mutex_unlock(&d->sync.lock);
            }
        }
        return GST_PAD_PROBE_OK;
    }

    if (ptype & GST_PAD_PROBE_TYPE_BUFFER) {
        GstBuffer *buf = GST_PAD_PROBE_INFO_BUFFER(info);
        if (!buf) return GST_PAD_PROBE_OK;

        GstClockTime pts = GST_BUFFER_PTS(buf);
        GstClockTime dts = GST_BUFFER_DTS(buf);
        GstClockTime clock_now = d->shared_clock ? gst_clock_get_time(d->shared_clock) : GST_CLOCK_TIME_NONE;

        pthread_mutex_lock(&d->sync.lock);
        d->sync.video_decoded_frames++;
        if (GST_CLOCK_TIME_IS_VALID(pts)) {
            gint64 pts_ms = (gint64)(pts / GST_MSECOND);
            if (!d->sync.video_has_pts) {
                d->sync.video_first_pts_ms = pts_ms;
                d->sync.video_has_pts = TRUE;
            }
            d->sync.video_last_pts_ms = pts_ms;
            if (d->sync.has_video_segment) {
                guint64 rt = gst_segment_to_running_time(&d->sync.video_segment, GST_FORMAT_TIME, pts);
                if (GST_CLOCK_TIME_IS_VALID(rt)) {
                    d->sync.video_last_rt_ms = (gint64)(rt / GST_MSECOND);
                }
            }
        }
        if (GST_CLOCK_TIME_IS_VALID(dts)) {
            d->sync.video_last_dts_ms = (gint64)(dts / GST_MSECOND);
            d->sync.video_has_dts = TRUE;
        }
        if (GST_CLOCK_TIME_IS_VALID(clock_now)) {
            d->sync.video_arrival_clock_ms = (gint64)(clock_now / GST_MSECOND);
        }
        pthread_mutex_unlock(&d->sync.lock);

        gboolean should_report_first = FALSE;
        char dec_copy[128];
        pthread_mutex_lock(&d->lock);
        if (!d->first_video_frame_reported) {
            d->first_video_frame_reported = TRUE;
            should_report_first = TRUE;
        }
        snprintf(dec_copy, sizeof(dec_copy), "%s",
                 d->video_decoder_name[0] ? d->video_decoder_name : "GStreamer Video");
        pthread_mutex_unlock(&d->lock);

        if (should_report_first) {
            emit_video_state(d, 2, dec_copy);
            emit_log(d, "VID", "تم استلام وعرض أول إطار فيديو فعلي في glimagesink -> PLAYING");
        }
    }
    return GST_PAD_PROBE_OK;
}

static void on_video_pad_added(GstElement *src, GstPad *new_pad, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    if (!is_alive(d)) return;

    GstCaps *caps = gst_pad_get_current_caps(new_pad);
    if (!caps) caps = gst_pad_query_caps(new_pad, NULL);
    if (!caps || gst_caps_is_empty(caps)) {
        if (caps) gst_caps_unref(caps);
        return;
    }

    const gchar *media = gst_structure_get_name(gst_caps_get_structure(caps, 0));
    if (media && g_str_has_prefix(media, "video/")) {
        pthread_mutex_lock(&d->lock);
        GstElement *vq = d->video_queue;
        pthread_mutex_unlock(&d->lock);
        if (vq) {
            GstPad *sink_pad = gst_element_get_static_pad(vq, "sink");
            if (sink_pad && !gst_pad_is_linked(sink_pad)) {
                gst_pad_link(new_pad, sink_pad);
            }
            if (sink_pad) gst_object_unref(sink_pad);
        }
    } else if (media && g_str_has_prefix(media, "audio/")) {
        pthread_mutex_lock(&d->lock);
        d->video_a_audio_streams++;
        gint tracks = d->video_a_audio_streams;
        gboolean enabled = d->original_audio_enabled;
        GstElement *aq = d->audio_a_queue;
        GstElement *valve = d->audio_a_valve;
        GstElement *vpipe = d->video_pipeline;
        GstElement *fakesink = d->audio_a_fakesink;
        char container_copy[64];
        snprintf(container_copy, sizeof(container_copy), "%s", d->video_container_kind);
        pthread_mutex_unlock(&d->lock);

        GstPad *aq_sink = aq ? gst_element_get_static_pad(aq, "sink") : NULL;
        if (aq_sink && !gst_pad_is_linked(aq_sink)) {
            if (gst_pad_link(new_pad, aq_sink) == GST_PAD_LINK_OK) {
                /* Link valve -> either audio_a_branch (if A enabled) or fakesink (if B active) */
                pthread_mutex_lock(&d->lock);
                GstPad *valve_src = valve ? gst_element_get_static_pad(valve, "src") : NULL;
                if (valve_src && !gst_pad_is_linked(valve_src)) {
                    if (enabled && d->audio_a_branch == NULL && vpipe) {
                        GstElement *branch = create_audio_a_branch_bin(d);
                        if (branch) {
                            gst_bin_add(GST_BIN(vpipe), branch);
                            gst_element_sync_state_with_parent(branch);
                            GstPad *bsink = gst_element_get_static_pad(branch, "sink");
                            if (bsink) {
                                gst_pad_link(valve_src, bsink);
                                gst_object_unref(bsink);
                            }
                            d->audio_a_branch = branch;
                            d->audio_a_decoder_active = TRUE;
                            g_object_set(valve, "drop", FALSE, NULL);
                        }
                    } else if (fakesink) {
                        g_object_set(valve, "drop", TRUE, NULL);
                        GstPad *fsink = gst_element_get_static_pad(fakesink, "sink");
                        if (fsink) {
                            gst_pad_link(valve_src, fsink);
                            gst_object_unref(fsink);
                        }
                        d->audio_a_decoder_active = FALSE;
                    }
                }
                if (valve_src) gst_object_unref(valve_src);
                gboolean active = d->audio_a_decoder_active;
                pthread_mutex_unlock(&d->lock);
                emit_video_topology(d, container_copy, tracks, active);
            }
        } else {
            /* Additional audio track pad in multi-audio TS/HLS stream: safely drop */
            GstElement *drop_sink = gst_element_factory_make("fakesink", NULL);
            if (drop_sink && vpipe) {
                g_object_set(drop_sink, "sync", FALSE, "async", FALSE, "silent", TRUE, NULL);
                gst_bin_add(GST_BIN(vpipe), drop_sink);
                gst_element_sync_state_with_parent(drop_sink);
                GstPad *fsink = gst_element_get_static_pad(drop_sink, "sink");
                if (fsink) {
                    gst_pad_link(new_pad, fsink);
                    gst_object_unref(fsink);
                }
            } else if (drop_sink) {
                gst_object_unref(drop_sink);
            }
            emit_video_topology(d, container_copy, tracks, d->audio_a_decoder_active);
        }
        if (aq_sink) gst_object_unref(aq_sink);
    } else if (media) {
        pthread_mutex_lock(&d->lock);
        GstElement *vpipe = d->video_pipeline;
        pthread_mutex_unlock(&d->lock);
        GstElement *drop_sink = gst_element_factory_make("fakesink", NULL);
        if (drop_sink && vpipe) {
            g_object_set(drop_sink, "sync", FALSE, "async", FALSE, "silent", TRUE, NULL);
            gst_bin_add(GST_BIN(vpipe), drop_sink);
            gst_element_sync_state_with_parent(drop_sink);
            GstPad *fsink = gst_element_get_static_pad(drop_sink, "sink");
            if (fsink) {
                gst_pad_link(new_pad, fsink);
                gst_object_unref(fsink);
            }
        } else if (drop_sink) {
            gst_object_unref(drop_sink);
        }
    }
    gst_caps_unref(caps);
}

static gboolean on_video_bus_message(GstBus *bus, GstMessage *msg, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    if (!is_alive(d)) return FALSE;

    switch (GST_MESSAGE_TYPE(msg)) {
        case GST_MESSAGE_ERROR: {
            GError *err = NULL;
            gchar *dbg = NULL;
            gst_message_parse_error(msg, &err, &dbg);
            char kind[32], full_msg[512];
            classify_gst_error(err, dbg, kind, sizeof(kind), full_msg, sizeof(full_msg));
            emit_video_state(d, 4, full_msg);
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
                pthread_mutex_lock(&d->lock);
                gboolean first_seen = d->first_video_frame_reported;
                char dec_copy[128];
                snprintf(dec_copy, sizeof(dec_copy), "%s", d->video_decoder_name);
                pthread_mutex_unlock(&d->lock);
                if (first_seen) {
                    emit_video_state(d, 2, dec_copy);
                }
            }
            break;
        }
        case GST_MESSAGE_STATE_CHANGED: {
            pthread_mutex_lock(&d->lock);
            GstElement *vpipe = d->video_pipeline;
            gboolean first_seen = d->first_video_frame_reported;
            char dec_copy[128];
            snprintf(dec_copy, sizeof(dec_copy), "%s", d->video_decoder_name);
            pthread_mutex_unlock(&d->lock);
            if (vpipe && GST_MESSAGE_SRC(msg) == GST_OBJECT(vpipe)) {
                GstState old_s, new_s, pending;
                gst_message_parse_state_changed(msg, &old_s, &new_s, &pending);
                if (new_s == GST_STATE_PLAYING) {
                    if (first_seen) {
                        emit_video_state(d, 2, dec_copy[0] ? dec_copy : "Active");
                    } else {
                        /* Do NOT report PLAYING before real decoded video frame arrives! */
                        emit_video_state(d, 1, "");
                    }
                } else if (new_s == GST_STATE_PAUSED && old_s == GST_STATE_PLAYING) {
                    emit_video_state(d, 3, "");
                }
            }
            break;
        }
        case GST_MESSAGE_EOS:
            emit_log(d, "VID", "انتهى تدفق الفيديو A (EOS)");
            break;
        default:
            break;
    }
    return TRUE;
}

/* ---- Pipeline B: Audio-only Extraction + input-selector Candidate Switch ---- */

static void free_audio_slot_off_lock(SonixGstData *d, AudioSourceSlot *slot) {
    if (!slot) return;
    GstElement *pipe = d ? d->audio_pipeline : NULL;
    GstElement *sel = d ? d->audio_selector : NULL;

    if (slot->bin) {
        gst_element_set_state(slot->bin, GST_STATE_NULL);
    }
    if (slot->selector_pad && sel) {
        if (slot->ghost_src && gst_pad_is_linked(slot->selector_pad)) {
            gst_pad_unlink(slot->ghost_src, slot->selector_pad);
        }
        gst_element_release_request_pad(sel, slot->selector_pad);
        gst_object_unref(slot->selector_pad);
        slot->selector_pad = NULL;
    }
    if (slot->bin && pipe) {
        gst_bin_remove(GST_BIN(pipe), slot->bin);
        /* bin ref owned by pipe was dropped by gst_bin_remove */
    }
    free(slot);
}

typedef struct _PromoteReq {
    SonixGstData *d;
    gint64 slot_id;
} PromoteReq;

static gboolean mainloop_promote_candidate_cb(gpointer user_data) {
    PromoteReq *req = (PromoteReq *)user_data;
    SonixGstData *d = req->d;
    gint64 target_slot_id = req->slot_id;
    free(req);

    if (!is_alive(d)) return G_SOURCE_REMOVE;

    AudioSourceSlot *old_active = NULL;
    char dec_copy[128] = {0};
    char cont_copy[64] = {0};
    gint b_vid_decs = 0;

    pthread_mutex_lock(&d->lock);
    AudioSourceSlot *cand = d->candidate_slot;
    if (!cand || cand->slot_id != target_slot_id || cand->failed) {
        pthread_mutex_unlock(&d->lock);
        return G_SOURCE_REMOVE;
    }

    old_active = d->active_slot;
    d->active_slot = cand;
    d->candidate_slot = NULL;
    cand->is_candidate = FALSE;

    snprintf(dec_copy, sizeof(dec_copy), "%s",
             cand->audio_decoder_name[0] ? cand->audio_decoder_name : "GStreamer Audio");
    snprintf(cont_copy, sizeof(cont_copy), "%s",
             cand->container_kind[0] ? cand->container_kind : "Auto");
    b_vid_decs = cand->video_decoders_detected;

    pthread_mutex_lock(&d->sync.lock);
    d->sync.video_b_decoders_created = b_vid_decs;
    d->sync.audio_b_first_pts_ms = 0;
    d->sync.audio_b_last_pts_ms = 0;
    d->sync.audio_b_has_pts = FALSE;
    d->sync.baseline_calibrated = FALSE;
    d->sync.measured_pts_drift_ms = 0;
    pthread_mutex_unlock(&d->sync.lock);

    pthread_mutex_unlock(&d->lock);

    /* Retire old Audio B slot AFTER new C is already active on selector! */
    if (old_active) {
        free_audio_slot_off_lock(d, old_active);
    }

    emit_candidate_promoted(d, target_slot_id, dec_copy, cont_copy, b_vid_decs);
    return G_SOURCE_REMOVE;
}

typedef struct _FailCandidateReq {
    SonixGstData *d;
    gint64 slot_id;
    char kind[32];
    char msg[512];
} FailCandidateReq;

static gboolean mainloop_fail_candidate_cb(gpointer user_data) {
    FailCandidateReq *req = (FailCandidateReq *)user_data;
    SonixGstData *d = req->d;
    gint64 target_id = req->slot_id;

    if (!is_alive(d)) {
        free(req);
        return G_SOURCE_REMOVE;
    }

    AudioSourceSlot *to_free = NULL;
    pthread_mutex_lock(&d->lock);
    if (d->candidate_slot && d->candidate_slot->slot_id == target_id) {
        to_free = d->candidate_slot;
        d->candidate_slot = NULL;
    }
    pthread_mutex_unlock(&d->lock);

    if (to_free) {
        free_audio_slot_off_lock(d, to_free);
        emit_candidate_failed(d, target_id, req->kind, req->msg);
    }
    free(req);
    return G_SOURCE_REMOVE;
}

static gint on_slot_autoplug_select(GstElement *bin, GstPad *pad, GstCaps *caps,
                                    GstElementFactory *factory, gpointer user_data) {
    if (caps && gst_caps_get_size(caps) > 0) {
        const gchar *name = gst_structure_get_name(gst_caps_get_structure(caps, 0));
        if (name && (g_str_has_prefix(name, "video/") ||
                     g_str_has_prefix(name, "text/") ||
                     g_str_has_prefix(name, "subpicture/") ||
                     g_str_has_prefix(name, "application/x-subtitle"))) {
            /* Expose BEFORE parser or decoder is plugged! */
            return 1; /* GST_AUTOPLUG_SELECT_EXPOSE */
        }
    }
    if (factory) {
        const gchar *klass = gst_element_factory_get_metadata(factory, GST_ELEMENT_METADATA_KLASS);
        if (klass && strstr(klass, "Video") && (strstr(klass, "Decoder") || strstr(klass, "Parser"))) {
            return 1;
        }
    }
    return 0;
}

static void on_slot_deep_element_added(GstBin *bin, GstBin *sub_bin, GstElement *element, gpointer user_data) {
    AudioSourceSlot *slot = (AudioSourceSlot *)user_data;
    if (!slot || !is_alive(slot->owner)) return;

    GstElementFactory *factory = gst_element_get_factory(element);
    if (!factory) return;
    const gchar *klass = gst_element_factory_get_metadata(factory, GST_ELEMENT_METADATA_KLASS);
    const gchar *fname = gst_plugin_feature_get_name(GST_PLUGIN_FEATURE(factory));
    if (!fname) return;

    if (strncmp(fname, "souphttpsrc", 11) == 0) {
        configure_http_source(element);
    }

    if (klass && strstr(klass, "Demuxer")) {
        if (strstr(fname, "hls")) snprintf(slot->container_kind, sizeof(slot->container_kind), "HLS");
        else if (strstr(fname, "tsdemux") || strstr(fname, "mpegts")) snprintf(slot->container_kind, sizeof(slot->container_kind), "MPEG-TS");
        else if (strstr(fname, "qtdemux")) snprintf(slot->container_kind, sizeof(slot->container_kind), "fMP4");
    }

    if (klass && strstr(klass, "Decoder")) {
        if (strstr(klass, "Video")) {
            slot->video_decoders_detected++;
            LOGE("CRITICAL: Unexpected video decoder in Audio B slot: %s", fname);
        } else if (strstr(klass, "Audio")) {
            snprintf(slot->audio_decoder_name, sizeof(slot->audio_decoder_name), "%s", fname);
            LOGI("Audio B slot %lld decoder selected: %s", (long long)slot->slot_id, fname);
        }
    }
}

static void on_slot_pad_added(GstElement *src, GstPad *new_pad, gpointer user_data) {
    AudioSourceSlot *slot = (AudioSourceSlot *)user_data;
    if (!slot || !is_alive(slot->owner)) return;

    GstCaps *caps = gst_pad_get_current_caps(new_pad);
    if (!caps) caps = gst_pad_query_caps(new_pad, NULL);
    if (!caps || gst_caps_is_empty(caps)) {
        if (caps) gst_caps_unref(caps);
        return;
    }

    const gchar *media = gst_structure_get_name(gst_caps_get_structure(caps, 0));
    if (media && g_str_has_prefix(media, "audio/")) {
        GstPad *q_sink = gst_element_get_static_pad(slot->queue, "sink");
        if (q_sink && !gst_pad_is_linked(q_sink)) {
            if (gst_pad_link(new_pad, q_sink) == GST_PAD_LINK_OK) {
                LOGI("Audio slot %lld linked audio pad: %s", (long long)slot->slot_id, media);
            }
        }
        if (q_sink) gst_object_unref(q_sink);
    } else if (media && slot->bin) {
        GstElement *drop_sink = gst_element_factory_make("fakesink", NULL);
        if (drop_sink) {
            g_object_set(drop_sink, "sync", FALSE, "async", FALSE, "silent", TRUE, "enable-last-sample", FALSE, NULL);
            gst_bin_add(GST_BIN(slot->bin), drop_sink);
            gst_element_sync_state_with_parent(drop_sink);
            GstPad *fsink = gst_element_get_static_pad(drop_sink, "sink");
            if (fsink && gst_pad_link(new_pad, fsink) == GST_PAD_LINK_OK) {
                slot->video_pads_dropped++;
                LOGI("Audio slot %lld dropped non-audio pad (%s) before decode", (long long)slot->slot_id, media);
            }
            if (fsink) gst_object_unref(fsink);
        }
    }
    gst_caps_unref(caps);
}

static GstPadProbeReturn on_slot_ghost_src_probe(GstPad *pad, GstPadProbeInfo *info, gpointer user_data) {
    AudioSourceSlot *slot = (AudioSourceSlot *)user_data;
    if (!slot || !is_alive(slot->owner)) return GST_PAD_PROBE_OK;
    SonixGstData *d = slot->owner;

    GstPadProbeType ptype = GST_PAD_PROBE_INFO_TYPE(info);
    if (ptype & GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM) {
        GstEvent *ev = GST_PAD_PROBE_INFO_EVENT(info);
        if (ev && GST_EVENT_TYPE(ev) == GST_EVENT_SEGMENT) {
            const GstSegment *seg = NULL;
            gst_event_parse_segment(ev, &seg);
            if (seg) {
                slot->slot_segment = *seg;
                slot->has_segment = TRUE;
            }
        }
        return GST_PAD_PROBE_OK;
    }

    if (ptype & GST_PAD_PROBE_TYPE_BUFFER) {
        GstBuffer *buf = GST_PAD_PROBE_INFO_BUFFER(info);
        if (!buf) return GST_PAD_PROBE_OK;

        if (!slot->first_buffer_seen) {
            slot->first_buffer_seen = TRUE;

            /* Align candidate slot's running time with active audio_pipeline clock so openslessink plays immediately */
            if (d->audio_pipeline && d->shared_clock) {
                GstClockTime now = gst_clock_get_time(d->shared_clock);
                GstClockTime base = gst_element_get_base_time(d->audio_pipeline);
                if (GST_CLOCK_TIME_IS_VALID(now) && GST_CLOCK_TIME_IS_VALID(base) && now >= base) {
                    GstClockTime pipe_rt = now - base;
                    GstClockTime pts = GST_BUFFER_PTS(buf);
                    guint64 buf_rt = 0;
                    if (slot->has_segment && GST_CLOCK_TIME_IS_VALID(pts)) {
                        guint64 rt = gst_segment_to_running_time(&slot->slot_segment, GST_FORMAT_TIME, pts);
                        if (GST_CLOCK_TIME_IS_VALID(rt)) buf_rt = rt;
                    }
                    gint64 offset = (gint64)pipe_rt - (gint64)buf_rt;
                    gst_pad_set_offset(pad, offset);
                }
            }

            if (slot->is_candidate && d->audio_selector && slot->selector_pad) {
                /* Atomically switch active pad to the verified ready candidate C! */
                g_object_set(d->audio_selector, "active-pad", slot->selector_pad, NULL);
                PromoteReq *req = (PromoteReq *)calloc(1, sizeof(PromoteReq));
                if (req) {
                    req->d = d;
                    req->slot_id = slot->slot_id;
                    g_main_context_invoke(d->context, mainloop_promote_candidate_cb, req);
                }
            }
        }
    }
    return GST_PAD_PROBE_OK;
}

static GstPadProbeReturn on_audio_b_sink_probe(GstPad *pad, GstPadProbeInfo *info, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    if (!is_alive(d)) return GST_PAD_PROBE_OK;

    GstPadProbeType ptype = GST_PAD_PROBE_INFO_TYPE(info);
    if (ptype & GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM) {
        GstEvent *ev = GST_PAD_PROBE_INFO_EVENT(info);
        if (ev && GST_EVENT_TYPE(ev) == GST_EVENT_SEGMENT) {
            const GstSegment *seg = NULL;
            gst_event_parse_segment(ev, &seg);
            if (seg) {
                pthread_mutex_lock(&d->sync.lock);
                d->sync.audio_b_segment = *seg;
                d->sync.has_audio_b_segment = TRUE;
                pthread_mutex_unlock(&d->sync.lock);
            }
        }
        return GST_PAD_PROBE_OK;
    }

    if (ptype & GST_PAD_PROBE_TYPE_BUFFER) {
        GstBuffer *buf = GST_PAD_PROBE_INFO_BUFFER(info);
        if (!buf) return GST_PAD_PROBE_OK;

        GstClockTime pts = GST_BUFFER_PTS(buf);
        GstClockTime clock_now = d->shared_clock ? gst_clock_get_time(d->shared_clock) : GST_CLOCK_TIME_NONE;

        pthread_mutex_lock(&d->sync.lock);
        d->sync.audio_b_decoded_frames++;
        if (GST_CLOCK_TIME_IS_VALID(pts)) {
            gint64 pts_ms = (gint64)(pts / GST_MSECOND);
            if (!d->sync.audio_b_has_pts) {
                d->sync.audio_b_first_pts_ms = pts_ms;
                d->sync.audio_b_has_pts = TRUE;
            }
            d->sync.audio_b_last_pts_ms = pts_ms;
            if (d->sync.has_audio_b_segment) {
                guint64 rt = gst_segment_to_running_time(&d->sync.audio_b_segment, GST_FORMAT_TIME, pts);
                if (GST_CLOCK_TIME_IS_VALID(rt)) {
                    d->sync.audio_b_last_rt_ms = (gint64)(rt / GST_MSECOND);
                }
            }
            if (d->sync.video_has_pts) {
                if (!d->sync.baseline_calibrated) {
                    d->sync.baseline_pts_offset_ms = d->sync.video_last_pts_ms - pts_ms;
                    if (GST_CLOCK_TIME_IS_VALID(clock_now)) {
                        d->sync.initial_arrival_skew_ms = d->sync.video_arrival_clock_ms - (gint64)(clock_now / GST_MSECOND);
                    }
                    d->sync.baseline_calibrated = TRUE;
                    d->sync.measured_pts_drift_ms = 0;
                } else {
                    gint64 cur_delta = d->sync.video_last_pts_ms - pts_ms;
                    d->sync.measured_pts_drift_ms = cur_delta - d->sync.baseline_pts_offset_ms;
                }
            }
        }
        if (GST_CLOCK_TIME_IS_VALID(clock_now)) {
            d->sync.audio_b_arrival_clock_ms = (gint64)(clock_now / GST_MSECOND);
            if (d->sync.video_arrival_clock_ms > 0) {
                d->sync.clock_skew_ms = (d->sync.video_last_rt_ms - d->sync.audio_b_last_rt_ms);
            }
        }
        pthread_mutex_unlock(&d->sync.lock);
    }
    return GST_PAD_PROBE_OK;
}

static gboolean on_audio_bus_message(GstBus *bus, GstMessage *msg, gpointer user_data) {
    SonixGstData *d = (SonixGstData *)user_data;
    if (!is_alive(d)) return FALSE;

    switch (GST_MESSAGE_TYPE(msg)) {
        case GST_MESSAGE_ERROR: {
            GError *err = NULL;
            gchar *dbg = NULL;
            gst_message_parse_error(msg, &err, &dbg);
            char kind[32], full_msg[512];
            classify_gst_error(err, dbg, kind, sizeof(kind), full_msg, sizeof(full_msg));

            /* Isolate errors belonging ONLY to candidate slot C while keeping active B playing! */
            gboolean from_candidate = FALSE;
            gint64 cand_id = 0;
            pthread_mutex_lock(&d->lock);
            if (d->candidate_slot && d->candidate_slot->bin && GST_MESSAGE_SRC(msg) &&
                gst_object_has_as_ancestor(GST_MESSAGE_SRC(msg), GST_OBJECT(d->candidate_slot->bin))) {
                from_candidate = TRUE;
                cand_id = d->candidate_slot->slot_id;
                d->candidate_slot->failed = TRUE;
            }
            pthread_mutex_unlock(&d->lock);

            if (from_candidate) {
                FailCandidateReq *req = (FailCandidateReq *)calloc(1, sizeof(FailCandidateReq));
                if (req) {
                    req->d = d;
                    req->slot_id = cand_id;
                    snprintf(req->kind, sizeof(req->kind), "%s", kind);
                    snprintf(req->msg, sizeof(req->msg), "%s", full_msg);
                    g_main_context_invoke(d->context, mainloop_fail_candidate_cb, req);
                }
            } else {
                emit_audio_state(d, 3, full_msg);
            }
            if (err) g_error_free(err);
            g_free(dbg);
            break;
        }
        case GST_MESSAGE_BUFFERING: {
            gint percent = 0;
            gst_message_parse_buffering(msg, &percent);
            gboolean from_candidate = FALSE;
            pthread_mutex_lock(&d->lock);
            if (d->candidate_slot && d->candidate_slot->bin && GST_MESSAGE_SRC(msg) &&
                gst_object_has_as_ancestor(GST_MESSAGE_SRC(msg), GST_OBJECT(d->candidate_slot->bin))) {
                from_candidate = TRUE;
            }
            pthread_mutex_unlock(&d->lock);
            if (!from_candidate && percent < 100) {
                emit_audio_state(d, 1, "");
            }
            break;
        }
        case GST_MESSAGE_EOS: {
            gboolean from_candidate = FALSE;
            gint64 cand_id = 0;
            pthread_mutex_lock(&d->lock);
            if (d->candidate_slot && d->candidate_slot->bin && GST_MESSAGE_SRC(msg) &&
                gst_object_has_as_ancestor(GST_MESSAGE_SRC(msg), GST_OBJECT(d->candidate_slot->bin))) {
                from_candidate = TRUE;
                cand_id = d->candidate_slot->slot_id;
                d->candidate_slot->failed = TRUE;
            }
            pthread_mutex_unlock(&d->lock);
            if (from_candidate) {
                FailCandidateReq *req = (FailCandidateReq *)calloc(1, sizeof(FailCandidateReq));
                if (req) {
                    req->d = d;
                    req->slot_id = cand_id;
                    snprintf(req->kind, sizeof(req->kind), "EOS");
                    snprintf(req->msg, sizeof(req->msg), "انتهى تدفق المصدر المرشح (EOS) قبل وصول بيانات صالحة");
                    g_main_context_invoke(d->context, mainloop_fail_candidate_cb, req);
                }
            } else {
                emit_audio_state(d, 3, "[STREAM] انتهى تدفق مصدر الصوت الخارجي (EOS)");
            }
            break;
        }
        default:
            break;
    }
    return TRUE;
}

static AudioSourceSlot *create_audio_slot(SonixGstData *d, const char *url, gint64 slot_id, gboolean is_candidate) {
    char bin_name[64];
    snprintf(bin_name, sizeof(bin_name), "audio_slot_bin_%lld", (long long)slot_id);

    AudioSourceSlot *slot = (AudioSourceSlot *)calloc(1, sizeof(AudioSourceSlot));
    if (!slot) return NULL;
    slot->owner = d;
    slot->slot_id = slot_id;
    slot->is_candidate = is_candidate;
    snprintf(slot->url, sizeof(slot->url), "%s", url);
    snprintf(slot->container_kind, sizeof(slot->container_kind), "Auto");

    slot->bin = gst_bin_new(bin_name);
    slot->src = gst_element_factory_make("uridecodebin", NULL);
    slot->queue = gst_element_factory_make("queue", NULL);
    slot->conv = gst_element_factory_make("audioconvert", NULL);
    slot->resample = gst_element_factory_make("audioresample", NULL);
    slot->capsfilter = gst_element_factory_make("capsfilter", NULL);

    if (!slot->bin || !slot->src || !slot->queue || !slot->conv || !slot->resample || !slot->capsfilter) {
        if (slot->bin) gst_object_unref(slot->bin);
        if (slot->src) gst_object_unref(slot->src);
        if (slot->queue) gst_object_unref(slot->queue);
        if (slot->conv) gst_object_unref(slot->conv);
        if (slot->resample) gst_object_unref(slot->resample);
        if (slot->capsfilter) gst_object_unref(slot->capsfilter);
        free(slot);
        return NULL;
    }

    g_object_set(slot->src, "uri", url, "use-buffering", TRUE, "buffer-duration", (gint64)(2 * GST_SECOND), NULL);
    configure_bounded_queue(slot->queue, 150, 4 * 1024 * 1024, (guint64)(2 * GST_SECOND), 2);

    /* Normalize all slots to identical PCM format so B->C switch causes ZERO OpenSL ES reconfiguration! */
    GstCaps *fixed_pcm = gst_caps_from_string("audio/x-raw,format=S16LE,rate=48000,channels=2");
    if (fixed_pcm) {
        g_object_set(slot->capsfilter, "caps", fixed_pcm, NULL);
        gst_caps_unref(fixed_pcm);
    }

    gst_bin_add_many(GST_BIN(slot->bin), slot->src, slot->queue, slot->conv, slot->resample, slot->capsfilter, NULL);
    gst_element_link_many(slot->queue, slot->conv, slot->resample, slot->capsfilter, NULL);

    g_signal_connect(slot->src, "source-setup", G_CALLBACK(on_source_setup), d);
    g_signal_connect(slot->src, "autoplug-select", G_CALLBACK(on_slot_autoplug_select), slot);
    g_signal_connect(slot->src, "deep-element-added", G_CALLBACK(on_slot_deep_element_added), slot);
    g_signal_connect(slot->src, "pad-added", G_CALLBACK(on_slot_pad_added), slot);

    GstPad *cf_src = gst_element_get_static_pad(slot->capsfilter, "src");
    slot->ghost_src = gst_ghost_pad_new("src", cf_src);
    gst_pad_set_active(slot->ghost_src, TRUE);
    gst_element_add_pad(slot->bin, slot->ghost_src);
    gst_object_unref(cf_src);

    gst_pad_add_probe(slot->ghost_src,
                      GST_PAD_PROBE_TYPE_BUFFER | GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM,
                      on_slot_ghost_src_probe, slot, NULL);

    return slot;
}

static gboolean ensure_audio_pipeline_locked(SonixGstData *d, char *err_buf, size_t err_sz) {
    if (d->audio_pipeline) return TRUE;

    GstElement *pipe = gst_pipeline_new("external_audio_b_pipeline");
    GstElement *sel = gst_element_factory_make("input-selector", "aud_b_selector");
    GstElement *conv = gst_element_factory_make("audioconvert", "aud_b_post_conv");
    GstElement *res = gst_element_factory_make("audioresample", "aud_b_post_resamp");
    GstElement *vol = gst_element_factory_make("volume", "aud_b_post_vol");
    GstElement *sink = gst_element_factory_make("openslessink", "aud_b_sink");

    if (!pipe || !sel || !conv || !res || !vol || !sink) {
        snprintf(err_buf, err_sz, "Missing GStreamer Audio B element (%s%s%s%s%s)",
                 !sel ? "input-selector " : "",
                 !conv ? "audioconvert " : "",
                 !res ? "audioresample " : "",
                 !vol ? "volume " : "",
                 !sink ? "openslessink" : "");
        if (pipe) gst_object_unref(pipe);
        if (sel) gst_object_unref(sel);
        if (conv) gst_object_unref(conv);
        if (res) gst_object_unref(res);
        if (vol) gst_object_unref(vol);
        if (sink) gst_object_unref(sink);
        return FALSE;
    }

    g_object_set(sel, "sync-streams", FALSE, "sync-mode", 0, NULL);
    g_object_set(sink,
                 "sync", TRUE,
                 "ts-offset", (gint64)((d->audio_delay_ms + d->auto_drift_comp_ms) * GST_MSECOND),
                 NULL);
    set_sink_async_false_if_supported(sink);

    gst_bin_add_many(GST_BIN(pipe), sel, conv, res, vol, sink, NULL);
    if (!gst_element_link_many(sel, conv, res, vol, sink, NULL)) {
        snprintf(err_buf, err_sz, "Failed to link input-selector -> openslessink");
        gst_object_unref(pipe);
        return FALSE;
    }

    gst_pipeline_use_clock(GST_PIPELINE(pipe), d->shared_clock);

    GstPad *sink_pad = gst_element_get_static_pad(sink, "sink");
    if (sink_pad) {
        gst_pad_add_probe(sink_pad,
                          GST_PAD_PROBE_TYPE_BUFFER | GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM,
                          on_audio_b_sink_probe, d, NULL);
        gst_object_unref(sink_pad);
    }

    GstBus *bus = gst_element_get_bus(pipe);
    GSource *w = gst_bus_create_watch(bus);
    g_source_set_callback(w, (GSourceFunc)on_audio_bus_message, d, NULL);
    g_source_attach(w, d->context);
    gst_object_unref(bus);

    d->audio_pipeline = pipe;
    d->audio_selector = sel;
    d->audio_post_convert = conv;
    d->audio_post_resample = res;
    d->audio_post_volume = vol;
    d->audio_b_sink = sink;
    d->audio_bus_source = w;
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
            gst_plugin_feature_set_rank(f, GST_RANK_PRIMARY + 50);
            hw_count++;
        }
    }
    gst_plugin_feature_list_free(factories);
    char msg[160];
    snprintf(msg, sizeof(msg), "تم رفع أولوية مفككات MediaCodec العتادية (amcviddec المكتشفة: %d)", hw_count);
    emit_log(d, "HW", msg);
}

/* ---- JNI Entry Points ---- */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeInit(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&g_lifecycle_lock);
    if (!g_data) {
        SonixGstData *d = (SonixGstData *)calloc(1, sizeof(SonixGstData));
        g_atomic_int_set(&d->shutting_down, 0);
        pthread_mutex_init(&d->lock, NULL);
        pthread_mutex_init(&d->sync.lock, NULL);
        d->original_audio_enabled = TRUE;
        d->original_audio_volume = 1.0f;
        d->app_obj = (*env)->NewGlobalRef(env, thiz);

        jclass cls = (*env)->GetObjectClass(env, thiz);
        d->on_video_state_id = (*env)->GetMethodID(env, cls, "onNativeVideoState", "(ILjava/lang/String;)V");
        d->on_video_format_id = (*env)->GetMethodID(env, cls, "onNativeVideoFormat", "(IILjava/lang/String;Z)V");
        d->on_video_topology_id = (*env)->GetMethodID(env, cls, "onNativeVideoTopology", "(Ljava/lang/String;IZ)V");
        d->on_audio_state_id = (*env)->GetMethodID(env, cls, "onNativeAudioState", "(ILjava/lang/String;)V");
        d->on_candidate_promoted_id = (*env)->GetMethodID(env, cls, "onNativeCandidateAudioPromoted", "(JLjava/lang/String;Ljava/lang/String;I)V");
        d->on_candidate_failed_id = (*env)->GetMethodID(env, cls, "onNativeCandidateAudioFailed", "(JLjava/lang/String;Ljava/lang/String;)V");
        d->on_log_id = (*env)->GetMethodID(env, cls, "onNativeLog", "(Ljava/lang/String;Ljava/lang/String;)V");
        (*env)->DeleteLocalRef(env, cls);

        d->context = g_main_context_new();
        d->shared_clock = gst_system_clock_obtain();
        g_data = d;
        pthread_create(&d->loop_thread, NULL, gst_loop_worker, d);
        boost_mediacodec_hardware_decoders(d);
    }
    pthread_mutex_unlock(&g_lifecycle_lock);

    gchar *ver = gst_version_string();
    jstring jver = (*env)->NewStringUTF(env, ver ? ver : "GStreamer 1.x");
    g_free(ver);
    return jver;
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeSetSurface(JNIEnv *env, jobject thiz, jobject surface) {
    SonixGstData *d = g_data;
    if (!is_alive(d)) return;

    ANativeWindow *new_win = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    ANativeWindow *old_win = NULL;
    GstElement *vsink = NULL;

    pthread_mutex_lock(&d->lock);
    old_win = d->native_window;
    d->native_window = new_win;
    vsink = d->video_sink ? GST_ELEMENT(gst_object_ref(d->video_sink)) : NULL;
    pthread_mutex_unlock(&d->lock);

    /* Never hold d->lock while invoking video_overlay_set_window_handle! */
    if (vsink && GST_IS_VIDEO_OVERLAY(vsink)) {
        gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(vsink), (guintptr)new_win);
    }
    if (vsink) gst_object_unref(vsink);
    if (old_win) ANativeWindow_release(old_win);
}

static void detach_video_pipeline_off_lock(SonixGstData *d, GstElement **out_pipe, GSource **out_bus) {
    pthread_mutex_lock(&d->lock);
    *out_pipe = d->video_pipeline;
    *out_bus = d->video_bus_source;
    d->video_pipeline = NULL;
    d->video_src = NULL;
    d->video_queue = NULL;
    d->video_sink = NULL;
    d->audio_a_queue = NULL;
    d->audio_a_valve = NULL;
    d->audio_a_fakesink = NULL;
    d->audio_a_branch = NULL;
    d->audio_a_volume = NULL;
    d->video_bus_source = NULL;
    d->video_decoder_name[0] = '\0';
    d->video_is_hw_decoder = FALSE;
    d->video_container_kind[0] = '\0';
    d->first_video_frame_reported = FALSE;
    d->video_a_audio_streams = 0;
    d->audio_a_decoder_active = FALSE;
    pthread_mutex_unlock(&d->lock);

    pthread_mutex_lock(&d->sync.lock);
    d->sync.has_video_segment = FALSE;
    d->sync.video_has_pts = FALSE;
    d->sync.video_has_dts = FALSE;
    d->sync.video_first_pts_ms = 0;
    d->sync.video_last_pts_ms = 0;
    d->sync.video_last_dts_ms = 0;
    d->sync.video_last_rt_ms = 0;
    d->sync.video_arrival_clock_ms = 0;
    d->sync.video_decoded_frames = 0;
    d->sync.audio_a_decoded_frames = 0;
    d->sync.baseline_calibrated = FALSE;
    d->sync.measured_pts_drift_ms = 0;
    pthread_mutex_unlock(&d->sync.lock);
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativePlayVideo(JNIEnv *env, jobject thiz, jstring jurl) {
    SonixGstData *d = g_data;
    if (!is_alive(d) || !jurl) return;
    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);

    /* Teardown old Video A cleanly OFF lock (Audio B keeps playing untouched!) */
    GstElement *old_pipe = NULL;
    GSource *old_bus = NULL;
    detach_video_pipeline_off_lock(d, &old_pipe, &old_bus);
    if (old_bus) {
        g_source_destroy(old_bus);
        g_source_unref(old_bus);
    }
    if (old_pipe) {
        gst_element_set_state(old_pipe, GST_STATE_NULL);
        gst_object_unref(old_pipe);
    }

    GstElement *pipe = gst_pipeline_new("video_a_pipeline");
    GstElement *src = gst_element_factory_make("uridecodebin", "vid_a_src");
    GstElement *vq = gst_element_factory_make("queue", "vid_a_queue");
    GstElement *vsink = gst_element_factory_make("glimagesink", "vid_a_glsink");
    GstElement *aq = gst_element_factory_make("queue", "aud_a_queue");
    GstElement *valve = gst_element_factory_make("valve", "aud_a_valve");
    GstElement *fsink = gst_element_factory_make("fakesink", "aud_a_fakesink");

    if (!pipe || !src || !vq || !vsink || !aq || !valve || !fsink) {
        char missing[256];
        snprintf(missing, sizeof(missing), "[DECODER] عناصر GStreamer مفقودة للفيديو A: %s%s%s%s%s%s",
                 !src ? "uridecodebin " : "",
                 !vq || !aq ? "queue " : "",
                 !vsink ? "glimagesink " : "",
                 !valve ? "valve " : "",
                 !fsink ? "fakesink " : "",
                 !pipe ? "pipeline" : "");
        emit_video_state(d, 4, missing);
        if (pipe) gst_object_unref(pipe);
        if (src) gst_object_unref(src);
        if (vq) gst_object_unref(vq);
        if (vsink) gst_object_unref(vsink);
        if (aq) gst_object_unref(aq);
        if (valve) gst_object_unref(valve);
        if (fsink) gst_object_unref(fsink);
        (*env)->ReleaseStringUTFChars(env, jurl, url);
        return;
    }

    g_object_set(src, "uri", url, "use-buffering", TRUE, "buffer-duration", (gint64)(2 * GST_SECOND), NULL);
    configure_bounded_queue(vq, 120, 16 * 1024 * 1024, (guint64)(2 * GST_SECOND), 2);
    configure_bounded_queue(aq, 120, 4 * 1024 * 1024, (guint64)(2 * GST_SECOND), 2);
    g_object_set(vsink, "sync", TRUE, "qos", TRUE, NULL);
    g_object_set(fsink, "sync", FALSE, "async", FALSE, "silent", TRUE, NULL);

    gst_bin_add_many(GST_BIN(pipe), src, vq, vsink, aq, valve, fsink, NULL);
    if (!gst_element_link(vq, vsink) || !gst_element_link(aq, valve)) {
        emit_video_state(d, 4, "[PIPELINE] فشل ربط عناصر Video/Audio A داخل GstPipeline");
        gst_object_unref(pipe);
        (*env)->ReleaseStringUTFChars(env, jurl, url);
        return;
    }

    gst_pipeline_use_clock(GST_PIPELINE(pipe), d->shared_clock);

    g_signal_connect(src, "source-setup", G_CALLBACK(on_source_setup), d);
    g_signal_connect(src, "autoplug-select", G_CALLBACK(on_video_autoplug_select), d);
    g_signal_connect(src, "deep-element-added", G_CALLBACK(on_video_deep_element_added), d);
    g_signal_connect(src, "pad-added", G_CALLBACK(on_video_pad_added), d);

    GstPad *vpad = gst_element_get_static_pad(vsink, "sink");
    if (vpad) {
        g_signal_connect(vpad, "notify::caps", G_CALLBACK(on_video_caps_notify), d);
        gst_pad_add_probe(vpad,
                          GST_PAD_PROBE_TYPE_BUFFER | GST_PAD_PROBE_TYPE_EVENT_DOWNSTREAM,
                          on_video_sink_probe, d, NULL);
        gst_object_unref(vpad);
    }

    GstBus *bus = gst_element_get_bus(pipe);
    GSource *bus_src = gst_bus_create_watch(bus);
    g_source_set_callback(bus_src, (GSourceFunc)on_video_bus_message, d, NULL);
    g_source_attach(bus_src, d->context);
    gst_object_unref(bus);

    ANativeWindow *win = NULL;
    pthread_mutex_lock(&d->lock);
    d->video_pipeline = pipe;
    d->video_src = src;
    d->video_queue = vq;
    d->video_sink = vsink;
    d->audio_a_queue = aq;
    d->audio_a_valve = valve;
    d->audio_a_fakesink = fsink;
    d->video_bus_source = bus_src;
    g_object_set(valve, "drop", !d->original_audio_enabled, NULL);
    win = d->native_window;
    pthread_mutex_unlock(&d->lock);

    if (win && GST_IS_VIDEO_OVERLAY(vsink)) {
        gst_video_overlay_set_window_handle(GST_VIDEO_OVERLAY(vsink), (guintptr)win);
    }

    gst_element_set_state(pipe, GST_STATE_PLAYING);
    (*env)->ReleaseStringUTFChars(env, jurl, url);
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeSetVideoPaused(JNIEnv *env, jobject thiz, jboolean paused) {
    SonixGstData *d = g_data;
    if (!is_alive(d)) return;
    pthread_mutex_lock(&d->lock);
    GstElement *vpipe = d->video_pipeline ? GST_ELEMENT(gst_object_ref(d->video_pipeline)) : NULL;
    pthread_mutex_unlock(&d->lock);
    if (vpipe) {
        gst_element_set_state(vpipe, paused ? GST_STATE_PAUSED : GST_STATE_PLAYING);
        gst_object_unref(vpipe);
    }
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeSetAudioPaused(JNIEnv *env, jobject thiz, jboolean paused) {
    SonixGstData *d = g_data;
    if (!is_alive(d)) return;
    pthread_mutex_lock(&d->lock);
    GstElement *apipe = d->audio_pipeline ? GST_ELEMENT(gst_object_ref(d->audio_pipeline)) : NULL;
    pthread_mutex_unlock(&d->lock);
    if (apipe) {
        gst_element_set_state(apipe, paused ? GST_STATE_PAUSED : GST_STATE_PLAYING);
        gst_object_unref(apipe);
    }
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeStopVideo(JNIEnv *env, jobject thiz) {
    SonixGstData *d = g_data;
    if (!is_alive(d)) return;
    GstElement *old_pipe = NULL;
    GSource *old_bus = NULL;
    detach_video_pipeline_off_lock(d, &old_pipe, &old_bus);
    if (old_bus) {
        g_source_destroy(old_bus);
        g_source_unref(old_bus);
    }
    if (old_pipe) {
        gst_element_set_state(old_pipe, GST_STATE_NULL);
        gst_object_unref(old_pipe);
    }
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeSetOriginalAudioEnabled(JNIEnv *env, jobject thiz, jboolean enabled) {
    SonixGstData *d = g_data;
    if (!is_alive(d)) return;
    ToggleAudioAReq *req = (ToggleAudioAReq *)calloc(1, sizeof(ToggleAudioAReq));
    if (!req) return;
    req->d = d;
    req->enable = (gboolean)enabled;
    g_main_context_invoke(d->context, mainloop_apply_audio_a_state, req);
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeSetVideoVolume(JNIEnv *env, jobject thiz, jfloat volume) {
    SonixGstData *d = g_data;
    if (!is_alive(d)) return;
    gfloat clamped = volume < 0.0f ? 0.0f : (volume > 1.0f ? 1.0f : volume);
    pthread_mutex_lock(&d->lock);
    d->original_audio_volume = clamped;
    GstElement *vol = d->audio_a_volume ? GST_ELEMENT(gst_object_ref(d->audio_a_volume)) : NULL;
    pthread_mutex_unlock(&d->lock);
    if (vol) {
        g_object_set(vol, "volume", (gdouble)clamped, NULL);
        gst_object_unref(vol);
    }
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeAttachOrSwitchExternalAudio(JNIEnv *env, jobject thiz, jstring jurl, jlong delay_ms, jlong slot_id) {
    SonixGstData *d = g_data;
    if (!is_alive(d) || !jurl) return;
    const char *url = (*env)->GetStringUTFChars(env, jurl, NULL);

    char err_buf[256] = {0};
    AudioSourceSlot *old_cand = NULL;
    AudioSourceSlot *new_cand = NULL;
    GstElement *apipe = NULL;
    GstElement *sel = NULL;

    pthread_mutex_lock(&d->lock);
    d->audio_delay_ms = (gint64)delay_ms;
    if (!ensure_audio_pipeline_locked(d, err_buf, sizeof(err_buf))) {
        pthread_mutex_unlock(&d->lock);
        emit_candidate_failed(d, (gint64)slot_id, "ELEMENT", err_buf);
        (*env)->ReleaseStringUTFChars(env, jurl, url);
        return;
    }

    old_cand = d->candidate_slot;
    d->candidate_slot = NULL;
    apipe = GST_ELEMENT(gst_object_ref(d->audio_pipeline));
    sel = GST_ELEMENT(gst_object_ref(d->audio_selector));
    g_object_set(d->audio_b_sink, "ts-offset", (gint64)((d->audio_delay_ms + d->auto_drift_comp_ms) * GST_MSECOND), NULL);
    pthread_mutex_unlock(&d->lock);

    if (old_cand) {
        free_audio_slot_off_lock(d, old_cand);
    }

    new_cand = create_audio_slot(d, url, (gint64)slot_id, TRUE);
    (*env)->ReleaseStringUTFChars(env, jurl, url);

    if (!new_cand) {
        gst_object_unref(apipe);
        gst_object_unref(sel);
        emit_candidate_failed(d, (gint64)slot_id, "ELEMENT", "فشل إنشاء مسار الصوت المرشح C/B");
        return;
    }

    GstPad *sel_pad = gst_element_request_pad_simple(sel, "sink_%u");
    if (!sel_pad) {
        free(new_cand);
        gst_object_unref(apipe);
        gst_object_unref(sel);
        emit_candidate_failed(d, (gint64)slot_id, "PIPELINE", "فشل حجز منفذ في input-selector");
        return;
    }
    new_cand->selector_pad = sel_pad;

    if (!gst_bin_add(GST_BIN(apipe), new_cand->bin)) {
        gst_element_release_request_pad(sel, sel_pad);
        gst_object_unref(sel_pad);
        free(new_cand);
        gst_object_unref(apipe);
        gst_object_unref(sel);
        emit_candidate_failed(d, (gint64)slot_id, "PIPELINE", "تعذر إضافة مسار الصوت إلى Audio Pipeline B");
        return;
    }

    if (gst_pad_link(new_cand->ghost_src, sel_pad) != GST_PAD_LINK_OK) {
        gst_bin_remove(GST_BIN(apipe), new_cand->bin);
        gst_element_release_request_pad(sel, sel_pad);
        gst_object_unref(sel_pad);
        free(new_cand);
        gst_object_unref(apipe);
        gst_object_unref(sel);
        emit_candidate_failed(d, (gint64)slot_id, "PIPELINE", "فشل ربط مسار الصوت بالمحول input-selector");
        return;
    }

    pthread_mutex_lock(&d->lock);
    d->candidate_slot = new_cand;
    pthread_mutex_unlock(&d->lock);

    gst_element_set_state(apipe, GST_STATE_PLAYING);
    gst_element_sync_state_with_parent(new_cand->bin);

    gst_object_unref(apipe);
    gst_object_unref(sel);
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeStopExternalAudio(JNIEnv *env, jobject thiz) {
    SonixGstData *d = g_data;
    if (!is_alive(d)) return;

    AudioSourceSlot *cand = NULL;
    AudioSourceSlot *act = NULL;
    GstElement *apipe = NULL;
    GSource *abus = NULL;

    pthread_mutex_lock(&d->lock);
    cand = d->candidate_slot;
    act = d->active_slot;
    apipe = d->audio_pipeline;
    abus = d->audio_bus_source;
    d->candidate_slot = NULL;
    d->active_slot = NULL;
    d->audio_pipeline = NULL;
    d->audio_selector = NULL;
    d->audio_post_convert = NULL;
    d->audio_post_resample = NULL;
    d->audio_post_volume = NULL;
    d->audio_b_sink = NULL;
    d->audio_bus_source = NULL;
    d->auto_drift_comp_ms = 0;
    pthread_mutex_unlock(&d->lock);

    if (abus) {
        g_source_destroy(abus);
        g_source_unref(abus);
    }
    if (apipe) {
        gst_element_set_state(apipe, GST_STATE_NULL);
    }
    if (cand) free(cand);
    if (act) free(act);
    if (apipe) {
        gst_object_unref(apipe);
    }

    pthread_mutex_lock(&d->sync.lock);
    d->sync.has_audio_b_segment = FALSE;
    d->sync.audio_b_has_pts = FALSE;
    d->sync.audio_b_first_pts_ms = 0;
    d->sync.audio_b_last_pts_ms = 0;
    d->sync.audio_b_last_rt_ms = 0;
    d->sync.audio_b_arrival_clock_ms = 0;
    d->sync.audio_b_decoded_frames = 0;
    d->sync.baseline_calibrated = FALSE;
    d->sync.measured_pts_drift_ms = 0;
    d->sync.clock_skew_ms = 0;
    d->sync.video_b_decoders_created = 0;
    pthread_mutex_unlock(&d->sync.lock);
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeSetAudioDelayMs(JNIEnv *env, jobject thiz, jlong delay_ms) {
    SonixGstData *d = g_data;
    if (!is_alive(d)) return;
    pthread_mutex_lock(&d->lock);
    d->audio_delay_ms = (gint64)delay_ms;
    gint64 total_offset_ns = (d->audio_delay_ms + d->auto_drift_comp_ms) * GST_MSECOND;
    GstElement *bsink = d->audio_b_sink ? GST_ELEMENT(gst_object_ref(d->audio_b_sink)) : NULL;
    pthread_mutex_unlock(&d->lock);
    if (bsink) {
        g_object_set(bsink, "ts-offset", total_offset_ns, NULL);
        gst_object_unref(bsink);
    }
}

JNIEXPORT jlongArray JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeQuerySyncSnapshot(JNIEnv *env, jobject thiz) {
    jlong values[12];
    for (int i = 0; i < 12; i++) values[i] = 0;
    values[3] = -1;
    values[4] = -1;
    values[5] = -1;

    SonixGstData *d = g_data;
    if (is_alive(d)) {
        pthread_mutex_lock(&d->lock);
        GstElement *vpipe = d->video_pipeline ? GST_ELEMENT(gst_object_ref(d->video_pipeline)) : NULL;
        GstElement *apipe = d->audio_pipeline ? GST_ELEMENT(gst_object_ref(d->audio_pipeline)) : NULL;
        GstElement *bsink = d->audio_b_sink ? GST_ELEMENT(gst_object_ref(d->audio_b_sink)) : NULL;
        gint64 delay_ms = d->audio_delay_ms;
        pthread_mutex_unlock(&d->lock);

        gint64 vpos = 0, apos = 0;
        if (vpipe) {
            if (gst_element_query_position(vpipe, GST_FORMAT_TIME, &vpos)) {
                values[0] = vpos / GST_MSECOND;
            }
            gst_object_unref(vpipe);
        }
        if (apipe) {
            if (gst_element_query_position(apipe, GST_FORMAT_TIME, &apos)) {
                values[1] = apos / GST_MSECOND;
            }
            gst_object_unref(apipe);
        }
        values[2] = delay_ms;

        gint64 drift_comp = 0;
        gboolean need_comp = FALSE;
        pthread_mutex_lock(&d->sync.lock);
        values[3] = d->sync.video_has_pts ? d->sync.video_last_pts_ms : -1;
        values[4] = d->sync.video_has_dts ? d->sync.video_last_dts_ms : -1;
        values[5] = d->sync.audio_b_has_pts ? d->sync.audio_b_last_pts_ms : -1;
        values[6] = (d->sync.video_has_pts && d->sync.audio_b_has_pts && d->sync.baseline_calibrated) ? 1 : 0;
        values[7] = d->sync.baseline_pts_offset_ms;
        values[8] = d->sync.measured_pts_drift_ms;
        values[9] = d->sync.clock_skew_ms;
        values[10] = d->sync.video_b_decoders_created;
        values[11] = d->sync.audio_a_decoded_frames;

        if (values[6] == 1 && (d->sync.measured_pts_drift_ms > 180 || d->sync.measured_pts_drift_ms < -180)) {
            gint64 step = d->sync.measured_pts_drift_ms / 4;
            if (step > 40) step = 40;
            if (step < -40) step = -40;
            d->sync.baseline_pts_offset_ms += step;
            d->sync.measured_pts_drift_ms -= step;
            drift_comp = step;
            need_comp = TRUE;
        }
        pthread_mutex_unlock(&d->sync.lock);

        if (need_comp && bsink) {
            pthread_mutex_lock(&d->lock);
            d->auto_drift_comp_ms += drift_comp;
            gint64 new_ts_offset = (d->audio_delay_ms + d->auto_drift_comp_ms) * GST_MSECOND;
            pthread_mutex_unlock(&d->lock);
            g_object_set(bsink, "ts-offset", new_ts_offset, NULL);
        }
        if (bsink) gst_object_unref(bsink);
    }

    jlongArray arr = (*env)->NewLongArray(env, 12);
    if (arr) {
        (*env)->SetLongArrayRegion(env, arr, 0, 12, values);
    }
    return arr;
}

JNIEXPORT void JNICALL
Java_com_example_gst_SonixGStreamerEngine_nativeShutdown(JNIEnv *env, jobject thiz) {
    pthread_mutex_lock(&g_lifecycle_lock);
    SonixGstData *d = g_data;
    if (!d) {
        pthread_mutex_unlock(&g_lifecycle_lock);
        return;
    }

    g_atomic_int_set(&d->shutting_down, 1);

    pthread_mutex_lock(&d->lock);
    GstElement *vpipe = d->video_pipeline;
    GSource *vbus = d->video_bus_source;
    GstElement *apipe = d->audio_pipeline;
    GSource *abus = d->audio_bus_source;
    AudioSourceSlot *act = d->active_slot;
    AudioSourceSlot *cand = d->candidate_slot;
    ANativeWindow *win = d->native_window;
    GMainLoop *loop = d->main_loop;
    jobject app_ref = d->app_obj;

    d->video_pipeline = NULL;
    d->video_sink = NULL;
    d->video_bus_source = NULL;
    d->audio_pipeline = NULL;
    d->audio_selector = NULL;
    d->audio_b_sink = NULL;
    d->audio_bus_source = NULL;
    d->active_slot = NULL;
    d->candidate_slot = NULL;
    d->native_window = NULL;
    d->app_obj = NULL;
    pthread_mutex_unlock(&d->lock);

    if (vbus) {
        g_source_destroy(vbus);
        g_source_unref(vbus);
    }
    if (abus) {
        g_source_destroy(abus);
        g_source_unref(abus);
    }
    if (vpipe) {
        gst_element_set_state(vpipe, GST_STATE_NULL);
        gst_object_unref(vpipe);
    }
    if (apipe) {
        gst_element_set_state(apipe, GST_STATE_NULL);
    }
    if (act) free(act);
    if (cand) free(cand);
    if (apipe) {
        gst_object_unref(apipe);
    }
    if (win) {
        ANativeWindow_release(win);
    }

    if (loop) {
        g_main_loop_quit(loop);
    }
    if (d->context) {
        g_main_context_wakeup(d->context);
    }
    if (d->loop_thread) {
        pthread_join(d->loop_thread, NULL);
    }

    if (d->shared_clock) {
        gst_object_unref(d->shared_clock);
        d->shared_clock = NULL;
    }
    if (d->context) {
        g_main_context_unref(d->context);
        d->context = NULL;
    }
    if (app_ref) {
        (*env)->DeleteGlobalRef(env, app_ref);
    }
    pthread_mutex_destroy(&d->sync.lock);
    pthread_mutex_destroy(&d->lock);
    free(d);
    g_data = NULL;

    pthread_mutex_unlock(&g_lifecycle_lock);
}

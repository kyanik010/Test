package com.example.audio

/**
 * Standard error codes for the SONIX AUDIO engine.
 */
enum class AudioErrorCode(val code: String, val messageAr: String) {
    SOURCE_HTTP_ERROR("SOURCE_HTTP_ERROR", "فشل الاتصال بمصدر القناة (خطأ في الخادم)"),
    SOURCE_TIMEOUT("SOURCE_TIMEOUT", "انتهت مهلة الاتصال بمصدر القناة"),
    SOURCE_AUTH_ERROR("SOURCE_AUTH_ERROR", "فشل التوثيق مع مزود البث"),
    INVALID_HLS_PLAYLIST("INVALID_HLS_PLAYLIST", "قائمة HLS غير صالحة"),
    NO_AUDIO_TRACK("NO_AUDIO_TRACK", "لم يتم العثور على مسار صوتي صالح في البث"),
    UNSUPPORTED_AUDIO_CODEC("UNSUPPORTED_AUDIO_CODEC", "ترميز الصوت غير مدعوم"),
    TS_DEMUX_ERROR("TS_DEMUX_ERROR", "خطأ في استخراج المسار الصوتي من الحاوية"),
    DECODER_INIT_ERROR("DECODER_INIT_ERROR", "فشل تهيئة مفكك ترميز الصوت"),
    DECODER_OUTPUT_ERROR("DECODER_OUTPUT_ERROR", "فشل فك ترميز إطارات الصوت"),
    DECODER_UNAVAILABLE("DECODER_UNAVAILABLE", "مفكك ترميز الصوت غير متاح في النظام"),
    PCM_NOT_PRODUCED("PCM_NOT_PRODUCED", "تعذر إنتاج عينات PCM الصوتية"),
    AAC_ENCODER_ERROR("AAC_ENCODER_ERROR", "فشل مشفر AAC المستقل"),
    AAC_OUTPUT_EMPTY("AAC_OUTPUT_EMPTY", "لم يتم إنتاج إطارات AAC صالحة"),
    LOCAL_SERVER_ERROR("LOCAL_SERVER_ERROR", "فشل خادم البث المحلي المستقل"),
    MEDIA3_SOURCE_ERROR("MEDIA3_SOURCE_ERROR", "تعذر تحميل المورد الصوتي في المشغل"),
    MEDIA3_PLAYBACK_ERROR("MEDIA3_PLAYBACK_ERROR", "فشل تشغيل الصوت في Media3"),
    SESSION_CANCELLED("SESSION_CANCELLED", "تم إلغاء جلسة الصوت")
}

/**
 * State and data models for the SONIX AUDIO ADDON engine.
 */
enum class AudioExtractionState(val code: String, val displayNameAr: String) {
    IDLE("idle", "خامل"),
    CHANNEL_DISCOVERED("channel_discovered", "تم اكتشاف القناة"),
    STREAM_OPENING("stream_opening", "جارٍ فتح المصدر"),
    STREAM_INSPECTED("stream_inspected", "تم تحليل المسار الصوتي"),
    DECODING_PCM("decoding_pcm", "جارٍ فك ترميز الصوت إلى PCM"),
    OUTPUT_PRODUCING("output_producing", "جارٍ إنتاج المورد المستقل"),
    RESOURCE_READY("resource_ready", "المورد الصوتي المستقل جاهز"),
    PLAYING("playing", "يعمل"),
    STOPPED("stopped", "متوقف"),
    FAILED_INSPECTION("failed_inspection", "فشل فحص المصدر"),
    FAILED_DECODING("failed_decoding", "فشل فك ترميز الصوت"),
    FAILED_OUTPUT("failed_output", "فشل إنتاج المورد الصوتي"),
    FAILED_GENERAL("failed_general", "فشل الاتصال بالمصدر")
}

enum class AudioChannelGroup(val code: String, val displayName: String) {
    BEIN_SPORTS("BEIN_SPORTS", "beIN SPORTS"),
    ALWAN("ALWAN", "ALWAN"),
    OTHER("OTHER", "قناة أخرى")
}

data class AudioChannelInfo(
    val id: String,
    val name: String,
    val number: String = "",
    val group: AudioChannelGroup,
    val categoryName: String = "",
    val categoryId: String = "",
    val containerExtension: String = "ts"
)

data class AudioInspectionResult(
    val isSupported: Boolean,
    val containerType: String,
    val audioMimeType: String,
    val sampleRate: Int = 48000,
    val channelCount: Int = 2,
    val bitrate: Int = 128000,
    val hasVideoTrack: Boolean = true,
    val videoMimeType: String? = null,
    val hlsAudioOnlyUrl: String? = null,
    val errorCode: AudioErrorCode? = null,
    val errorMessage: String? = null
)

data class AudioEngineMetrics(
    val sessionId: String = "",
    val streamId: String = "",
    val containerType: String = "",
    val pcmBytesProduced: Long = 0L,
    val pcmSamplesDecoded: Long = 0L,
    val encodedFramesCount: Long = 0L,
    val activeBitrate: Int = 0,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val audioCodec: String = "",
    val audioPid: Int = -1,
    val videoPid: Int = -1,
    val isHardwareAccelerated: Boolean = true,
    val localServerPort: Int = 0,
    val lastState: AudioExtractionState = AudioExtractionState.IDLE,
    val lastErrorCode: AudioErrorCode? = null,
    val lastError: String? = null
)

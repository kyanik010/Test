LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := sonix_gst
LOCAL_SRC_FILES := sonix_gstreamer.c
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog -landroid

include $(BUILD_SHARED_LIBRARY)

ifndef GSTREAMER_ROOT_ANDROID
GSTREAMER_ROOT_ANDROID := /opt/gstreamer-android
endif

ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/armv7
else ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
GSTREAMER_ROOT := $(GSTREAMER_ROOT_ANDROID)/arm64
else
$(error Target arch ABI $(TARGET_ARCH_ABI) not supported)
endif

GSTREAMER_NDK_BUILD_PATH := $(GSTREAMER_ROOT)/share/gst-android/ndk-build/
GSTREAMER_JAVA_SRC_DIR := $(abspath $(LOCAL_PATH)/../java)
GSTREAMER_ASSETS_DIR := $(abspath $(LOCAL_PATH)/../assets)
GSTREAMER_INCLUDE_FONTS := no
GSTREAMER_INCLUDE_CA_CERTIFICATES := yes

G_IO_MODULES := openssl

GSTREAMER_PLUGINS := \
	coreelements \
	playback \
	audioconvert \
	audiorate \
	audioresample \
	typefindfunctions \
	videoconvertscale \
	videorate \
	volume \
	autodetect \
	app \
	adaptivedemux2 \
	hls \
	dash \
	isomp4 \
	audioparsers \
	videoparsersbad \
	id3demux \
	mpg123 \
	mpegtsdemux \
	libav \
	androidmedia \
	soup \
	tcp \
	udp \
	rtsp \
	opengl \
	opensles

GSTREAMER_EXTRA_DEPS := gstreamer-video-1.0 gstreamer-audio-1.0

include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk

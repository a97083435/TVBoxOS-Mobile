LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := tvbox_ass
LOCAL_SRC_FILES := subtitle_ass.cpp
LOCAL_CPPFLAGS := -std=c++17 -fvisibility=hidden -Wall -Wextra
LOCAL_LDFLAGS := -Wl,--exclude-libs,ALL
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)

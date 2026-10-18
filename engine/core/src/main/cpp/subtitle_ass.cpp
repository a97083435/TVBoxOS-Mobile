#include <jni.h>
#include <dlfcn.h>
#include <algorithm>
#include <vector>
#include <string>
#include "libass/ass.h"

// Resolve the existing player's public libass ABI. No second copy of libass is linked.
namespace {
struct Api {
    void *handle = dlopen("libmpv.so", RTLD_NOW | RTLD_LOCAL);
#define SYMBOL(name) decltype(&name) name##_p = handle ? reinterpret_cast<decltype(&name)>(dlsym(handle, #name)) : nullptr
    SYMBOL(ass_library_init); SYMBOL(ass_library_done); SYMBOL(ass_renderer_init);
    SYMBOL(ass_renderer_done); SYMBOL(ass_set_frame_size); SYMBOL(ass_set_fonts);
    SYMBOL(ass_read_memory); SYMBOL(ass_free_track); SYMBOL(ass_render_frame);
    SYMBOL(ass_set_fonts_dir); SYMBOL(ass_set_cache_limits); SYMBOL(ass_library_version);
    SYMBOL(ass_new_track); SYMBOL(ass_process_codec_private); SYMBOL(ass_process_chunk); SYMBOL(ass_flush_events);
    SYMBOL(ass_prune_events);
#undef SYMBOL
    bool valid() const { return ass_library_init_p && ass_library_done_p && ass_renderer_init_p && ass_renderer_done_p
        && ass_set_frame_size_p && ass_set_fonts_p && ass_read_memory_p && ass_free_track_p
        && ass_render_frame_p && ass_set_fonts_dir_p && ass_set_cache_limits_p && ass_library_version_p
        && ass_new_track_p && ass_process_codec_private_p && ass_process_chunk_p && ass_flush_events_p
        && ass_library_version_p() >= 0x01300000; }
};
Api &api() { static Api value; return value; }
struct Session { ASS_Library *library; ASS_Renderer *renderer; };
std::string string(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *text = env->GetStringUTFChars(value, nullptr);
    if (!text) return {};
    std::string result(text); env->ReleaseStringUTFChars(value, text); return result;
}
constexpr int WIDTH = 1920, HEIGHT = 1080;
}
#define JNI_METHOD(name) extern "C" JNIEXPORT name
JNI_METHOD(jboolean) Java_com_kukuqi_tvbox_engine_player_subtitle_AssNative_available(JNIEnv *, jclass) { return api().valid(); }
JNI_METHOD(jlong) Java_com_kukuqi_tvbox_engine_player_subtitle_AssNative_open(JNIEnv *env, jclass, jstring config, jstring fonts, jstring family) {
    auto &a = api(); if (!a.valid()) return 0;
    auto *library = a.ass_library_init_p(); if (!library) return 0;
    std::string dir = string(env, fonts); a.ass_set_fonts_dir_p(library, dir.c_str());
    auto *renderer = a.ass_renderer_init_p(library);
    if (!renderer) { a.ass_library_done_p(library); return 0; }
    a.ass_set_frame_size_p(renderer, WIDTH, HEIGHT);
    a.ass_set_cache_limits_p(renderer, 4096, 48);
    std::string conf = string(env, config), name = string(env, family);
    a.ass_set_fonts_p(renderer, nullptr, name.empty() ? "sans-serif" : name.c_str(), ASS_FONTPROVIDER_AUTODETECT, conf.empty() ? nullptr : conf.c_str(), 1);
    return reinterpret_cast<jlong>(new Session{library, renderer});
}
JNI_METHOD(jlong) Java_com_kukuqi_tvbox_engine_player_subtitle_AssNative_read(JNIEnv *env, jclass, jlong pointer, jbyteArray data) {
    auto *session = reinterpret_cast<Session *>(pointer); if (!session || !data) return 0;
    jsize length = env->GetArrayLength(data); if (length <= 0 || length > 8 * 1024 * 1024) return 0;
    std::vector<char> buffer(length + 1);
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte *>(buffer.data()));
    return reinterpret_cast<jlong>(api().ass_read_memory_p(session->library, buffer.data(), length, nullptr));
}
JNI_METHOD(jlong) Java_com_kukuqi_tvbox_engine_player_subtitle_AssNative_endMs(JNIEnv *, jclass, jlong pointer) {
    auto *track = reinterpret_cast<ASS_Track *>(pointer); long long end = 0;
    if (track) for (int i = 0; i < track->n_events; i++) end = std::max(end, track->events[i].Start + track->events[i].Duration);
    return std::min(end, 24LL * 60 * 60 * 1000);
}
JNI_METHOD(jlong) Java_com_kukuqi_tvbox_engine_player_subtitle_AssNative_newTrack(JNIEnv *env, jclass, jlong pointer, jbyteArray data) {
    auto *session = reinterpret_cast<Session *>(pointer); if (!session || !data) return 0;
    jsize length = env->GetArrayLength(data); if (length <= 0 || length > 8 * 1024 * 1024) return 0;
    ASS_Track *track = api().ass_new_track_p(session->library); if (!track) return 0;
    std::vector<char> buffer(length + 1);
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte *>(buffer.data()));
    api().ass_process_codec_private_p(track, buffer.data(), length); return reinterpret_cast<jlong>(track);
}
JNI_METHOD(void) Java_com_kukuqi_tvbox_engine_player_subtitle_AssNative_chunk(JNIEnv *env, jclass, jlong pointer, jbyteArray data, jlong start, jlong duration, jboolean reset) {
    auto *track = reinterpret_cast<ASS_Track *>(pointer); if (!track || !data) return;
    if (reset) api().ass_flush_events_p(track);
    if (api().ass_prune_events_p) api().ass_prune_events_p(track, start - 300000);
    jsize length = env->GetArrayLength(data); if (length > 8 * 1024 * 1024) return;
    std::vector<char> buffer(length + 1); env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte *>(buffer.data()));
    api().ass_process_chunk_p(track, buffer.data(), length, start, duration);
}
JNI_METHOD(jintArray) Java_com_kukuqi_tvbox_engine_player_subtitle_AssNative_render(JNIEnv *env, jclass, jlong context, jlong pointer, jlong time) {
    auto *session = reinterpret_cast<Session *>(context); auto *track = reinterpret_cast<ASS_Track *>(pointer);
    if (!session || !track) return nullptr;
    int changed = 0; auto *images = api().ass_render_frame_p(session->renderer, track, time, &changed);
    if (!changed) return nullptr;
    int left = WIDTH, top = HEIGHT, right = 0, bottom = 0;
    for (auto *img = images; img; img = img->next) if (img->w > 0 && img->h > 0) {
        left = std::min(left, std::max(0, img->dst_x)); top = std::min(top, std::max(0, img->dst_y));
        right = std::max(right, std::min(WIDTH, img->dst_x + img->w)); bottom = std::max(bottom, std::min(HEIGHT, img->dst_y + img->h));
    }
    int width = std::max(0, right - left), height = std::max(0, bottom - top);
    std::vector<jint> pixels(4 + width * height, 0);
    pixels[0] = left; pixels[1] = top; pixels[2] = width; pixels[3] = height;
    for (auto *img = images; img && width && height; img = img->next) {
        int r = img->color >> 24, g = (img->color >> 16) & 255, b = (img->color >> 8) & 255, opacity = 255 - (img->color & 255);
        int x0 = std::max(left, img->dst_x), x1 = std::min(right, img->dst_x + img->w);
        int y0 = std::max(top, img->dst_y), y1 = std::min(bottom, img->dst_y + img->h);
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            unsigned alpha = img->bitmap[(y-img->dst_y)*img->stride + x-img->dst_x] * opacity / 255;
            if (!alpha) continue;
            unsigned old = pixels[4 + (y-top)*width + x-left], oldAlpha = old >> 24;
            unsigned outAlpha = alpha + oldAlpha * (255-alpha) / 255;
            auto blend = [&](unsigned channel, unsigned color) { return (color*alpha + channel*oldAlpha*(255-alpha)/255) / outAlpha; };
            pixels[4 + (y-top)*width + x-left] = (outAlpha<<24) | (blend((old>>16)&255,r)<<16) | (blend((old>>8)&255,g)<<8) | blend(old&255,b);
        }
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(pixels.size()));
    if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(pixels.size()), pixels.data());
    return result;
}
JNI_METHOD(void) Java_com_kukuqi_tvbox_engine_player_subtitle_AssNative_freeTrack(JNIEnv *, jclass, jlong track) { if (track) api().ass_free_track_p(reinterpret_cast<ASS_Track *>(track)); }
JNI_METHOD(void) Java_com_kukuqi_tvbox_engine_player_subtitle_AssNative_close(JNIEnv *, jclass, jlong pointer) {
    auto *session = reinterpret_cast<Session *>(pointer); if (!session) return;
    api().ass_renderer_done_p(session->renderer); api().ass_library_done_p(session->library); delete session;
}

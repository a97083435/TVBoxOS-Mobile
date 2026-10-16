#include <jni.h>
#include <vector>
#include <string>
namespace node { int Start(int argc, char** argv); }
extern "C" JNIEXPORT jint JNICALL
Java_com_kukuqi_tvbox_runtime_nodejs_NodeRuntime_run(JNIEnv* env, jclass, jobjectArray args) {
    std::vector<std::string> values;
    for (int i = 0; i < env->GetArrayLength(args); i++) {
        auto arg = (jstring) env->GetObjectArrayElement(args, i);
        const char* value = env->GetStringUTFChars(arg, nullptr);
        values.emplace_back(value); env->ReleaseStringUTFChars(arg, value); env->DeleteLocalRef(arg);
    }
    // libuv requires contiguous argument storage (uv_setup_args).
    size_t total = 0; for (auto& value : values) total += value.size() + 1;
    std::vector<char> buffer(total); std::vector<char*> argv;
    char* cursor = buffer.data();
    for (auto& value : values) { argv.push_back(cursor); value.copy(cursor, value.size()); cursor[value.size()] = 0; cursor += value.size()+1; }
    argv.push_back(nullptr);
    return node::Start((int) values.size(), argv.data());
}

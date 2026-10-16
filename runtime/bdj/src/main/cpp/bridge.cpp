#include <jni.h>
#include <dlfcn.h>
#include <cstdlib>
#include <string>
#include <pthread.h>
static void* jvm_library;
extern "C" JNIEXPORT void JNICALL
Java_com_kukuqi_tvbox_runtime_bdj_BdjRuntime_configure(JNIEnv* env, jclass, jstring homeArg, jstring jarArg, jstring storageArg) {
    const char* home = env->GetStringUTFChars(homeArg, nullptr);
    const char* jar = env->GetStringUTFChars(jarArg, nullptr);
    const char* storage = env->GetStringUTFChars(storageArg, nullptr);
    setenv("JAVA_HOME", home, 1); setenv("JDK_HOME", home, 1);
    setenv("LIBBLURAY_CP", jar, 1);
    setenv("LIBBLURAY_PERSISTENT_ROOT", storage, 1);
    setenv("LIBBLURAY_CACHE_ROOT", storage, 1);
    setenv("BDJ_JVM_DISABLE_JIT", "1", 1);
    std::string library = std::string(home) + "/lib/aarch64/server/libjvm.so";
    void* handle = dlopen(library.c_str(), RTLD_NOW | RTLD_GLOBAL);
    jvm_library = handle;
    std::string error = handle ? "" : dlerror();
    if (handle && !dlsym(handle, "JNI_CreateJavaVM")) error = "JRE 缺少 JNI_CreateJavaVM";
    env->ReleaseStringUTFChars(homeArg, home); env->ReleaseStringUTFChars(jarArg, jar); env->ReleaseStringUTFChars(storageArg, storage);
    // Keep the JRE loaded for libbluray; unloading a JVM is not supported.
    if (!error.empty()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.c_str());
}

// Explicit diagnostic, called by instrumentation in a disposable host process.
struct Probe { std::string home, jar, result; bool ok = false; };
static void* probe_worker(void* data) {
    auto& probe = *static_cast<Probe*>(data);
    using Create = jint (*)(JavaVM**, void**, void*);
    auto create = reinterpret_cast<Create>(dlsym(jvm_library, "JNI_CreateJavaVM"));
    std::string home = "-Djava.home=" + probe.home;
    std::string classes = "-Djava.class.path=" + probe.jar;
    std::string native = "-Djava.library.path=" + probe.home + "/lib/aarch64";
    JavaVMOption options[] = {{const_cast<char*>("-Xrs"), nullptr}, {const_cast<char*>("-Xint"), nullptr},
        {const_cast<char*>("-Xmx64m"), nullptr}, {const_cast<char*>("-Djava.awt.headless=true"), nullptr},
        {home.data(), nullptr}, {classes.data(), nullptr}, {native.data(), nullptr}};
    // Android's JNI headers stop at 1.6; the separate OpenJDK VM accepts JNI 1.8.
    JavaVMInitArgs args = {0x00010008, 7, options, JNI_FALSE};
    JavaVM* vm = nullptr; JNIEnv* env = nullptr;
    jint status = create(&vm, reinterpret_cast<void**>(&env), &args);
    if (status != JNI_OK) { probe.result = "JVM 启动失败 " + std::to_string(status); return nullptr; }
    jclass system = env->FindClass("java/lang/System");
    jmethodID method = system ? env->GetStaticMethodID(system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;") : nullptr;
    auto version = method ? (jstring) env->CallStaticObjectMethod(system, method, env->NewStringUTF("java.version")) : nullptr;
    if (version && !env->ExceptionCheck()) {
        const char* value = env->GetStringUTFChars(version, nullptr); probe.result = value; env->ReleaseStringUTFChars(version, value); probe.ok = true;
    } else { env->ExceptionDescribe(); env->ExceptionClear(); probe.result = "JVM 无法执行 Java 方法"; }
    vm->DetachCurrentThread();
    return nullptr;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_kukuqi_tvbox_runtime_bdj_BdjRuntime_probe(JNIEnv* env, jclass, jstring home, jstring jar) {
    if (!jvm_library) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "请先加载 JRE"); return nullptr; }
    const char* home_value = env->GetStringUTFChars(home, nullptr);
    const char* jar_value = env->GetStringUTFChars(jar, nullptr);
    Probe probe{home_value, jar_value};
    env->ReleaseStringUTFChars(home, home_value); env->ReleaseStringUTFChars(jar, jar_value);
    pthread_t thread;
    if (pthread_create(&thread, nullptr, probe_worker, &probe) != 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "无法创建 JVM 测试线程"); return nullptr;
    }
    pthread_join(thread, nullptr);
    if (!probe.ok) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), probe.result.c_str()); return nullptr; }
    return env->NewStringUTF(probe.result.c_str());
}

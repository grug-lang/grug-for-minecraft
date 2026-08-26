#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include "adapter_shared.h"

// --- Grug Structs ---
struct grug_source_span { size_t offset; size_t line; };
struct grug_error_kind { uint8_t tag[4]; };
struct grug_error {
    struct grug_error_kind kind;
    char* function_name; char* file_path;
    struct { char* line; size_t len; } source;
    struct grug_source_span span;
    char* error_message; char* error_string;
};

struct grug_file_info {
    char* path; char* file_name; char* mod_name;
    char* entity_type; char* entity_name;
    uint64_t file_id;
    struct grug_error error;
};
struct grug_files_slice { struct grug_file_info* ptr; size_t len; };

struct grug_runtime_error_handler {
    void* user_data;
    void (*drop_fn)(void* user_data);
    void (*handler_fn)(
        void* data,
        uint32_t err_kind,
        char* reason_str,
        size_t reason_len,
        char* export_fn_name,
        size_t export_fn_name_len,
        char* script_path,
        size_t script_path_len
    );
};

struct grug_backend {
    void* obj;
    void* vtable;
};

struct grug_init_settings {
    const char* mod_api_path;
    size_t mod_api_path_len;
    const char* mods_dir_path;
    size_t mods_dir_path_len;
    struct grug_runtime_error_handler runtime_error_handler;
    struct grug_backend backend;
};

extern void* grug_init(struct grug_init_settings settings, struct grug_error* out_error);
extern struct grug_init_settings grug_default_settings(void);
extern struct grug_error* grug_all_host_fns_registered(void* state);
extern struct grug_files_slice grug_compile_all_files(void* state);

// --- JNI Caching (declared extern in adapter_shared.h) ---
jint jni_version;
JavaVM* jvm;
jclass game_functions_class;
static jmethodID jm_on_runtime_error;

// --- Runtime Error Handler Callback ---
static void runtime_error_callback(
    void* data,
    uint32_t err_kind,
    char* reason_str,
    size_t reason_len,
    char* export_fn_name,
    size_t export_fn_name_len,
    char* script_path,
    size_t script_path_len
) {
    if (!reason_str || reason_len == 0) return;

    char message[1024];
    snprintf(message, sizeof(message), "Error in %.*s (%.*s): %.*s", 
        (int)script_path_len, script_path, 
        (int)export_fn_name_len, export_fn_name, 
        (int)reason_len, reason_str);

    JNIEnv* env; FILL_ENV(env);
    jstring str = (*env)->NewStringUTF(env, message);
    (*env)->CallStaticVoidMethod(env, game_functions_class, jm_on_runtime_error, str);
    CHECK(env);
    (*env)->DeleteLocalRef(env, str);
}

// --- JNI Implementation ---
JNIEXPORT void JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_initGrugAdapter(JNIEnv *env, jclass clazz) {
    jni_version = (*env)->GetVersion(env);
    (*env)->GetJavaVM(env, &jvm);

    jclass local_gf = (*env)->FindClass(env, "com/example/examplemod/examplemod/grug/GameFunctions");
    if (!local_gf) return;

    game_functions_class = (*env)->NewGlobalRef(env, local_gf);

    jm_on_runtime_error = (*env)->GetStaticMethodID(env, game_functions_class, "onRuntimeError", "(Ljava/lang/String;)V");

    resolve_generated_method_ids(env, game_functions_class);
}

JNIEXPORT jlong JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeInit(JNIEnv *env, jclass clazz, jstring modApiPath, jstring modsDirPath) {
    const char *c_modApiPath = (*env)->GetStringUTFChars(env, modApiPath, NULL);
    const char *c_modsDirPath = (*env)->GetStringUTFChars(env, modsDirPath, NULL);

    struct grug_init_settings settings = grug_default_settings();
    settings.mod_api_path = c_modApiPath;
    settings.mod_api_path_len = strlen(c_modApiPath);
    settings.mods_dir_path = c_modsDirPath;
    settings.mods_dir_path_len = strlen(c_modsDirPath);
    settings.runtime_error_handler.user_data = NULL;
    settings.runtime_error_handler.drop_fn = NULL;
    settings.runtime_error_handler.handler_fn = runtime_error_callback;

    struct grug_error error;
    memset(&error, 0, sizeof(error));
    
    void *state = grug_init(settings, &error);

    (*env)->ReleaseStringUTFChars(env, modApiPath, c_modApiPath);
    (*env)->ReleaseStringUTFChars(env, modsDirPath, c_modsDirPath);

    if (!state) {
        jclass exceptionClass = (*env)->FindClass(env, "java/lang/RuntimeException");
        (*env)->ThrowNew(env, exceptionClass, error.error_string ? error.error_string : "Failed to initialize grug_state.");
        return 0;
    }

    register_generated_host_fns(state);
    struct grug_error* reg_err = grug_all_host_fns_registered(state);
    if (reg_err) {
        jclass exceptionClass = (*env)->FindClass(env, "java/lang/RuntimeException");
        (*env)->ThrowNew(env, exceptionClass, reg_err->error_string ? reg_err->error_string : "Not all host functions were registered.");
        return 0;
    }

    return (jlong)(intptr_t)state;
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeCompileAllFiles(JNIEnv *env, jclass clazz, jlong statePtr) {
    struct grug_files_slice files = grug_compile_all_files((void*)(intptr_t)statePtr);
    jclass fileInfoClass = (*env)->FindClass(env, "com/example/examplemod/examplemod/grug/FileInfo");
    jmethodID constructor = (*env)->GetMethodID(env, fileInfoClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V");
    jobjectArray array = (*env)->NewObjectArray(env, files.len, fileInfoClass, NULL);

    for (size_t i = 0; i < files.len; i++) {
        struct grug_file_info *info = &files.ptr[i];
        jstring path = (*env)->NewStringUTF(env, info->path);
        jstring file_name = (*env)->NewStringUTF(env, info->file_name);
        jstring mod_name = (*env)->NewStringUTF(env, info->mod_name);
        jstring entity_type = (*env)->NewStringUTF(env, info->entity_type);
        jstring entity_name = (*env)->NewStringUTF(env, info->entity_name);

        jstring error_str = NULL;
        if (info->file_id == UINT64_MAX && info->error.error_string) {
            error_str = (*env)->NewStringUTF(env, info->error.error_string);
        }

        jobject obj = (*env)->NewObject(env, fileInfoClass, constructor, path, file_name, mod_name, entity_type, entity_name, (jlong)info->file_id, error_str);
        (*env)->SetObjectArrayElement(env, array, i, obj);

        (*env)->DeleteLocalRef(env, path); (*env)->DeleteLocalRef(env, file_name);
        (*env)->DeleteLocalRef(env, mod_name); (*env)->DeleteLocalRef(env, entity_type);
        (*env)->DeleteLocalRef(env, entity_name); (*env)->DeleteLocalRef(env, obj);
        if (error_str) (*env)->DeleteLocalRef(env, error_str);
    }
    return array;
}

extern void* grug_create_entity(void* state, uint64_t file_id);
extern void* grug_entity_get_data(void* state, void* entity_handle);
extern uint64_t grug_get_on_fn_id(void* state, const char* entity_type, const char* on_fn_name);
extern bool grug_call_export_fn(void* state, void* entity_handle, uint64_t on_fn_id, const union grug_value* args, size_t args_len);
extern void grug_deinit_entity(void* state, void* entity_handle);

struct grug_entity {
    uint64_t id;
    uint64_t file_id;
    void* data;
};

JNIEXPORT jlong JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeCreateEntity(JNIEnv *env, jclass clazz, jlong statePtr, jlong fileId) {
    return (jlong)(intptr_t)grug_create_entity((void*)(intptr_t)statePtr, (uint64_t)fileId);
}

JNIEXPORT jlong JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeGetEntityId(JNIEnv *env, jclass clazz, jlong statePtr, jlong entityHandle) {
    struct grug_entity* ent = grug_entity_get_data((void*)(intptr_t)statePtr, (void*)(intptr_t)entityHandle);
    return ent ? (jlong)ent->id : -1;
}

JNIEXPORT jlong JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeGetExportFnId(JNIEnv *env, jclass clazz, jlong statePtr, jstring entityType, jstring fnName) {
    const char *c_entityType = (*env)->GetStringUTFChars(env, entityType, NULL);
    const char *c_fnName = (*env)->GetStringUTFChars(env, fnName, NULL);

    uint64_t id = grug_get_on_fn_id((void*)(intptr_t)statePtr, c_entityType, c_fnName);

    (*env)->ReleaseStringUTFChars(env, entityType, c_entityType);
    (*env)->ReleaseStringUTFChars(env, fnName, c_fnName);
    return (jlong)id;
}

JNIEXPORT jboolean JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeCallExportFn(JNIEnv *env, jclass clazz, jlong statePtr, jlong entityHandle, jlong exportFnId) {
    return grug_call_export_fn((void*)(intptr_t)statePtr, (void*)(intptr_t)entityHandle, (uint64_t)exportFnId, NULL, 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeDestroyEntity(JNIEnv *env, jclass clazz, jlong statePtr, jlong entityHandle) {
    grug_deinit_entity((void*)(intptr_t)statePtr, (void*)(intptr_t)entityHandle);
}

extern struct grug_files_slice grug_update(void* state);

JNIEXPORT jobjectArray JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeUpdate(JNIEnv *env, jclass clazz, jlong statePtr) {
    struct grug_files_slice files = grug_update((void*)(intptr_t)statePtr);
    jclass fileInfoClass = (*env)->FindClass(env, "com/example/examplemod/examplemod/grug/FileInfo");
    jmethodID constructor = (*env)->GetMethodID(env, fileInfoClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;)V");
    jobjectArray array = (*env)->NewObjectArray(env, files.len, fileInfoClass, NULL);

    for (size_t i = 0; i < files.len; i++) {
        struct grug_file_info *info = &files.ptr[i];
        jstring path = (*env)->NewStringUTF(env, info->path);
        jstring file_name = (*env)->NewStringUTF(env, info->file_name);
        jstring mod_name = (*env)->NewStringUTF(env, info->mod_name);
        jstring entity_type = (*env)->NewStringUTF(env, info->entity_type);
        jstring entity_name = (*env)->NewStringUTF(env, info->entity_name);

        jstring error_str = NULL;
        if (info->file_id == UINT64_MAX && info->error.error_string) {
            error_str = (*env)->NewStringUTF(env, info->error.error_string);
        }

        jobject obj = (*env)->NewObject(env, fileInfoClass, constructor, path, file_name, mod_name, entity_type, entity_name, (jlong)info->file_id, error_str);
        (*env)->SetObjectArrayElement(env, array, i, obj);

        (*env)->DeleteLocalRef(env, path); (*env)->DeleteLocalRef(env, file_name);
        (*env)->DeleteLocalRef(env, mod_name); (*env)->DeleteLocalRef(env, entity_type);
        (*env)->DeleteLocalRef(env, entity_name); (*env)->DeleteLocalRef(env, obj);
        if (error_str) (*env)->DeleteLocalRef(env, error_str);
    }
    return array;
}

struct grug_str_slice { char** ptr; size_t len; };

extern struct grug_str_slice grug_get_updated_resources(void* state);

JNIEXPORT jobjectArray JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeGetUpdatedResources(JNIEnv *env, jclass clazz, jlong statePtr) {
    struct grug_str_slice resources = grug_get_updated_resources((void*)(intptr_t)statePtr);
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    jobjectArray array = (*env)->NewObjectArray(env, resources.len, stringClass, NULL);

    for (size_t i = 0; i < resources.len; i++) {
        jstring str = (*env)->NewStringUTF(env, resources.ptr[i]);
        (*env)->SetObjectArrayElement(env, array, i, str);
        (*env)->DeleteLocalRef(env, str);
    }
    return array;
}

extern void grug_set_runtime_error(void* state, const char* message);

JNIEXPORT void JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_gameFunctionErrorHappened(JNIEnv *env, jclass clazz, jlong statePtr, jstring message) {
    const char *c_message = (*env)->GetStringUTFChars(env, message, NULL);
    
    grug_set_runtime_error((void*)(intptr_t)statePtr, c_message);
    
    (*env)->ReleaseStringUTFChars(env, message, c_message);
}

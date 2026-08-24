#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>

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

union grug_value {
    double _number;
    bool _bool;
    const char* _string;
    uint64_t _id;
};
typedef union grug_value (*host_fn)(void* gst, const union grug_value[]);

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
    const char* mods_dir_path;
    struct grug_runtime_error_handler runtime_error_handler;
    struct grug_backend backend;
};

extern void* grug_init(const struct grug_init_settings* settings, struct grug_error* out_error);
extern struct grug_init_settings grug_default_settings(void);
extern struct grug_error* grug_register_host_fn(void* state, const char* fn_name, host_fn func);
extern struct grug_error* grug_register_method(void* state, const char* class_name, const char* fn_name, host_fn func);
extern struct grug_error* grug_all_host_fns_registered(void* state);
extern struct grug_files_slice grug_compile_all_files(void* state);

// --- JNI Caching ---
static jint jni_version;
static JavaVM* jvm;
static jclass game_functions_class;
static jmethodID jm_on_runtime_error;

static jmethodID jm_get_block_entity_level, jm_get_block_pos_of_block_entity;
static jmethodID jm_BlockPos_above_n, jm_BlockPos_center;
static jmethodID jm_Vec3_x, jm_Vec3_y, jm_Vec3_z;
static jmethodID jm_item, jm_item_entity, jm_ItemEntity_entity, jm_item_stack;
static jmethodID jm_resource_location, jm_Entity_set_delta_movement, jm_Entity_spawn, jm_vec3, jm_vec3_zero;

#define FILL_ENV(env) (*jvm)->GetEnv(jvm, (void**)&env, jni_version)
#define CHECK(env) if ((*env)->ExceptionCheck(env)) { \
    (*env)->ExceptionDescribe(env); \
    (*env)->ExceptionClear(env); \
}

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

// --- Host Function Wrappers ---
union grug_value host_get_block_entity_level(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_get_block_entity_level, (jlong)args[0]._id);
    CHECK(env);
    return (union grug_value){._id = (uint64_t)res};
}
union grug_value host_get_block_pos_of_block_entity(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_get_block_pos_of_block_entity, (jlong)args[0]._id);
    CHECK(env);
    return (union grug_value){._id = (uint64_t)res};
}
union grug_value host_BlockPos_above_n(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_BlockPos_above_n, (jlong)args[0]._id, (jint)args[1]._number);
    CHECK(env);
    return (union grug_value){._id = (uint64_t)res};
}
union grug_value host_BlockPos_center(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_BlockPos_center, (jlong)args[0]._id);
    CHECK(env);
    return (union grug_value){._id = (uint64_t)res};
}
union grug_value host_Vec3_x(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jfloat res = (*env)->CallStaticFloatMethod(env, game_functions_class, jm_Vec3_x, (jlong)args[0]._id);
    CHECK(env);
    return (union grug_value){._number = (double)res};
}
union grug_value host_Vec3_y(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jfloat res = (*env)->CallStaticFloatMethod(env, game_functions_class, jm_Vec3_y, (jlong)args[0]._id);
    CHECK(env);
    return (union grug_value){._number = (double)res};
}
union grug_value host_Vec3_z(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jfloat res = (*env)->CallStaticFloatMethod(env, game_functions_class, jm_Vec3_z, (jlong)args[0]._id);
    CHECK(env);
    return (union grug_value){._number = (double)res};
}
union grug_value host_item(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_item, (jlong)args[0]._id);
    CHECK(env);
    return (union grug_value){._id = (uint64_t)res};
}
union grug_value host_item_entity(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_item_entity, (jlong)args[0]._id, (jfloat)args[1]._number, (jfloat)args[2]._number, (jfloat)args[3]._number, (jlong)args[4]._id);
    CHECK(env);
    return (union grug_value){._id = (uint64_t)res};
}
union grug_value host_ItemEntity_entity(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_ItemEntity_entity, (jlong)args[0]._id);
    CHECK(env);
    return (union grug_value){._id = (uint64_t)res};
}
union grug_value host_item_stack(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_item_stack, (jlong)args[0]._id);
    CHECK(env);
    return (union grug_value){._id = (uint64_t)res};
}
union grug_value host_resource_location(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jstring str = (*env)->NewStringUTF(env, args[0]._string);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_resource_location, str);
    CHECK(env);
    (*env)->DeleteLocalRef(env, str);
    return (union grug_value){._id = (uint64_t)res};
}
union grug_value host_Entity_set_delta_movement(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    (*env)->CallStaticVoidMethod(env, game_functions_class, jm_Entity_set_delta_movement, (jlong)args[0]._id, (jlong)args[1]._id);
    CHECK(env);
    return (union grug_value){0};
}
union grug_value host_Entity_spawn(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    (*env)->CallStaticVoidMethod(env, game_functions_class, jm_Entity_spawn, (jlong)args[0]._id, (jlong)args[1]._id);
    CHECK(env);
    return (union grug_value){0};
}
union grug_value host_vec3(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_vec3, (jfloat)args[0]._number, (jfloat)args[1]._number, (jfloat)args[2]._number);
    CHECK(env);
    return (union grug_value){._id = (uint64_t)res};
}
union grug_value host_vec3_zero(void* gst, const union grug_value args[]) {
    JNIEnv* env; FILL_ENV(env);
    jlong res = (*env)->CallStaticLongMethod(env, game_functions_class, jm_vec3_zero);
    CHECK(env);
    return (union grug_value){._id = (uint64_t)res};
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

    jm_get_block_entity_level = (*env)->GetStaticMethodID(env, game_functions_class, "get_block_entity_level", "(J)J");
    jm_get_block_pos_of_block_entity = (*env)->GetStaticMethodID(env, game_functions_class, "get_block_pos_of_block_entity", "(J)J");
    jm_BlockPos_above_n = (*env)->GetStaticMethodID(env, game_functions_class, "BlockPos_above_n", "(JI)J");
    jm_BlockPos_center = (*env)->GetStaticMethodID(env, game_functions_class, "BlockPos_center", "(J)J");
    jm_Vec3_x = (*env)->GetStaticMethodID(env, game_functions_class, "Vec3_x", "(J)F");
    jm_Vec3_y = (*env)->GetStaticMethodID(env, game_functions_class, "Vec3_y", "(J)F");
    jm_Vec3_z = (*env)->GetStaticMethodID(env, game_functions_class, "Vec3_z", "(J)F");
    jm_item = (*env)->GetStaticMethodID(env, game_functions_class, "item", "(J)J");
    jm_item_entity = (*env)->GetStaticMethodID(env, game_functions_class, "item_entity", "(JFFFJ)J");
    jm_ItemEntity_entity = (*env)->GetStaticMethodID(env, game_functions_class, "ItemEntity_entity", "(J)J");
    jm_item_stack = (*env)->GetStaticMethodID(env, game_functions_class, "item_stack", "(J)J");
    jm_resource_location = (*env)->GetStaticMethodID(env, game_functions_class, "resource_location", "(Ljava/lang/String;)J");
    jm_Entity_set_delta_movement = (*env)->GetStaticMethodID(env, game_functions_class, "Entity_set_delta_movement", "(JJ)V");
    jm_Entity_spawn = (*env)->GetStaticMethodID(env, game_functions_class, "Entity_spawn", "(JJ)V");
    jm_vec3 = (*env)->GetStaticMethodID(env, game_functions_class, "vec3", "(FFF)J");
    jm_vec3_zero = (*env)->GetStaticMethodID(env, game_functions_class, "vec3_zero", "()J");
}

JNIEXPORT jlong JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeInit(JNIEnv *env, jclass clazz, jstring modApiPath, jstring modsDirPath) {
    const char *c_modApiPath = (*env)->GetStringUTFChars(env, modApiPath, NULL);
    const char *c_modsDirPath = (*env)->GetStringUTFChars(env, modsDirPath, NULL);
    char *safe_modApiPath = strdup(c_modApiPath);
    char *safe_modsDirPath = strdup(c_modsDirPath);
    (*env)->ReleaseStringUTFChars(env, modApiPath, c_modApiPath);
    (*env)->ReleaseStringUTFChars(env, modsDirPath, c_modsDirPath);

    struct grug_init_settings settings = grug_default_settings();
    settings.mod_api_path = safe_modApiPath;
    settings.mods_dir_path = safe_modsDirPath;
    settings.runtime_error_handler.user_data = NULL;
    settings.runtime_error_handler.drop_fn = NULL;
    settings.runtime_error_handler.handler_fn = runtime_error_callback;

    struct grug_error error;
    memset(&error, 0, sizeof(error));
    void *state = grug_init(&settings, &error);

    if (!state) {
        jclass exceptionClass = (*env)->FindClass(env, "java/lang/RuntimeException");
        (*env)->ThrowNew(env, exceptionClass, error.error_string ? error.error_string : "Failed to initialize grug_state.");
        return 0;
    }

    grug_register_host_fn(state, "get_block_entity_level", host_get_block_entity_level);
    grug_register_host_fn(state, "get_block_pos_of_block_entity", host_get_block_pos_of_block_entity);
    grug_register_host_fn(state, "item", host_item);
    grug_register_host_fn(state, "item_entity", host_item_entity);
    grug_register_host_fn(state, "item_stack", host_item_stack);
    grug_register_host_fn(state, "resource_location", host_resource_location);
    grug_register_host_fn(state, "vec3", host_vec3);
    grug_register_host_fn(state, "vec3_zero", host_vec3_zero);

    grug_register_method(state, "BlockPos", "above_n", host_BlockPos_above_n);
    grug_register_method(state, "BlockPos", "center", host_BlockPos_center);
    grug_register_method(state, "Vec3", "x", host_Vec3_x);
    grug_register_method(state, "Vec3", "y", host_Vec3_y);
    grug_register_method(state, "Vec3", "z", host_Vec3_z);
    grug_register_method(state, "ItemEntity", "entity", host_ItemEntity_entity);
    grug_register_method(state, "Entity", "set_delta_movement", host_Entity_set_delta_movement);
    grug_register_method(state, "Entity", "spawn", host_Entity_spawn);

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

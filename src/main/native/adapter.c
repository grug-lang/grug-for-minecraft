#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>

struct grug_source_span {
    size_t offset;
    size_t line;
};

struct grug_error_kind {
    uint8_t tag[4];
};

struct grug_error {
    struct grug_error_kind kind;
    char* function_name;
    char* file_path;
    struct {
        char* line;
        size_t len;
    } source;
    struct grug_source_span span;
    char* error_message;
    char* error_string;
};

struct grug_runtime_error_handler {
    void* user_data;
    void (*drop_fn)(void*);
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

struct grug_backend_vtable {
    void* compile_script;
    void* init_entity;
    void* clear_entities;
    void* entity_data;
    void* call_on_function_raw;
    void* call_on_function;
    void* drop;
};

struct grug_backend {
    void* obj;
    struct grug_backend_vtable* vtable;
};

struct grug_init_settings {
    char const* mod_api_path;
    char const* mods_dir_path;
    struct grug_runtime_error_handler runtime_error_handler;
    struct grug_backend backend;
};

extern struct grug_state* grug_init(struct grug_init_settings *settings, struct grug_error* out_error);
extern struct grug_init_settings grug_default_settings(void);

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

    struct grug_error error;
    memset(&error, 0, sizeof(error));

    struct grug_state *state = grug_init(&settings, &error);

    if (!state) {
        jclass exceptionClass = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (error.error_string) {
            (*env)->ThrowNew(env, exceptionClass, error.error_string);
        } else {
            (*env)->ThrowNew(env, exceptionClass, "Failed to initialize grug_state.");
        }
        return 0; 
    }

    return (jlong)(intptr_t)state;
}

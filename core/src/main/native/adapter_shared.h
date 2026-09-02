#pragma once
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>

union grug_value {
    double _number;
    bool _bool;
    const char* _string;
    uint64_t _id;
};
typedef union grug_value (*host_fn)(void* gst, const union grug_value[]);

struct grug_error;

extern JavaVM* jvm;
extern jint jni_version;
extern jclass game_functions_class;

#define FILL_ENV(env) (*jvm)->GetEnv(jvm, (void**)&env, jni_version)
#define CHECK(env) if ((*env)->ExceptionCheck(env)) { \
    (*env)->ExceptionDescribe(env); \
    (*env)->ExceptionClear(env); \
}

extern struct grug_error* grug_register_host_fn(void* state, const char* fn_name, host_fn func);
extern struct grug_error* grug_register_method(void* state, const char* class_name, const char* fn_name, host_fn func);

// Implemented in the generated file (see generate.py)
void resolve_generated_method_ids(JNIEnv* env, jclass game_functions_class);
void register_generated_host_fns(void* state);

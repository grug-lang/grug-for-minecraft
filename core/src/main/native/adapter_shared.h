#pragma once
#include <jni.h>
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

enum grug_type_enum {
    GRUG_TYPE_ENUM_VOID,
    GRUG_TYPE_ENUM_BOOL,
    GRUG_TYPE_ENUM_NUMBER,
    GRUG_TYPE_ENUM_STRING,
    GRUG_TYPE_ENUM_ID,
    GRUG_TYPE_ENUM_RESOURCE,
    GRUG_TYPE_ENUM_ENTITY
};

struct grug_type {
    uint32_t type;
    union {
        struct {
            char* name;
            struct grug_type* generics;
            size_t generics_len;
        } id;
        char* resource_extension;
        char* entity_type;
    } data;
};

union grug_value {
    double _number;
    bool _bool;
    const char* _string;
    uint64_t _id;
};
typedef union grug_value (*host_fn)(void* gst, const union grug_value args[], const struct grug_type generics[]);

struct grug_error;

extern JavaVM* jvm;
extern jint jni_version;
extern jclass host_functions_class;

#define FILL_ENV(env) (*jvm)->GetEnv(jvm, (void**)&env, jni_version)
// For the calls this adapter makes itself: an exception there is its own bug rather than a script's,
// and raising a runtime error from inside the runtime error reporting that is already running could
// recurse, so it describes the stack and carries on.
#define CHECK(env) if ((*env)->ExceptionCheck(env)) { \
    (*env)->ExceptionDescribe(env); \
    (*env)->ExceptionClear(env); \
}

// The exception check of a generated host function wrapper (see generate.py). A host function that
// throws a Java exception is a defect in this side rather than in the script, and grug-rs cannot
// unwind the exception through its Rust frames, so the wrapper hands it back to Java, which turns it
// into a grug runtime error: the script stops at the call that made it and the test that made it
// fails by name. Clearing it and carrying on with a zero value, which is what the wrapper did before
// #890, let the test report PASS and left the log with a stack that named no test.
void grug_report_host_function_threw(JNIEnv* env, void* state);

#define HOST_CHECK(env, state) if ((*env)->ExceptionCheck(env)) { \
    grug_report_host_function_threw(env, state); \
}

extern struct grug_error* grug_register_host_fn(void* state, const char* fn_name, host_fn func);
extern struct grug_error* grug_register_host_method(void* state, const char* class_name, const char* fn_name, host_fn func);

// Implemented in the generated file (see generate.py)
void resolve_generated_method_ids(JNIEnv* env, jclass host_functions_class);
void register_generated_host_fns(void* state);

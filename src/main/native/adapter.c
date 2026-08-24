#include <jni.h>

// Manually declared: grug.h is currently out of sync with the real symbols
// exported by gruggers/src/capi.rs. Option<Box<CState>> is ABI-equivalent
// to a nullable pointer, so this is safe to call with NULL.
extern void grug_deinit(void *state);

JNIEXPORT jboolean JNICALL
Java_com_example_examplemod_examplemod_grug_Grug_nativeGrugPing(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    grug_deinit(NULL); // no-op; just proves libgruggers.a is linked in
    return JNI_TRUE;
}

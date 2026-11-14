/*
 * JNI bridge between io.homeassistant.companion.android.tailcat.LibTailcat and libtailcat, the C
 * API of Tailcat (https://github.com/tailscale/tailcat, cmd/libtailcat/tailcat.h, ABI version 1).
 *
 * Only the client side the app needs is bridged: creating a client, dialing a TCP stream through
 * the tunnel, stream I/O, cancellation tokens and closing handles. Every call blocks, so the Kotlin
 * side only calls these functions from background threads.
 *
 * Failures are thrown as TailcatNativeException(status, message). libtailcat keeps secrets (the
 * server address and private keys) out of its error messages, and nothing here logs anything.
 */

#include <jni.h>
#include <stdlib.h>

#include "tailcat.h"

#define EXCEPTION_CLASS "io/homeassistant/companion/android/tailcat/TailcatNativeException"
#define JNI_FUNCTION(name) Java_io_homeassistant_companion_android_tailcat_LibTailcat_##name

/* Throws TailcatNativeException(status, message) and releases message. */
static void throw_status(JNIEnv *env, int32_t status, char *message) {
    jclass exception_class = (*env)->FindClass(env, EXCEPTION_CLASS);
    if (exception_class == NULL) {
        tc_free(message);
        return; /* NoClassDefFoundError is pending. */
    }
    jmethodID constructor = (*env)->GetMethodID(env, exception_class, "<init>", "(ILjava/lang/String;)V");
    if (constructor == NULL) {
        tc_free(message);
        return;
    }
    jstring text = (*env)->NewStringUTF(env, message != NULL ? message : "");
    tc_free(message);
    if (text == NULL) {
        return; /* OutOfMemoryError is pending. */
    }
    jobject exception = (*env)->NewObject(env, exception_class, constructor, (jint)status, text);
    if (exception != NULL) {
        (*env)->Throw(env, (jthrowable)exception);
    }
}

/* Throws when status is not TC_OK; returns whether it threw. */
static int check(JNIEnv *env, int32_t status, char *message) {
    if (status == TC_OK) {
        tc_free(message);
        return 0;
    }
    throw_status(env, status, message);
    return 1;
}

static int check_range(JNIEnv *env, jbyteArray buffer, jint offset, jint length) {
    jsize size = (*env)->GetArrayLength(env, buffer);
    if (offset < 0 || length < 0 || offset > size - length) {
        throw_status(env, TC_INVALID_ARGUMENT, NULL);
        return 1;
    }
    return 0;
}

JNIEXPORT jint JNICALL JNI_FUNCTION(abiVersion)(JNIEnv *env, jobject self) {
    (void)env;
    (void)self;
    return (jint)tc_abi_version();
}

JNIEXPORT jlong JNICALL JNI_FUNCTION(clientNew)(JNIEnv *env, jobject self, jstring config_json) {
    (void)self;
    const char *config = (*env)->GetStringUTFChars(env, config_json, NULL);
    if (config == NULL) {
        return 0;
    }
    tc_handle client = 0;
    char *error = NULL;
    int32_t status = tc_client_new((char *)config, &client, &error);
    (*env)->ReleaseStringUTFChars(env, config_json, config);
    if (check(env, status, error)) {
        return 0;
    }
    return (jlong)client;
}

JNIEXPORT jlong JNICALL JNI_FUNCTION(clientDial)(JNIEnv *env, jobject self, jlong client, jlong token, jint port) {
    (void)self;
    if (port < 1 || port > 65535) {
        throw_status(env, TC_INVALID_ARGUMENT, NULL);
        return 0;
    }
    tc_handle connection = 0;
    char *error = NULL;
    int32_t status = tc_client_dial((tc_handle)client, (tc_token)token, (uint16_t)port, TC_TCP, &connection, &error);
    if (check(env, status, error)) {
        return 0;
    }
    return (jlong)connection;
}

/* Returns the number of bytes read, or -1 at end of stream. */
JNIEXPORT jint JNICALL JNI_FUNCTION(connRead)(
    JNIEnv *env, jobject self, jlong connection, jlong token, jbyteArray buffer, jint offset, jint length) {
    (void)self;
    if (check_range(env, buffer, offset, length)) {
        return 0;
    }
    if (length == 0) {
        return 0;
    }
    void *bytes = malloc((size_t)length);
    if (bytes == NULL) {
        throw_status(env, TC_FAILURE, NULL);
        return 0;
    }
    size_t count = 0;
    char *error = NULL;
    int32_t status = tc_conn_read((tc_handle)connection, (tc_token)token, bytes, (size_t)length, &count, &error);
    if (count > 0) {
        /* Bytes delivered before a failure are handed over first; the next read reports it again. */
        (*env)->SetByteArrayRegion(env, buffer, offset, (jsize)count, (const jbyte *)bytes);
        free(bytes);
        tc_free(error);
        return (jint)count;
    }
    free(bytes);
    if (status == TC_EOF) {
        tc_free(error);
        return -1;
    }
    check(env, status, error);
    return 0;
}

/* Writes all length bytes or throws. */
JNIEXPORT void JNICALL JNI_FUNCTION(connWrite)(
    JNIEnv *env, jobject self, jlong connection, jlong token, jbyteArray buffer, jint offset, jint length) {
    (void)self;
    if (check_range(env, buffer, offset, length)) {
        return;
    }
    if (length == 0) {
        return;
    }
    jbyte *bytes = malloc((size_t)length);
    if (bytes == NULL) {
        throw_status(env, TC_FAILURE, NULL);
        return;
    }
    (*env)->GetByteArrayRegion(env, buffer, offset, length, bytes);
    size_t written = 0;
    while (written < (size_t)length) {
        size_t count = 0;
        char *error = NULL;
        int32_t status = tc_conn_write(
            (tc_handle)connection, (tc_token)token, bytes + written, (size_t)length - written, &count, &error);
        written += count;
        if (status != TC_OK) {
            free(bytes);
            check(env, status, error);
            return;
        }
        tc_free(error);
    }
    free(bytes);
}

JNIEXPORT void JNICALL JNI_FUNCTION(connCloseWrite)(JNIEnv *env, jobject self, jlong connection, jlong token) {
    (void)self;
    char *error = NULL;
    check(env, tc_conn_close_write((tc_handle)connection, (tc_token)token, &error), error);
}

JNIEXPORT jlong JNICALL JNI_FUNCTION(tokenNew)(JNIEnv *env, jobject self, jlong timeout_nanos) {
    (void)self;
    tc_token token = 0;
    char *error = NULL;
    if (check(env, tc_token_new((int64_t)timeout_nanos, &token, &error), error)) {
        return 0;
    }
    return (jlong)token;
}

JNIEXPORT void JNICALL JNI_FUNCTION(tokenCancel)(JNIEnv *env, jobject self, jlong token) {
    (void)env;
    (void)self;
    /* Cancelling a token that is already released is harmless; nothing to report. */
    tc_token_cancel((tc_token)token, NULL);
}

JNIEXPORT void JNICALL JNI_FUNCTION(close)(JNIEnv *env, jobject self, jlong handle) {
    (void)env;
    (void)self;
    /* Closing retires the handle even when shutdown reports an error, so the status is not useful. */
    tc_close((tc_handle)handle, NULL);
}

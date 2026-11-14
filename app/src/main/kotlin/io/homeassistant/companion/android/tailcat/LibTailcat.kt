package io.homeassistant.companion.android.tailcat

import android.os.StrictMode
import timber.log.Timber

/** libtailcat C ABI version this bridge is written against. */
private const val SUPPORTED_ABI_VERSION = 1

/**
 * JNI bindings to libtailcat (the C API of https://github.com/tailscale/tailcat), bridged by
 * `app/tailcat/tailcat_jni.c`. The native libraries are built by `app/tailcat/build-libtailcat.sh`
 * and are not part of the repository: when they are missing, [isAvailable] is `false` and Tailcat
 * is reported as unavailable instead of crashing.
 *
 * Every call blocks until the network answers, so none may run on the main thread. Handles are
 * opaque `Long`s released with [close]. Failures throw [TailcatNativeException].
 */
internal object LibTailcat {

    /** Token value meaning "no deadline and no explicit cancellation" (`TC_NO_CANCEL`). */
    const val NO_CANCEL = 0L

    /** Whether the native libraries are packaged and speak the supported ABI. Loads them once. */
    val isAvailable: Boolean by lazy {
        // Loading reads the libraries from disk once. The first URL resolution of a paired server
        // may happen on the main thread, so that one read is allowed there.
        val policy = StrictMode.allowThreadDiskReads()
        try {
            System.loadLibrary("tailcat")
            System.loadLibrary("tailcat_jni")
            val version = abiVersion()
            (version == SUPPORTED_ABI_VERSION).also { supported ->
                if (!supported) Timber.e("Unsupported libtailcat ABI version $version")
            }
        } catch (e: UnsatisfiedLinkError) {
            Timber.w(e, "libtailcat is not available in this build")
            false
        } finally {
            StrictMode.setThreadPolicy(policy)
        }
    }

    external fun abiVersion(): Int

    /** Creates a client from `{"address": …, "key": …}`; network startup is deferred to the first dial. */
    external fun clientNew(configJson: String): Long

    /** Dials a TCP stream to [port] on the client's peer. */
    external fun clientDial(client: Long, token: Long, port: Int): Long

    /** Reads up to [length] bytes into [buffer]; returns the count, or -1 at end of stream. */
    external fun connRead(connection: Long, token: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Writes all [length] bytes of [buffer]. */
    external fun connWrite(connection: Long, token: Long, buffer: ByteArray, offset: Int, length: Int)

    /** Half-closes the stream: sends FIN while reading stays possible. */
    external fun connCloseWrite(connection: Long, token: Long)

    /** Creates a cancellation token expiring after [timeoutNanos] (-1 for none). */
    external fun tokenNew(timeoutNanos: Long): Long

    /** Cancels calls using [token]; safe from any thread. */
    external fun tokenCancel(token: Long)

    /** Releases [handle], interrupting calls on it (for a client: its streams too). */
    external fun close(handle: Long)
}

/**
 * A libtailcat call failed with [status] (one of the `TC_*` codes). The message comes from
 * libtailcat, which keeps secrets out of its error messages.
 */
class TailcatNativeException(val status: Int, message: String) : Exception("libtailcat status $status: $message") {
    /** Whether the call ran out of time or was cancelled, as opposed to failing outright. */
    val isTimeout: Boolean
        get() = status == STATUS_TIMEOUT || status == STATUS_CANCELLED

    private companion object {
        const val STATUS_TIMEOUT = 3
        const val STATUS_CANCELLED = 4
    }
}

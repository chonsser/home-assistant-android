package io.homeassistant.companion.android.tailcat

import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** [TailcatClient] backed by libtailcat. Creating it does not touch the network yet. */
internal class LibTailcatClient(config: TailcatConfig) : TailcatClient {

    private val handle: Long = try {
        LibTailcat.clientNew(
            buildJsonObject {
                put("address", config.address)
                put("key", config.clientPrivateKey)
            }.toString(),
        )
    } catch (e: TailcatNativeException) {
        throw IOException("Failed to create a Tailcat client", e)
    }
    private val closed = AtomicBoolean(false)

    override fun dial(port: Int, timeout: Duration): TailcatStream {
        val token = LibTailcat.tokenNew(timeout.inWholeNanoseconds.coerceAtLeast(0))
        try {
            return LibTailcatStream(LibTailcat.clientDial(handle, token, port))
        } catch (e: TailcatNativeException) {
            throw IOException("Failed to dial port $port through Tailcat", e)
        } finally {
            LibTailcat.close(token)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            LibTailcat.close(handle)
        }
    }
}

/** [TailcatStream] over a libtailcat connection handle. Closing interrupts blocked reads and writes. */
private class LibTailcatStream(private val handle: Long) : TailcatStream {
    private val closed = AtomicBoolean(false)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = io {
        LibTailcat.connRead(handle, LibTailcat.NO_CANCEL, buffer, offset, length)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) = io {
        LibTailcat.connWrite(handle, LibTailcat.NO_CANCEL, buffer, offset, length)
    }

    override fun closeWrite() = io { LibTailcat.connCloseWrite(handle, LibTailcat.NO_CANCEL) }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            LibTailcat.close(handle)
        }
    }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: TailcatNativeException) {
        throw IOException("Tailcat stream failed", e)
    }
}

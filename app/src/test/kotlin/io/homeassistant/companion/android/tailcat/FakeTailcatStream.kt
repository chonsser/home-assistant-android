package io.homeassistant.companion.android.tailcat

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

/** A one-way in-memory byte pipe: reads block until data arrives or the pipe is closed (EOF). */
class BytePipe {
    private val buffer = java.io.ByteArrayOutputStream()
    private var readPosition = 0
    private var isClosed = false
    private val lock = Object()

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) = synchronized(lock) {
        if (isClosed) throw IOException("Pipe closed")
        buffer.write(bytes, offset, length)
        lock.notifyAll()
    }

    fun close() = synchronized(lock) {
        isClosed = true
        lock.notifyAll()
    }

    /** Reads up to [length] bytes, or returns -1 once closed and drained. */
    fun read(target: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        while (buffer.size() == readPosition && !isClosed) lock.wait()
        val available = buffer.size() - readPosition
        if (available == 0) return -1
        val count = minOf(available, length)
        System.arraycopy(buffer.toByteArray(), readPosition, target, offset, count)
        readPosition += count
        count
    }

    /** Reads everything until EOF. */
    fun readAll(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(1024)
        while (true) {
            val count = read(chunk, 0, chunk.size)
            if (count < 0) return out.toByteArray()
            out.write(chunk, 0, count)
        }
    }
}

/**
 * An in-memory tunnel stream: what the proxy writes arrives in [toServer], and what the test writes
 * to [toProxy] is what the proxy reads.
 */
class FakeTailcatStream : TailcatStream {
    val toServer = BytePipe()
    val toProxy = BytePipe()

    @Volatile
    var isClosed = false
        private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (isClosed) throw IOException("Stream closed")
        return toProxy.read(buffer, offset, length)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) = toServer.write(buffer, offset, length)

    override fun closeWrite() = toServer.close()

    override fun close() {
        isClosed = true
        toServer.close()
        toProxy.close()
    }
}

/** A dialer handing out [FakeTailcatStream]s, or failing while [failure] is set. */
class FakeTailcatDialer : TailcatDialer {
    val streams = CopyOnWriteArrayList<FakeTailcatStream>()
    val dialedPorts = CopyOnWriteArrayList<Int>()

    @Volatile
    var failure: IOException? = null

    override fun dial(port: Int, timeout: Duration): TailcatStream {
        dialedPorts += port
        failure?.let { throw it }
        return FakeTailcatStream().also { streams += it }
    }
}

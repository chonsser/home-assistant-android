package io.homeassistant.companion.android.tailcat

import java.io.Closeable
import java.io.IOException
import kotlin.time.Duration

/** A TCP stream through a Tailcat tunnel. All calls block. */
interface TailcatStream : Closeable {
    /** Reads up to [length] bytes into [buffer] at [offset]; returns the count, or -1 at end of stream. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** Writes [length] bytes of [buffer] from [offset]. */
    @Throws(IOException::class)
    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Signals the end of what this side sends, keeping the stream readable. */
    @Throws(IOException::class)
    fun closeWrite()
}

/** Opens streams to a port inside a Tailcat tunnel. */
fun interface TailcatDialer {
    /** Dials [port] inside the tunnel, giving up after [timeout]. Blocks. */
    @Throws(IOException::class)
    fun dial(port: Int, timeout: Duration): TailcatStream
}

/** A Tailcat client: dials streams until it is closed, which also closes its streams. */
interface TailcatClient :
    TailcatDialer,
    Closeable

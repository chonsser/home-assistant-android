package io.homeassistant.companion.android.tailcat

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.time.Duration
import okhttp3.HttpUrl
import timber.log.Timber

private const val BUFFER_SIZE = 64 * 1024
private const val ACCEPT_BACKLOG = 64

/** How far past the preferred port to look for a free one. */
internal const val PORT_PROBE_COUNT = 64

/**
 * Serves a Tailcat tunnel on `http://127.0.0.1:<port>`: every TCP connection accepted there is
 * piped, in both directions, to a stream dialed to [tunnelPort] inside the tunnel. The WebView,
 * OkHttp and the WebSocket then talk to the loopback URL as if it were the server.
 *
 * The listening socket is bound in the constructor, so [localUrl] is known the moment the proxy
 * exists. Every libtailcat call blocks, so accepting, dialing and piping run on dedicated threads
 * from [executor] rather than on coroutine dispatchers, whose pools they would exhaust.
 *
 * Note that the loopback interface is shared by every app on the device: another app could reach
 * Home Assistant's unauthenticated pages through this port, much as a device on the home network
 * could. Everything else still requires Home Assistant credentials.
 */
class TailcatLoopbackProxy(
    preferredPort: Int,
    private val tunnelPort: Int,
    private val dialer: TailcatDialer,
    private val dialTimeout: Duration,
    private val executor: ExecutorService,
) : Closeable {

    private val serverSocket: ServerSocket = bindFirstFree(preferredPort)
    private val pipes: MutableSet<Pipe> = Collections.synchronizedSet(mutableSetOf())

    @Volatile
    private var isClosed = false

    /** The loopback port this proxy listens on. */
    val port: Int = serverSocket.localPort

    /** The URL serving the tunnel. */
    val localUrl: HttpUrl = HttpUrl.Builder().scheme("http").host(LOOPBACK_HOST).port(port).build()

    /** Number of client connections currently being piped. */
    val activeConnectionCount: Int
        get() = pipes.size

    init {
        executor.execute(::acceptLoop)
    }

    /** Stops listening and closes every piped connection. */
    override fun close() {
        isClosed = true
        runCatching { serverSocket.close() }.onFailure { Timber.w(it, "Failed to close the Tailcat listener") }
        synchronized(pipes) { pipes.toList() }.forEach(Pipe::abort)
    }

    private fun acceptLoop() {
        while (!isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (!isClosed) Timber.e(e, "Failed to accept a connection on loopback port $port")
                return
            }
            try {
                executor.execute { serve(socket) }
            } catch (e: RejectedExecutionException) {
                Timber.e(e, "Failed to serve a connection on loopback port $port")
                closeQuietly(socket)
            }
        }
    }

    private fun serve(socket: Socket) {
        val stream = try {
            dialer.dial(tunnelPort, dialTimeout)
        } catch (e: IOException) {
            Timber.w(e, "Failed to open a Tailcat stream for loopback port $port")
            closeQuietly(socket)
            return
        }
        val pipe = Pipe(socket, stream)
        pipes += pipe
        if (!isClosed && startUpstream(pipe)) {
            pipe.pumpDownstream()
        } else {
            pipe.abort()
        }
    }

    private fun startUpstream(pipe: Pipe): Boolean = try {
        executor.execute(pipe::pumpUpstream)
        true
    } catch (e: RejectedExecutionException) {
        Timber.e(e, "Failed to pipe a connection on loopback port $port")
        false
    }

    /** One loopback connection and its tunnel stream. Each direction half-closes on end of stream. */
    private inner class Pipe(private val socket: Socket, private val stream: TailcatStream) {
        private var finishedDirections = 0

        /** Loopback client to tunnel. */
        fun pumpUpstream() {
            try {
                val input = socket.getInputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    stream.write(buffer, 0, count)
                }
                stream.closeWrite()
                finished()
            } catch (_: IOException) {
                // Either side went away or the proxy closed: nothing to report about a connection.
                abort()
            }
        }

        /** Tunnel to loopback client. */
        fun pumpDownstream() {
            try {
                val output = socket.getOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = stream.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    output.flush()
                }
                socket.shutdownOutput()
                finished()
            } catch (_: IOException) {
                abort()
            }
        }

        private fun finished() {
            val done = synchronized(this) { ++finishedDirections == 2 }
            if (done) abort()
        }

        fun abort() {
            pipes -= this
            closeQuietly(socket)
            closeQuietly(stream)
        }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"

        fun bindFirstFree(preferredPort: Int): ServerSocket {
            val loopback = InetAddress.getByName(LOOPBACK_HOST)
            for (candidate in TailcatLocalPort.candidates(preferredPort, PORT_PROBE_COUNT)) {
                val socket = ServerSocket()
                try {
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(loopback, candidate), ACCEPT_BACKLOG)
                    return socket
                } catch (_: IOException) {
                    // Taken by another process: try the next candidate.
                    closeQuietly(socket)
                }
            }
            throw IOException("No free loopback port near $preferredPort")
        }

        fun closeQuietly(closeable: Closeable) {
            try {
                closeable.close()
            } catch (e: IOException) {
                Timber.d(e, "Failed to close a Tailcat proxy resource")
            }
        }
    }
}

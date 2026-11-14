package io.homeassistant.companion.android.tailcat

import io.homeassistant.companion.android.common.data.tailcat.TailcatConfig
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import timber.log.Timber

private val DEFAULT_ATTEMPT_TIMEOUT = 10.seconds
private val DEFAULT_RETRY_DELAY = 1.seconds
private const val DEFAULT_FAILURES_BEFORE_RESET = 2

/**
 * How [TailcatClientSession] retries dials.
 *
 * @property attemptTimeout how long one dial attempt may take
 * @property retryDelay the pause between attempts
 * @property failuresBeforeReset consecutive failures after which a client that worked is replaced
 */
data class TailcatRetryPolicy(
    val attemptTimeout: Duration = DEFAULT_ATTEMPT_TIMEOUT,
    val retryDelay: Duration = DEFAULT_RETRY_DELAY,
    val failuresBeforeReset: Int = DEFAULT_FAILURES_BEFORE_RESET,
)

/**
 * Owns the Tailcat client of one tunnel endpoint: creates it lazily on the first dial, retries a
 * dial until the overall timeout, and replaces the client when it turns out to be unusable.
 *
 * Retrying matters right after pairing: Home Assistant restarts its Tailcat server to allowlist a
 * new device, which takes a couple of seconds, and a client that registered before that never gets
 * through. A client that never dialed successfully is therefore replaced after a failed attempt; one
 * that did keeps serving its other streams unless it fails [TailcatRetryPolicy.failuresBeforeReset] times in a row
 * (for example after the network changed under it).
 *
 * Every call blocks; closing a client waits for its calls, so it runs on [closeExecutor].
 */
class TailcatClientSession(
    private var config: TailcatConfig,
    private val closeExecutor: Executor,
    private val retryPolicy: TailcatRetryPolicy = TailcatRetryPolicy(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.inWholeMilliseconds) },
    private val makeClient: (TailcatConfig) -> TailcatClient = ::LibTailcatClient,
) : TailcatDialer {

    private val lock = Any()
    private var client: TailcatClient? = null
    private var clientHasDialed = false
    private var consecutiveFailures = 0

    /** Switches to a new client key for the same endpoint; the next dial uses it. */
    fun update(newConfig: TailcatConfig) {
        val changed = synchronized(lock) {
            (config != newConfig).also { if (it) config = newConfig }
        }
        if (changed) reset()
    }

    /** Closes the current client (and its streams); the next dial creates a fresh one. */
    fun reset() {
        val old = synchronized(lock) {
            client.also {
                client = null
                clientHasDialed = false
                consecutiveFailures = 0
            }
        }
        old?.let { closeExecutor.execute(it::close) }
    }

    /** Dials [port], retrying until [timeout] has passed. */
    override fun dial(port: Int, timeout: Duration): TailcatStream {
        val deadline = timeSource.markNow() + timeout
        var lastError: IOException = IOException("Timed out dialing through Tailcat")

        while (deadline.hasNotPassedNow()) {
            // An invalid configuration does not get better by retrying, so that error is thrown.
            val current = currentClient()
            try {
                val stream = current.dial(port, minOf(retryPolicy.attemptTimeout, -deadline.elapsedNow()))
                synchronized(lock) {
                    if (client === current) {
                        clientHasDialed = true
                        consecutiveFailures = 0
                    }
                }
                return stream
            } catch (e: IOException) {
                lastError = e
                Timber.i(e, "Tailcat dial attempt failed, retrying")
                discardIfUnusable(current)
            }

            val pause = minOf(retryPolicy.retryDelay, -deadline.elapsedNow())
            if (pause.isPositive()) sleep(pause)
        }
        throw lastError
    }

    private fun currentClient(): TailcatClient = synchronized(lock) {
        client ?: makeClient(config).also {
            client = it
            clientHasDialed = false
            consecutiveFailures = 0
        }
    }

    private fun discardIfUnusable(failed: TailcatClient) {
        val discarded = synchronized(lock) {
            if (client !== failed) return
            consecutiveFailures++
            if (clientHasDialed && consecutiveFailures < retryPolicy.failuresBeforeReset) return
            client = null
            clientHasDialed = false
            consecutiveFailures = 0
            failed
        }
        closeExecutor.execute(discarded::close)
    }
}

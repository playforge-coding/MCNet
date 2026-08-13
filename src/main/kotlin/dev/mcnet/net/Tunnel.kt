package dev.mcnet.net

import computer.iroh.BiStream
import computer.iroh.RecvStream
import computer.iroh.SendStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * The ALPN that identifies MCNet traffic. iroh refuses connections whose ALPN
 * the accepting endpoint did not register, so this doubles as a cheap filter
 * against unrelated iroh applications dialing a Minecraft host.
 */
val MCNET_ALPN: ByteArray = "mcnet/1".toByteArray(Charsets.US_ASCII)

/**
 * A second ALPN used only to ask "are you there?".
 *
 * Probing over [MCNET_ALPN] would work, but every check would look like a real
 * player arriving: the host would log a join and briefly count the prober in
 * `/mcnet status`. A separate ALPN lets the host answer the question and hang
 * up without any of that.
 */
val MCNET_PROBE_ALPN: ByteArray = "mcnet-probe/1".toByteArray(Charsets.US_ASCII)

/**
 * How much we are willing to pull out of a QUIC stream in one call. The
 * Minecraft protocol is bursty and latency-sensitive rather than throughput
 * bound, so a modest buffer keeps memory flat without adding round trips.
 */
private const val CHUNK_SIZE = 16 * 1024

/**
 * How long the socket-to-stream direction may keep draining after the other
 * direction has ended. Bounded so a peer that vanished mid-session cannot pin
 * the coroutine forever.
 */
private const val DRAIN_TIMEOUT_MS = 15_000L

/**
 * Splices a TCP socket onto a QUIC bidirectional stream, copying bytes in both
 * directions until the session ends.
 *
 * Nothing here understands the Minecraft protocol: MCNet is a byte pipe, which
 * is precisely why it keeps working across game versions. The vanilla client
 * believes it opened an ordinary TCP connection, and the vanilla server
 * believes it accepted one.
 *
 * Half-closes are propagated in both directions rather than simply tearing the
 * pair down. Minecraft depends on this: a server-list ping works by sending a
 * request, closing the sending half, and *then* reading the reply. If the
 * end-of-stream never reached the local server it would sit waiting for more
 * request bytes and neither side would ever finish.
 */
suspend fun bridge(socket: Socket, stream: BiStream, scope: CoroutineScope) {
    // Minecraft sends many small packets; Nagle's algorithm would add latency
    // for no useful batching.
    runCatching { socket.tcpNoDelay = true }

    val send = stream.send()
    val recv = stream.recv()

    val input = withContext(Dispatchers.IO) { socket.getInputStream() }
    val output = withContext(Dispatchers.IO) { socket.getOutputStream() }

    // socket -> QUIC. Ends at socket EOF, and finishes the send stream so the
    // peer sees a clean end rather than a dropped connection.
    val upstream = scope.launch { socketToStream(input, send) }

    // QUIC -> socket. Ends when the peer finishes its half.
    val downstream = scope.launch {
        val cleanEof = streamToSocket(recv, output)
        runCatching {
            withContext(Dispatchers.IO) {
                if (socket.isClosed) return@withContext
                // A clean end-of-stream becomes a TCP half-close, leaving the
                // local server free to finish writing its reply. Anything else
                // means the peer is gone, so drop the socket outright — which
                // also unblocks the read in the other direction.
                if (cleanEof) socket.shutdownOutput() else socket.close()
            }
        }
    }

    downstream.join()
    withTimeoutOrNull(DRAIN_TIMEOUT_MS) { upstream.join() }
    upstream.cancel()

    runCatching { withContext(Dispatchers.IO) { socket.close() } }
    // Unblocks the peer if it is still waiting on us.
    runCatching { recv.stop(0uL) }
}

private suspend fun socketToStream(input: InputStream, send: SendStream) {
    val buffer = ByteArray(CHUNK_SIZE)
    try {
        while (true) {
            val read = withContext(Dispatchers.IO) { input.read(buffer) }
            if (read <= 0) break
            // writeAll rather than write: write reports a partial count, and
            // dropping the remainder would silently corrupt the packet stream.
            send.writeAll(buffer.copyOf(read))
        }
    } catch (_: Exception) {
        // A peer disconnecting is ordinary, not exceptional.
    } finally {
        runCatching { send.finish() }
    }
}

/** Returns true if the stream ended cleanly, false if it failed mid-flight. */
private suspend fun streamToSocket(recv: RecvStream, output: OutputStream): Boolean {
    try {
        while (true) {
            // An empty read is how the bindings surface end-of-stream; this is
            // covered by a test, since bridge()'s termination depends on it.
            val chunk = recv.read(CHUNK_SIZE.toUInt())
            if (chunk.isEmpty()) return true
            withContext(Dispatchers.IO) {
                output.write(chunk)
                output.flush()
            }
        }
    } catch (_: Exception) {
        return false
    }
}

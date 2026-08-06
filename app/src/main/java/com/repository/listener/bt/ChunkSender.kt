package com.repository.listener.bt

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Splits a JSON payload with [ChunkFramer] and writes the chunks off the caller's thread.
 *
 * A chunked send sleeps [INTER_CHUNK_SLEEP_MS] between frames, so a large payload occupies its
 * thread for seconds. The callers are the Android main looper (chat list and chat history arrive
 * through ChatHistoryClient's main-looper callbacks) and the orchestrator WebSocket reader thread
 * (todo, job and every Telegram channel) -- one is an ANR and the other stalls all inbound traffic.
 *
 * A single-threaded executor also subsumes the per-channel send lock it replaced: submissions run
 * one whole split-and-send loop at a time, so chunks cannot interleave on any channel or across
 * channels, and FIFO submission order is preserved.
 */
class ChunkSender(
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bt-chunk-sender").apply { isDaemon = true }
    },
    private val send: (channel: String, args: Array<String>) -> Unit,
    private val sleep: (ms: Long) -> Unit = { Thread.sleep(it) }
) {

    fun send(channel: String, prefix: String?, json: String, maxChars: Int) {
        val chunks = ChunkFramer.frame(channel, prefix, json, maxChars)
        executor.execute {
            try {
                chunks.forEachIndexed { i, args ->
                    send(channel, args)
                    if (i != chunks.lastIndex) sleep(INTER_CHUNK_SLEEP_MS)
                }
            } catch (e: Exception) {
                // A dead socket must abort this stream only; the next submission is independent.
                onError?.invoke(channel, e)
            }
        }
    }

    var onError: ((channel: String, error: Exception) -> Unit)? = null

    companion object {
        const val INTER_CHUNK_SLEEP_MS = 50L
    }
}

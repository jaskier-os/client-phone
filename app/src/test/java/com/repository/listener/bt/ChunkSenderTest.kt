package com.repository.listener.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ChunkSenderTest {

    private class Recorder {
        val frames = java.util.Collections.synchronizedList(mutableListOf<Pair<String, List<String>>>())
        val slept = AtomicLong(0)
    }

    /** Runs submitted work on the calling thread, so ordering is deterministic in tests. */
    private val inline = java.util.concurrent.Executor { it.run() }

    private fun sender(r: Recorder, executor: java.util.concurrent.Executor = inline) =
        ChunkSender(
            executor = executor,
            send = { channel, args -> r.frames.add(channel to args.toList()) },
            sleep = { ms -> r.slept.addAndGet(ms) }
        )

    @Test
    fun aShortPayloadIsOneFrameWithNoSleep() {
        val r = Recorder()
        sender(r).send("ch.a", prefix = null, json = "{\"a\":1}", maxChars = 10_000)
        assertEquals(1, r.frames.size)
        assertEquals(0L, r.slept.get())
    }

    @Test
    fun everyChunkIsSentInOrderWithASleepBetweenButNotAfterTheLast() {
        val r = Recorder()
        sender(r).send("ch.a", prefix = null, json = "x".repeat(25), maxChars = 10)
        assertEquals(3, r.frames.size)
        assertEquals(listOf("ch.a", "ch.a", "ch.a"), r.frames.map { it.first })
        assertEquals(listOf("xxxxxxxxxx", "xxxxxxxxxx", "xxxxx"), r.frames.map { it.second[0] })
        assertEquals(2 * ChunkSender.INTER_CHUNK_SLEEP_MS, r.slept.get())
    }

    @Test
    fun theCallerDoesNotBlockOnTheSleeps() {
        val r = Recorder()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val s = ChunkSender(
                executor = executor,
                send = { channel, args -> r.frames.add(channel to args.toList()) },
                sleep = { started.countDown(); release.await() }
            )
            val elapsed = kotlin.system.measureTimeMillis {
                s.send("ch.a", null, "x".repeat(30), maxChars = 10)
            }
            assertTrue("caller blocked for ${elapsed}ms", elapsed < 500)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            release.countDown()
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun concurrentSendsOnTheSameChannelNeverInterleaveTheirChunks() {
        val r = Recorder()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val s = ChunkSender(
                executor = executor,
                send = { channel, args -> r.frames.add(channel to args.toList()) },
                sleep = { }
            )
            val threads = (0 until 4).map { t ->
                Thread { s.send("ch.a", null, "$t".repeat(30), maxChars = 10) }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(5_000) }
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
        assertEquals(12, r.frames.size)
        // Each payload is a run of one repeated digit, so any interleaving shows up as a run break.
        val digits = r.frames.map { it.second[0].first() }
        for (i in 0 until 4) {
            val group = digits.subList(i * 3, i * 3 + 3)
            assertEquals("chunk group $i interleaved: $digits", 1, group.toSet().size)
        }
    }

    @Test
    fun sendsAcrossDifferentChannelsAlsoStayWhole() {
        val r = Recorder()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val s = ChunkSender(
                executor = executor,
                send = { channel, args -> r.frames.add(channel to args.toList()) },
                sleep = { }
            )
            val threads = (0 until 3).map { t ->
                Thread { s.send("ch.$t", null, "x".repeat(30), maxChars = 10) }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(5_000) }
            executor.shutdown()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
        val channels = r.frames.map { it.first }
        for (i in 0 until 3) {
            assertEquals(1, channels.subList(i * 3, i * 3 + 3).toSet().size)
        }
    }

    @Test
    fun aFailingFrameAbortsTheRestOfThatStreamOnly() {
        val r = Recorder()
        var calls = 0
        val s = ChunkSender(
            executor = inline,
            send = { channel, args ->
                calls++
                if (calls == 2) throw IllegalStateException("socket died")
                r.frames.add(channel to args.toList())
            },
            sleep = { }
        )
        s.send("ch.a", null, "x".repeat(30), maxChars = 10)
        s.send("ch.a", null, "y".repeat(10), maxChars = 10)
        assertEquals(listOf("xxxxxxxxxx", "yyyyyyyyyy"), r.frames.map { it.second[0] })
    }

    @Test
    fun aPrefixIsCarriedOnEveryChunk() {
        val r = Recorder()
        sender(r).send("ch.h", prefix = "conv-7", json = "x".repeat(15), maxChars = 10)
        assertEquals(2, r.frames.size)
        assertTrue(r.frames.all { it.second[0] == "conv-7" })
    }
}

package com.repository.listener.rc

import com.repository.listener.bt.BtProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class RcBridgeTest {

    private class Harness(
        val store: RcMirrorStore = RcMirrorStore(),
        var transcriptFor: (String) -> String? = { null },
        var sendUserMessageImpl: (String, String) -> Unit = { _, _ -> }
    ) {
        val sent = mutableListOf<Pair<String, List<String>>>()
        val userMessages = mutableListOf<Pair<String, String>>()
        val userResponses = mutableListOf<Triple<String, String, String>>()
        val reads = mutableListOf<Pair<String, Long>>()
        val transcriptRequests = mutableListOf<String>()
        var linkUp = true
        var observeOpenDuringMarkRead = false
        val openDuringMarkRead = mutableListOf<String?>()
        private var bridgeRef: RcBridge? = null

        val bridge: RcBridge = RcBridge(
            store = store,
            send = { channel, args -> sent.add(channel to args.toList()); linkUp },
            sendUserMessage = { sid, text ->
                userMessages.add(sid to text); sendUserMessageImpl(sid, text)
            },
            sendUserResponse = { sid, rid, text -> userResponses.add(Triple(sid, rid, text)) },
            markRead = { sid, seen ->
                reads.add(sid to seen)
                if (observeOpenDuringMarkRead) openDuringMarkRead.add(bridgeRef?.openSessionId)
            },
            requestTranscript = { sid -> transcriptRequests.add(sid) },
            cachedTranscript = { sid -> transcriptFor(sid) }
        )

        init {
            bridgeRef = bridge
        }
    }

    @Test
    fun aSendRequestForwardsTheTextAndConfirms() {
        val h = Harness()
        h.bridge.handleSendReq(listOf("sess-1", "cid-9", "hello"))
        assertEquals(listOf("sess-1" to "hello"), h.userMessages)
        assertEquals(
            listOf(BtProtocol.CH_RC_SEND_RESP to listOf("sess-1", "cid-9", "sent")),
            h.sent
        )
    }

    @Test
    fun aRejectedSendRepliesWithTheErrorAndDoesNotPropagate() {
        val h = Harness()
        h.sendUserMessageImpl = { _, _ -> throw IllegalStateException("offline") }
        h.bridge.handleSendReq(listOf("sess-1", "cid-9", "hello"))
        assertEquals(
            listOf(BtProtocol.CH_RC_SEND_RESP to listOf("sess-1", "cid-9", "error:offline")),
            h.sent
        )
    }

    @Test
    fun aMalformedSendRequestIsIgnored() {
        val h = Harness()
        h.bridge.handleSendReq(listOf("sess-1"))
        h.bridge.handleSendReq(emptyList())
        h.bridge.handleSendReq(listOf("sess-1", "cid-9", "   "))
        assertEquals(emptyList<Pair<String, String>>(), h.userMessages)
    }

    @Test
    fun anAnswerRequestRoutesToTheUserResponsePath() {
        val h = Harness()
        h.bridge.handleAnswerReq(listOf("sess-1", "req-3", "Yes"))
        assertEquals(listOf(Triple("sess-1", "req-3", "Yes")), h.userResponses)
    }

    @Test
    fun aMessagesRequestMarksReadRepliesAndOpensTheSession() {
        val h = Harness()
        h.store.appendUser("sess-1", "hi")
        h.bridge.handleMessagesReq(listOf("sess-1", "11"))
        assertEquals(listOf("sess-1" to 11L), h.reads)
        assertEquals("sess-1", h.bridge.openSessionId)
        assertEquals(1, h.sent.size)
        val (channel, args) = h.sent[0]
        assertEquals(BtProtocol.CH_RC_MESSAGES_RESP, channel)
        assertEquals("sess-1", args[0])
        val body = JSONObject(args[1])
        assertEquals(1, body.getJSONArray("rows").length())
        assertEquals(0L, body.getLong("lastSeq"))
        assertEquals(false, body.getBoolean("more"))
    }

    @Test
    fun anEmptyStoreWithNoCachedTranscriptRequestsItOnceAndStillReplies() {
        val h = Harness()
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        assertEquals(listOf("sess-1"), h.transcriptRequests)
        assertEquals(1, h.sent.size)
        assertEquals(BtProtocol.CH_RC_MESSAGES_RESP, h.sent[0].first)

        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        assertEquals(listOf("sess-1", "sess-1"), h.transcriptRequests)
    }

    @Test
    fun anEmptyStoreWithACachedTranscriptSeedsInsteadOfRequesting() {
        val h = Harness(transcriptFor = { """[{"type":"user_message","data":{"text":"seeded"}}]""" })
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        assertEquals(emptyList<String>(), h.transcriptRequests)
        val body = JSONObject(h.sent[0].second[1])
        assertEquals("seeded", body.getJSONArray("rows").getJSONObject(0).getString("x"))
    }

    @Test
    fun anEmptySessionIdClosesTheThreadAndSendsNothing() {
        val h = Harness()
        h.store.appendUser("sess-1", "hi")
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        h.sent.clear()
        h.bridge.handleMessagesReq(listOf(""))
        assertNull(h.bridge.openSessionId)
        assertEquals(emptyList<Pair<String, List<String>>>(), h.sent)
    }

    @Test
    fun rowsAreNotPushedForASessionThatIsNotOpen() {
        val h = Harness()
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        h.sent.clear()
        h.bridge.pushRows("sess-2", listOf(RcRow(0, "assistant", "done")))
        assertEquals(emptyList<Pair<String, List<String>>>(), h.sent)
    }

    @Test
    fun rowsArePushedForTheOpenSession() {
        val h = Harness()
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        h.sent.clear()
        h.bridge.pushRows("sess-1", listOf(RcRow(4, "tools", "Read, Grep", toolCount = 7)))
        assertEquals(1, h.sent.size)
        val body = JSONObject(h.sent[0].second[1])
        val row = body.getJSONArray("rows").getJSONObject(0)
        assertEquals(4L, row.getLong("q"))
        assertEquals("tools", row.getString("r"))
        assertEquals("Read, Grep", row.getString("x"))
        assertEquals(7, row.getInt("c"))
    }

    @Test
    fun aRowPushReportsWhetherTheFrameReachedTheGlasses() {
        val h = Harness()
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        assertTrue(h.bridge.pushRows("sess-1", listOf(RcRow(1, "assistant", "done"))))
        h.linkUp = false
        assertTrue(!h.bridge.pushRows("sess-1", listOf(RcRow(2, "assistant", "later"))))
    }

    @Test
    fun aRowPushForAClosedThreadReportsNotDelivered() {
        val h = Harness()
        assertTrue(!h.bridge.pushRows("sess-1", listOf(RcRow(1, "assistant", "done"))))
    }

    @Test
    fun anEmptyRowPushSendsNothing() {
        val h = Harness()
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        h.sent.clear()
        h.bridge.pushRows("sess-1", emptyList())
        assertEquals(emptyList<Pair<String, List<String>>>(), h.sent)
    }

    @Test
    fun aTranscriptForAnotherSessionEmitsNothing() {
        val h = Harness()
        h.bridge.handleMessagesReq(listOf("sess-B", "-1"))
        h.sent.clear()
        h.bridge.onTranscript("sess-A", """[{"type":"user_message","data":{"text":"A only"}}]""")
        assertEquals(emptyList<Pair<String, List<String>>>(), h.sent)
    }

    @Test
    fun aTranscriptForTheOpenSessionSeedsAndEmitsOnce() {
        val h = Harness()
        h.bridge.handleMessagesReq(listOf("sess-A", "-1"))
        h.sent.clear()
        h.bridge.onTranscript("sess-A", """[{"type":"user_message","data":{"text":"A only"}}]""")
        assertEquals(1, h.sent.size)
        assertEquals("sess-A", h.sent[0].second[0])
        val body = JSONObject(h.sent[0].second[1])
        assertEquals("A only", body.getJSONArray("rows").getJSONObject(0).getString("x"))
    }

    @Test
    fun aPromptRowCarriesItsOptionsOnTheWire() {
        val h = Harness()
        h.store.appendPrompt("sess-1", "Allow Bash?", listOf("Yes", "No"))
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        val row = JSONObject(h.sent[0].second[1]).getJSONArray("rows").getJSONObject(0)
        assertEquals(listOf("Yes", "No"), (0 until row.getJSONArray("o").length()).map {
            row.getJSONArray("o").getString(it)
        })
    }

    @Test
    fun aTailWithOlderRowsDroppedReportsMore() {
        val h = Harness()
        repeat(30) { h.store.appendUser("sess-1", "m$it") }
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        val body = JSONObject(h.sent[0].second[1])
        assertTrue(body.getBoolean("more"))
        assertEquals(RcMirrorStore.TAIL_ROWS, body.getJSONArray("rows").length())
    }

    @Test
    fun aMessagesResponseNeverExceedsOneFrame() {
        val h = Harness()
        repeat(40) { h.store.appendUser("sess-1", "x".repeat(400)) }
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        assertTrue(h.sent[0].second[1].length < RcJson.MAX_FRAME_CHARS)
    }

    @Test
    fun aResponseOfMaximallyEscapedRowsStillFitsOneFrame() {
        // Every char escapes to six (\u0001), so the row cap alone does not bound the frame.
        val h = Harness()
        repeat(40) { h.store.appendUser("sess-1", "\u0001".repeat(400)) }
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        val body = h.sent[0].second[1]
        assertTrue("frame was ${body.length} chars", body.length <= RcJson.MAX_FRAME_CHARS)
        assertTrue(JSONObject(body).getBoolean("more"))
    }

    @Test
    fun aSecondOpenOfASeededThreadDoesNotRefetchTheTranscript() {
        val h = Harness()
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        assertEquals(listOf("sess-1"), h.transcriptRequests)
        h.bridge.onTranscript("sess-1", """[{"type":"user_message","data":{"text":"seeded"}}]""")
        h.bridge.handleMessagesReq(listOf("sess-1", "0"))
        assertEquals(listOf("sess-1"), h.transcriptRequests)
    }

    @Test
    fun lastSeqIsTheNewestRowsSeqNotTheOldest() {
        val h = Harness()
        repeat(3) { h.store.appendUser("sess-1", "m$it") }
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        assertEquals(2L, JSONObject(h.sent[0].second[1]).getLong("lastSeq"))
    }

    @Test
    fun aRowWithoutToolsOrOptionsOmitsThoseKeys() {
        val h = Harness()
        h.store.appendUser("sess-1", "hi")
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        val row = JSONObject(h.sent[0].second[1]).getJSONArray("rows").getJSONObject(0)
        assertTrue("toolCount must not be on the wire when zero", !row.has("c"))
        assertTrue("options must not be on the wire when empty", !row.has("o"))
    }

    @Test
    fun theOpenSessionOnlySwitchesAfterTheAcknowledgementHasBeenApplied() {
        val h = Harness()
        h.bridge.handleMessagesReq(listOf("sess-A", "-1"))
        // Reading openSessionId inside markRead for the NEXT thread must still see the old one:
        // switching first would attribute sess-A's acknowledgement to sess-B.
        h.observeOpenDuringMarkRead = true
        h.bridge.handleMessagesReq(listOf("sess-B", "-1"))
        assertEquals(listOf<String?>("sess-A"), h.openDuringMarkRead)
        assertEquals("sess-B", h.bridge.openSessionId)
    }

    @Test
    fun quotingRoundTripsThroughAJsonParser() {
        val h = Harness()
        val nasty = "quote\" back\\slash\nnew\ttab\u0001ctrl"
        h.store.appendUser("sess-1", nasty)
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        val row = JSONObject(h.sent[0].second[1]).getJSONArray("rows").getJSONObject(0)
        assertEquals(nasty, row.getString("x"))
    }

    @Test
    fun aLoneSurrogateIsEscapedSoTheFrameStaysEncodable() {
        val h = Harness()
        h.store.appendUser("sess-1", "before\uD83Dafter")
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        val body = h.sent[0].second[1]
        assertTrue("lone surrogate must not appear raw", !body.contains('\uD83D'))
        assertTrue(body.contains("\\ud83d"))
    }

    @Test
    fun aWellFormedSurrogatePairSurvivesVerbatim() {
        val h = Harness()
        h.store.appendUser("sess-1", "ok \uD83D\uDE00 done")
        h.bridge.handleMessagesReq(listOf("sess-1", "-1"))
        val row = JSONObject(h.sent[0].second[1]).getJSONArray("rows").getJSONObject(0)
        assertEquals("ok \uD83D\uDE00 done", row.getString("x"))
    }

    @Test
    fun aMalformedAnswerRequestIsIgnored() {
        val h = Harness()
        h.bridge.handleAnswerReq(listOf("sess-1", "", "Yes"))
        h.bridge.handleAnswerReq(listOf("sess-1", "req-3", ""))
        h.bridge.handleAnswerReq(listOf("", "req-3", "Yes"))
        h.bridge.handleAnswerReq(emptyList())
        assertEquals(emptyList<Triple<String, String, String>>(), h.userResponses)
    }

    @Test
    fun anErrorThrownByTheSendPathStillProducesAReply() {
        val h = Harness()
        h.sendUserMessageImpl = { _, _ -> throw StackOverflowError("boom") }
        h.bridge.handleSendReq(listOf("sess-1", "cid-9", "hello"))
        assertEquals(
            listOf(BtProtocol.CH_RC_SEND_RESP to listOf("sess-1", "cid-9", "error:boom")),
            h.sent
        )
    }
}

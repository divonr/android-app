package com.example.ApI.data.cloud

import com.example.ApI.data.model.StreamingEvent
import com.example.ApI.data.model.ThoughtsStatus
import com.example.ApI.data.model.TokenUsage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudRunTrackerTest {

    private fun running(runId: String, chatId: String, text: String = "", thinkingText: String = "", thinkingActive: Boolean = false) =
        CloudRunState(runId, chatId, "openai", "m", CloudRunState.STATUS_RUNNING, text, thinkingActive, thinkingText, null, 1)

    private fun ended(runId: String, chatId: String, status: String, error: String? = null) =
        CloudRunState(runId, chatId, "openai", "m", status, "last text", false, "", error, 1)

    private fun partial(runId: String, chatId: String, text: String) =
        CloudRunAction.Stream(StreamingEvent.PartialResponse(runId, chatId, text))

    // ── A run started here ────────────────────────────────────────────────────

    @Test
    fun `a run started here streams through the ordinary streaming events and ends with a pull`() {
        val tracker = CloudRunTracker()
        tracker.registerLocal("r1", "c1")
        assertTrue(tracker.isPending("r1"))
        assertEquals("r1", tracker.displayedRun("c1"))
        tracker.confirmLocal("r1")
        assertFalse(tracker.isPending("r1"))

        // The server's run_started of our own run changes nothing (the send path shows the chat already)
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.RunStarted(running("r1", "c1"))))

        assertEquals(
            listOf(CloudRunAction.Stream(StreamingEvent.ThinkingStarted("r1", "c1"))),
            tracker.onEvent(CloudRunEvent.ThinkingStarted("r1", "c1"))
        )
        assertEquals(
            listOf(CloudRunAction.Stream(StreamingEvent.ThinkingPartial("r1", "c1", "hm"))),
            tracker.onEvent(CloudRunEvent.ThinkingPartial("r1", "c1", "hm"))
        )
        assertEquals(
            listOf(CloudRunAction.Stream(StreamingEvent.ThinkingComplete("r1", "c1", "hm", 2f, ThoughtsStatus.PRESENT))),
            tracker.onEvent(CloudRunEvent.ThinkingComplete("r1", "c1", "hm", 2f, ThoughtsStatus.PRESENT))
        )
        assertEquals(listOf(partial("r1", "c1", "He")), tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "He")))
        assertEquals(
            listOf(CloudRunAction.ToolStarted("r1", "c1", "get_date_time", "Date")),
            tracker.onEvent(CloudRunEvent.ToolCall("r1", "c1", "get_date_time", "Date"))
        )
        assertEquals(
            listOf(CloudRunAction.ToolFinished("r1", "c1")),
            tracker.onEvent(CloudRunEvent.ToolResult("r1", "c1", "get_date_time", true))
        )
        assertEquals(
            listOf(CloudRunAction.MessagesSaved("r1", "c1", displayed = true)),
            tracker.onEvent(CloudRunEvent.MessagesAdded("r1", "c1"))
        )
        assertEquals(listOf(partial("r1", "c1", "llo")), tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "llo")))
        assertEquals(
            listOf(CloudRunAction.Finished("r1", "c1", displayed = true, messageId = "msg-1")),
            tracker.onEvent(CloudRunEvent.Complete("r1", "c1", "llo", "msg-1"))
        )
        assertNull(tracker.displayedRun("c1"))
        assertFalse(tracker.isTracked("r1"))
    }

    @Test
    fun `an error and a cancellation end the run`() {
        val tracker = CloudRunTracker()
        tracker.registerLocal("r1", "c1")
        assertEquals(
            listOf(CloudRunAction.Failed("r1", "c1", displayed = true, error = "boom")),
            tracker.onEvent(CloudRunEvent.Error("r1", "c1", "boom"))
        )
        tracker.registerLocal("r2", "c1")
        assertEquals(
            listOf(CloudRunAction.Finished("r2", "c1", displayed = true, messageId = null)),
            tracker.onEvent(CloudRunEvent.Cancelled("r2", "c1"))
        )
        // A reply that could not be attached has no message to wait for
        tracker.registerLocal("r3", "c1")
        assertEquals(
            listOf(CloudRunAction.Finished("r3", "c1", displayed = true, messageId = null)),
            tracker.onEvent(CloudRunEvent.Complete("r3", "c1", "x", ""))
        )
    }

    @Test
    fun `after the user stopped a run its further text is not shown`() {
        val tracker = CloudRunTracker()
        tracker.registerLocal("r1", "c1")
        tracker.confirmLocal("r1")
        tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "a"))
        tracker.markStopping("r1")
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "b")))
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.ThinkingPartial("r1", "c1", "b")))
        assertEquals(
            listOf(CloudRunAction.Finished("r1", "c1", displayed = true, messageId = null)),
            tracker.onEvent(CloudRunEvent.Cancelled("r1", "c1"))
        )
    }

    // ── Runs of other devices ─────────────────────────────────────────────────

    @Test
    fun `a run started elsewhere is discovered, shown and streamed`() {
        val tracker = CloudRunTracker()
        assertEquals(
            listOf(
                CloudRunAction.Discovered("r1", "c1"),
                CloudRunAction.Show("r1", "c1", "", "", thinkingActive = false, isNew = true)
            ),
            tracker.onEvent(CloudRunEvent.RunStarted(running("r1", "c1")))
        )
        assertEquals("r1", tracker.displayedRun("c1"))
        assertFalse(tracker.isPending("r1"))
        assertEquals(listOf(partial("r1", "c1", "hi")), tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "hi")))
        assertEquals(
            listOf(CloudRunAction.Finished("r1", "c1", displayed = true, messageId = "m")),
            tracker.onEvent(CloudRunEvent.Complete("r1", "c1", "hi", "m"))
        )
    }

    @Test
    fun `an event of an unknown run adopts the run`() {
        val tracker = CloudRunTracker()
        assertEquals(
            listOf(
                CloudRunAction.Discovered("r1", "c1"),
                CloudRunAction.Show("r1", "c1", "", "", thinkingActive = false, isNew = true),
                partial("r1", "c1", "x")
            ),
            tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "x"))
        )
    }

    @Test
    fun `the end of a run that was never followed is still reported for a pull`() {
        val tracker = CloudRunTracker()
        assertEquals(
            listOf(CloudRunAction.Finished("r1", "c1", displayed = false, messageId = null)),
            tracker.onEvent(CloudRunEvent.Complete("r1", "c1", "x", "m"))
        )
    }

    // ── chat_updated (the title, after a run ended) ───────────────────────────

    @Test
    fun `chat_updated asks for a refresh of the chat whatever is known about its run`() {
        val tracker = CloudRunTracker()
        val refresh = listOf(CloudRunAction.ChatUpdated("c1"))

        // A run that ended here a moment ago
        tracker.registerLocal("r1", "c1")
        tracker.confirmLocal("r1")
        tracker.onEvent(CloudRunEvent.Complete("r1", "c1", "x", "m"))
        assertEquals(refresh, tracker.onEvent(CloudRunEvent.ChatUpdated("r1", "c1")))
        // The run stays over: nothing is adopted, the chat displays nothing
        assertFalse(tracker.isTracked("r1"))
        assertNull(tracker.displayedRun("c1"))

        // A run of another device this client never saw, and one it gave up for an on-device fallback
        assertEquals(refresh, tracker.onEvent(CloudRunEvent.ChatUpdated("r-unknown", "c1")))
        assertFalse(tracker.isTracked("r-unknown"))
        tracker.registerLocal("r2", "c1")
        tracker.abandon("r2")
        assertEquals(refresh, tracker.onEvent(CloudRunEvent.ChatUpdated("r2", "c1")))
        assertFalse(tracker.isTracked("r2"))
    }

    @Test
    fun `chat_updated does not disturb another run the chat is displaying`() {
        val tracker = CloudRunTracker()
        tracker.registerLocal("r2", "c1")
        tracker.confirmLocal("r2")
        tracker.onEvent(CloudRunEvent.Partial("r2", "c1", "He"))
        // The title of the previous reply arrives while the next one streams
        assertEquals(listOf(CloudRunAction.ChatUpdated("c1")), tracker.onEvent(CloudRunEvent.ChatUpdated("r1", "c1")))
        assertEquals("r2", tracker.displayedRun("c1"))
        assertEquals(listOf(partial("r2", "c1", "llo")), tracker.onEvent(CloudRunEvent.Partial("r2", "c1", "llo")))
    }

    @Test
    fun `usage changes nothing - no action, no adopted run, a followed run goes on`() {
        val tracker = CloudRunTracker()
        val usage = TokenUsage(promptTokens = 4436, cachedTokens = 4043, outputTokens = 372, thoughtsTokens = 12)

        // Of a run nobody announced here: not adopted
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.Usage("r-unknown", "c9", usage)))
        assertFalse(tracker.isTracked("r-unknown"))
        assertNull(tracker.displayedRun("c9"))

        // Of the run a chat displays (one per provider request, so also between its tool steps)
        tracker.registerLocal("r1", "c1")
        tracker.confirmLocal("r1")
        tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "He"))
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.Usage("r1", "c1", usage)))
        assertEquals("r1", tracker.displayedRun("c1"))
        assertEquals(listOf(partial("r1", "c1", "llo")), tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "llo")))
    }

    // ── Snapshots ─────────────────────────────────────────────────────────────

    @Test
    fun `a snapshot adopts running runs with what they streamed so far and ignores finished ones`() {
        val tracker = CloudRunTracker()
        val actions = tracker.onEvent(
            CloudRunEvent.Snapshot(
                listOf(
                    running("r1", "c1", text = "so far", thinkingText = "hm", thinkingActive = true),
                    ended("r2", "c2", CloudRunState.STATUS_COMPLETE),
                    ended("r3", "c3", CloudRunState.STATUS_ERROR, "old error")
                )
            )
        )
        assertEquals(
            listOf(
                CloudRunAction.Discovered("r1", "c1"),
                CloudRunAction.Show("r1", "c1", "so far", "hm", thinkingActive = true, isNew = true)
            ),
            actions
        )
        assertNull(tracker.displayedRun("c2"))
        assertNull(tracker.displayedRun("c3"))
    }

    @Test
    fun `after a reconnect a displayed run takes its text from the snapshot`() {
        val tracker = CloudRunTracker()
        tracker.registerLocal("r1", "c1")
        tracker.confirmLocal("r1")
        tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "ab"))
        tracker.onEvent(CloudRunEvent.Disconnected)
        assertEquals(
            listOf(CloudRunAction.Show("r1", "c1", "abcdef", "", thinkingActive = false, isNew = false)),
            tracker.onEvent(CloudRunEvent.Snapshot(listOf(running("r1", "c1", text = "abcdef"))))
        )
        assertEquals(listOf(partial("r1", "c1", "g")), tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "g")))
    }

    @Test
    fun `a known run missing from a fresh snapshot is finished`() {
        val tracker = CloudRunTracker()
        tracker.registerLocal("r1", "c1")
        tracker.confirmLocal("r1")
        tracker.onEvent(CloudRunEvent.RunStarted(running("r2", "c2")))
        assertEquals(
            setOf(
                CloudRunAction.Finished("r1", "c1", displayed = true, messageId = null),
                CloudRunAction.Finished("r2", "c2", displayed = true, messageId = null)
            ),
            tracker.onEvent(CloudRunEvent.Snapshot(emptyList())).toSet()
        )
        assertNull(tracker.displayedRun("c1"))
        assertNull(tracker.displayedRun("c2"))
    }

    @Test
    fun `a run whose start request is still in flight survives a snapshot without it`() {
        val tracker = CloudRunTracker()
        tracker.registerLocal("r1", "c1")
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.Snapshot(emptyList())))
        assertEquals("r1", tracker.displayedRun("c1"))
        // Once the server reported it, a snapshot without it means it is over
        tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "x"))
        assertEquals(
            listOf(CloudRunAction.Finished("r1", "c1", displayed = true, messageId = null)),
            tracker.onEvent(CloudRunEvent.Snapshot(emptyList()))
        )
    }

    @Test
    fun `a known run that ended while disconnected is finished or failed by the snapshot`() {
        val tracker = CloudRunTracker()
        tracker.registerLocal("r1", "c1"); tracker.confirmLocal("r1")
        tracker.registerLocal("r2", "c2"); tracker.confirmLocal("r2")
        tracker.registerLocal("r3", "c3"); tracker.confirmLocal("r3")
        val actions = tracker.onEvent(
            CloudRunEvent.Snapshot(
                listOf(
                    ended("r1", "c1", CloudRunState.STATUS_COMPLETE),
                    ended("r2", "c2", CloudRunState.STATUS_CANCELLED),
                    ended("r3", "c3", CloudRunState.STATUS_ERROR, "boom")
                )
            )
        )
        assertEquals(
            setOf(
                CloudRunAction.Finished("r1", "c1", displayed = true, messageId = null),
                CloudRunAction.Finished("r2", "c2", displayed = true, messageId = null),
                CloudRunAction.Failed("r3", "c3", displayed = true, error = "boom")
            ),
            actions.toSet()
        )
    }

    // ── One display per chat ──────────────────────────────────────────────────

    @Test
    fun `a chat busy with an on-device request does not display a cloud run until it is free`() {
        var busy = true
        val tracker = CloudRunTracker { chatId -> busy && chatId == "c1" }
        assertEquals(
            listOf<CloudRunAction>(CloudRunAction.Discovered("r1", "c1")),
            tracker.onEvent(CloudRunEvent.RunStarted(running("r1", "c1")))
        )
        assertNull(tracker.displayedRun("c1"))
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "a")))
        assertEquals(
            listOf(CloudRunAction.MessagesSaved("r1", "c1", displayed = false)),
            tracker.onEvent(CloudRunEvent.MessagesAdded("r1", "c1"))
        )
        tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "b"))

        // Still busy at the next snapshot: nothing shown
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.Snapshot(listOf(running("r1", "c1", text = "b")))))

        busy = false
        assertEquals(
            listOf(CloudRunAction.Show("r1", "c1", "bc", "", thinkingActive = false, isNew = true)),
            tracker.onEvent(CloudRunEvent.Snapshot(listOf(running("r1", "c1", text = "bc"))))
        )
        assertEquals(listOf(partial("r1", "c1", "d")), tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "d")))
    }

    @Test
    fun `a busy chat's cloud run only reports its end`() {
        val tracker = CloudRunTracker { true }
        tracker.onEvent(CloudRunEvent.RunStarted(running("r1", "c1")))
        assertEquals(
            listOf(CloudRunAction.Finished("r1", "c1", displayed = false, messageId = "m")),
            tracker.onEvent(CloudRunEvent.Complete("r1", "c1", "x", "m"))
        )
    }

    @Test
    fun `a second run of a chat is followed silently and takes over when the first ends`() {
        val tracker = CloudRunTracker()
        tracker.onEvent(CloudRunEvent.RunStarted(running("r1", "c1")))
        assertEquals(
            listOf<CloudRunAction>(CloudRunAction.Discovered("r2", "c1")),
            tracker.onEvent(CloudRunEvent.RunStarted(running("r2", "c1")))
        )
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.Partial("r2", "c1", "two")))
        assertEquals("r1", tracker.displayedRun("c1"))
        assertEquals(
            listOf(
                CloudRunAction.Finished("r1", "c1", displayed = true, messageId = "m1"),
                CloudRunAction.Show("r2", "c1", "two", "", thinkingActive = false, isNew = true)
            ),
            tracker.onEvent(CloudRunEvent.Complete("r1", "c1", "one", "m1"))
        )
        assertEquals("r2", tracker.displayedRun("c1"))
    }

    @Test
    fun `a run started here takes the display of its chat`() {
        val tracker = CloudRunTracker()
        tracker.onEvent(CloudRunEvent.RunStarted(running("r1", "c1")))
        tracker.registerLocal("r2", "c1")
        assertEquals("r2", tracker.displayedRun("c1"))
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "x")))
        assertEquals(listOf(partial("r2", "c1", "y")), tracker.onEvent(CloudRunEvent.Partial("r2", "c1", "y")))
    }

    // ── Fallback, stop, reset ─────────────────────────────────────────────────

    @Test
    fun `a run abandoned for an on-device fallback is ignored`() {
        val tracker = CloudRunTracker()
        tracker.registerLocal("r1", "c1")
        tracker.abandon("r1")
        assertNull(tracker.displayedRun("c1"))
        // It did start on the server after all (ambiguous failure): never shown
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.RunStarted(running("r1", "c1"))))
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "x")))
        assertEquals(emptyList(), tracker.onEvent(CloudRunEvent.Snapshot(listOf(running("r1", "c1", text = "x")))))
        assertEquals(
            listOf(CloudRunAction.Finished("r1", "c1", displayed = false, messageId = null)),
            tracker.onEvent(CloudRunEvent.Cancelled("r1", "c1"))
        )
    }

    @Test
    fun `a forced finish clears the run and a later event brings it back`() {
        val tracker = CloudRunTracker()
        tracker.registerLocal("r1", "c1")
        tracker.confirmLocal("r1")
        assertEquals(
            listOf(CloudRunAction.Finished("r1", "c1", displayed = true, messageId = null)),
            tracker.forceFinish("r1")
        )
        assertEquals(emptyList(), tracker.forceFinish("r1"))
        assertEquals(
            listOf(
                CloudRunAction.Discovered("r1", "c1"),
                CloudRunAction.Show("r1", "c1", "", "", thinkingActive = false, isNew = true),
                partial("r1", "c1", "x")
            ),
            tracker.onEvent(CloudRunEvent.Partial("r1", "c1", "x"))
        )
    }

    @Test
    fun `reset finishes every run`() {
        val tracker = CloudRunTracker { it == "c2" }
        tracker.registerLocal("r1", "c1")
        tracker.onEvent(CloudRunEvent.RunStarted(running("r2", "c2")))
        assertEquals(
            setOf(
                CloudRunAction.Finished("r1", "c1", displayed = true, messageId = null),
                CloudRunAction.Finished("r2", "c2", displayed = false, messageId = null)
            ),
            tracker.reset().toSet()
        )
        assertEquals(emptyList(), tracker.reset())
    }
}

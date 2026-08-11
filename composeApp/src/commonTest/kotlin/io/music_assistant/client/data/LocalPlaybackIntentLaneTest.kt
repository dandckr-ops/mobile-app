package io.music_assistant.client.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalPlaybackIntentLaneTest {
    @Test
    fun `newer intent executes after an older in-flight intent`() = runTest {
        val lane = LocalPlaybackIntentLane()
        val events = mutableListOf<String>()
        val olderEntered = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        val pauseGeneration = lane.issue()
        val pause = async {
            lane.executeIfCurrent(pauseGeneration) {
                events += "pause-start"
                olderEntered.complete(Unit)
                releaseOlder.await()
                events += "pause-finish"
            }
        }

        olderEntered.await()
        val playGeneration = lane.issue()
        val play = async {
            lane.executeIfCurrent(playGeneration) { events += "play" }
        }
        releaseOlder.complete(Unit)

        assertTrue(pause.await())
        assertTrue(play.await())
        assertEquals(listOf("pause-start", "pause-finish", "play"), events)
    }

    @Test
    fun `superseded intent that has not started is dropped`() = runTest {
        val lane = LocalPlaybackIntentLane()
        val events = mutableListOf<String>()
        val stalePauseGeneration = lane.issue()
        val playGeneration = lane.issue()

        assertTrue(lane.executeIfCurrent(playGeneration) { events += "play" })
        assertFalse(lane.executeIfCurrent(stalePauseGeneration) { events += "stale-pause" })
        assertEquals(listOf("play"), events)
    }
}

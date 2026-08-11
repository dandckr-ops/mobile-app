package io.music_assistant.client.data

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orders outbound local-player playback intents across UI, CarPlay, and route-safety sources.
 * An intent already sending completes before a newer one; an older intent that has not started
 * is discarded once a newer generation exists. Therefore the newest accepted intent is always
 * the last one delivered to the server.
 */
class LocalPlaybackIntentLane {
    private val latestGeneration = atomic(0L)
    private val sendMutex = Mutex()

    fun issue(): Long = latestGeneration.incrementAndGet()

    suspend fun executeIfCurrent(
        generation: Long,
        operation: suspend () -> Unit,
    ): Boolean = sendMutex.withLock {
        if (generation != latestGeneration.value) return@withLock false
        operation()
        true
    }
}

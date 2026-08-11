package io.music_assistant.client.player

import platform.Foundation.NSData

/**
 * Interface for platform-specific audio player implementation.
 * This allows Swift (or other iOS logic) to provide the actual player.
 */
interface PlatformAudioPlayer {
    fun prepareStream(
        codec: String,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
        listener: MediaPlayerListener,
    )
    fun writeRawPcm(data: ByteArray)

    /**
     * Efficient variant called from Kotlin: data is already converted to NSData using
     * usePinned bulk-copy, avoiding a byte-by-byte Swift interop loop.
     */
    fun writeRawPcmNSData(data: NSData)

    /** Pause output (user pause / interruption / re-phase). */
    fun pauseSink()

    /** Resume output, reactivating the audio session to reclaim it from other apps. */
    fun resumeSink()

    /** Restore a persisted route-loss hold before cold-launch streaming begins. */
    fun restoreRouteLossPlaybackBlock()

    /** Mark the native gate initialized after persistence has been checked and no hold exists. */
    fun initializeRouteLossPlaybackSafety()

    /** Report the live native route-loss gate for transport reconnect vetoes. */
    fun isRouteLossPlaybackBlocked(): Boolean

    /** Release after explicit user intent and return its monotonic native safety generation. */
    fun allowPlaybackAfterUserIntent(): Long

    /** Atomically authorize verified continuity only while the live CarPlay route remains safe. */
    fun authorizeVerifiedContinuityPlayback(): Long

    /** Drop buffered PCM (track transition / playback-delay re-phase). */
    fun flush()

    fun stopRawPcmStream()
    fun setVolume(volume: Int)
    fun setMuted(muted: Boolean)
    fun dispose()

    fun setLongFormSeekIntervals(backSeconds: Long, forwardSeconds: Long)

    // Remote command handler (set by Kotlin to receive play/pause/next/prev events)
    fun setRemoteCommandHandler(handler: RemoteCommandHandler?)
}

/**
 * Handler for player commands originating on the iOS side.
 *
 * `source` identifies who issued the command so logs can distinguish a genuine
 * user action from an automatic one: "remote" (Control Center / lock screen),
 * "interruption" (audio-session interruption began/ended), or "route_loss"
 * (output device disappeared). It is diagnostic only — `command` alone drives
 * playback.
 */
interface RemoteCommandHandler {
    fun onCommand(command: String, source: String, explicitUserIntent: Boolean)
}

/**
 * Singleton provider to bridge Kotlin and Swift.
 * Swift should assign its implementation to `player` at startup.
 */
object PlatformPlayerProvider {
    var player: PlatformAudioPlayer? = null
}

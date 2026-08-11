@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.music_assistant.client.player

import io.music_assistant.client.player.sendspin.model.AudioCodec

/**
 * MediaPlayerController - Sendspin audio player
 *
 * Handles raw PCM audio streaming for Sendspin protocol.
 * Built-in player (ExoPlayer) has been removed - Sendspin is now the only playback method.
 */
expect class MediaPlayerController(platformContext: PlatformContext) {
    // Callback for remote commands (e.g. from iOS Control Center)
    // Common code can set this to receive commands like "play", "pause", "next", "previous"
    var onRemoteCommand: ((String, String, Boolean) -> Unit)?

    // Sendspin streaming
    fun prepareStream(
        codec: AudioCodec,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codecHeader: String?,
        listener: MediaPlayerListener,
    )

    fun writeRawPcm(data: ByteArray): Int

    fun stopRawPcmStream()

    // Lightweight sink control (no AudioTrack destruction) — used for network starvation pausing
    fun pauseSink()
    fun resumeSink()
    fun flush()

    // Resume playback after a transport reconnect (resumes audio sink + sends play command)
    fun resume()

    // Explicit user/verified-continuity intent may release an iOS route-loss safety hold.
    fun allowPlaybackAfterUserIntent(): Long

    // Returns a non-negative native safety generation, or -1 when the live route rejects it.
    fun authorizeVerifiedContinuityPlayback(): Long

    fun isPlaybackBlockedByRouteLoss(): Boolean

    // Volume control (0-100)
    fun setVolume(volume: Int)

    // Mute control
    fun setMuted(muted: Boolean)

    // Get current system volume (0-100)
    fun getCurrentSystemVolume(): Int

    fun release()

    fun setLongFormSeekIntervals(backSeconds: Long, forwardSeconds: Long)
}

expect class PlatformContext

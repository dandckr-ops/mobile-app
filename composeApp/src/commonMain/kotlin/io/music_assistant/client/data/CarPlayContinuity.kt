package io.music_assistant.client.data

import io.music_assistant.client.data.model.server.PlayerState
import kotlinx.serialization.Serializable

const val CARPLAY_CONTINUITY_TTL_MS = 5 * 60 * 1_000L

@Serializable
enum class ContinuityDisconnectReason {
    CarPlayRouteLoss,
}

@Serializable
data class CarPlayContinuityRecord(
    val serverId: String,
    val playerId: String,
    val queueId: String,
    val currentItemId: String,
    val wasPlaying: Boolean,
    val routeLostAtEpochMs: Long,
    val reason: ContinuityDisconnectReason,
)

data class CarPlayAuthority(
    val serverId: String,
    val playerId: String,
    val queueId: String,
    val currentItemId: String,
    val queueItemIds: Set<String>,
    val playerState: PlayerState,
    val elapsedTime: Double,
)

enum class ContinuityInvalidation {
    ManualPause,
    UntrustedRemotePause,
    UserPlay,
    Stop,
    QueueClear,
    Logout,
    LocalPlayerDisabled,
}

sealed interface ContinuityOutboundCommand {
    data object GetPlayers : ContinuityOutboundCommand
    data object GetQueues : ContinuityOutboundCommand
    data class GetQueueItems(val queueId: String) : ContinuityOutboundCommand
    data class Play(val playerId: String) : ContinuityOutboundCommand
}

/** The production seam: MainDataSource and tests both execute through this interface. */
interface CarPlayContinuityGateway {
    suspend fun refreshPlayers(): Boolean
    suspend fun refreshQueues(): Boolean
    suspend fun fetchQueueItems(queueId: String): Boolean
    fun authority(): CarPlayAuthority?
    fun play(playerId: String): Long?
}

fun shouldPersistCarPlayRouteLossHold(
    serverReportsPlaying: Boolean?,
    wasInterrupted: Boolean,
): Boolean = wasInterrupted || serverReportsPlaying != false

fun isCarPlayRouteLoss(command: String, source: String): Boolean =
    command == "pause" &&
        (source == "carplay_route_loss" || source == "carplay_route_loss_interruption")

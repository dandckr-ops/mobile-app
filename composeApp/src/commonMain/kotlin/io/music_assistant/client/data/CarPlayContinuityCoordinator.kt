package io.music_assistant.client.data

import co.touchlab.kermit.Logger
import io.music_assistant.client.data.model.server.PlayerState
import io.music_assistant.client.settings.SettingsRepository
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

class CarPlayContinuityCoordinator(
    private val settings: SettingsRepository,
) {
    private val log = Logger.withTag("CarPlayContinuity")
    private val stateLock = SynchronizedObject()
    private val ticketSequence = atomic(0L)
    private val generation = atomic(0L)
    private val preparing = atomic(false)
    private val verified = atomic<Verified?>(null)
    private var latestNativeSafetyGeneration = 0L

    private data class Verified(
        val ticket: Long,
        val generation: Long,
        val record: CarPlayContinuityRecord,
    )

    fun hasPendingRouteLoss(): Boolean = synchronized(stateLock) {
        settings.loadCarPlayContinuity() != null
    }

    fun allowsTransportAutoResume(): Boolean = synchronized(stateLock) {
        settings.loadCarPlayRouteLossHoldAt() == 0L
    }

    fun recordRouteLossHold(
        routeLostAtEpochMs: Long,
        nativeGeneration: Long,
        persistHold: Boolean = true,
    ): Boolean = synchronized(stateLock) {
        if (nativeGeneration < latestNativeSafetyGeneration) return@synchronized false
        latestNativeSafetyGeneration = nativeGeneration
        if (persistHold) settings.saveCarPlayRouteLossHoldAt(routeLostAtEpochMs)
        true
    }

    fun recordRouteLoss(record: CarPlayContinuityRecord) = synchronized(stateLock) {
        generation.incrementAndGet()
        verified.value = null
        if (record.wasPlaying && settings.loadCarPlayRouteLossHoldAt() != 0L) {
            // Native route handling owns durable-hold creation. This later metadata callback
            // may attach restoration authority only while that hold still exists; it must
            // never recreate a hold cleared by a newer explicit user Play.
            settings.saveCarPlayContinuity(record)
        } else {
            settings.saveCarPlayContinuity(null)
        }
        log.i {
            "transition=carplay_route_loss priorIntent=${record.wasPlaying} " +
                "identityMatch=pending ageMs=0 action=record suppression=none"
        }
    }

    fun invalidate(
        reason: ContinuityInvalidation,
        nativeGeneration: Long? = null,
    ): Boolean = synchronized(stateLock) {
        if (reason == ContinuityInvalidation.UserPlay && nativeGeneration != null) {
            if (nativeGeneration < latestNativeSafetyGeneration) return@synchronized false
            latestNativeSafetyGeneration = nativeGeneration
        }
        generation.incrementAndGet()
        verified.value = null
        val prior = settings.loadCarPlayContinuity()?.wasPlaying
        settings.saveCarPlayContinuity(null)
        if (reason == ContinuityInvalidation.UserPlay) {
            settings.saveCarPlayRouteLossHoldAt(null)
        }
        log.i {
            "transition=invalidate reason=$reason priorIntent=$prior identityMatch=false " +
                "ageMs=unknown action=suppress suppression=$reason"
        }
        true
    }

    fun disconnect() = synchronized(stateLock) {
        generation.incrementAndGet()
        verified.value = null
        log.i {
            "transition=carplay_disconnect priorIntent=${settings.loadCarPlayContinuity()?.wasPlaying} " +
                "identityMatch=pending ageMs=unknown action=fence suppression=disconnected"
        }
    }

    /** Refreshes in strict order. Returns a generation ticket; it does not play. */
    suspend fun prepare(
        nowEpochMs: Long,
        gateway: CarPlayContinuityGateway,
    ): Long? {
        if (!preparing.compareAndSet(expect = false, update = true)) {
            return suppress("refresh_already_in_flight", null, null)
        }
        try {
            val snapshot = synchronized(stateLock) {
                verified.value = null
                generation.value to settings.loadCarPlayContinuity()
            }
            val connectionGeneration = snapshot.first
            val record = snapshot.second
                ?: return suppress("no_record", null, null)
            val age = nowEpochMs - record.routeLostAtEpochMs
            val isFreshCarPlayLoss = record.reason == ContinuityDisconnectReason.CarPlayRouteLoss &&
                age in 0..CARPLAY_CONTINUITY_TTL_MS
            if (!isFreshCarPlayLoss) {
                synchronized(stateLock) {
                    if (
                        generation.value == connectionGeneration &&
                        settings.loadCarPlayContinuity() == record
                    ) {
                        settings.saveCarPlayContinuity(null)
                    }
                }
                return suppress("expired_or_wrong_reason", record, age)
            }
            if (!gateway.refreshPlayers() || connectionGeneration != generation.value) {
                return suppress("players_refresh_failed_or_stale", record, age)
            }
            if (!gateway.refreshQueues() || connectionGeneration != generation.value) {
                return suppress("queues_refresh_failed_or_stale", record, age)
            }
            if (!gateway.fetchQueueItems(record.queueId) || connectionGeneration != generation.value) {
                return suppress("items_refresh_failed_or_stale", record, age)
            }
            val authority = gateway.authority()
            val matches = authority != null &&
                authority.serverId == record.serverId &&
                authority.playerId == record.playerId &&
                authority.queueId == record.queueId &&
                authority.currentItemId == record.currentItemId &&
                record.currentItemId in authority.queueItemIds &&
                authority.playerState == PlayerState.PAUSED &&
                authority.elapsedTime.isFinite() &&
                authority.elapsedTime > 0.0
            val ticket = synchronized(stateLock) {
                if (connectionGeneration != generation.value) {
                    null
                } else if (!matches) {
                    if (settings.loadCarPlayContinuity() == record) {
                        settings.saveCarPlayContinuity(null)
                    }
                    null
                } else {
                    ticketSequence.incrementAndGet().also {
                        verified.value = Verified(it, connectionGeneration, record)
                    }
                }
            } ?: return suppress("authority_mismatch", record, age)
            log.i {
                "transition=authority_verified priorIntent=${record.wasPlaying} identityMatch=true " +
                    "ageMs=$age action=await_live_route_check suppression=none"
            }
            return ticket
        } finally {
            preparing.value = false
        }
    }

    /** Called only after Swift performs its live route/ownership check. One shot. */
    fun confirm(
        ticket: Long,
        nowEpochMs: Long,
        routeAvailable: Boolean,
        otherAudioOwner: Boolean,
        gateway: CarPlayContinuityGateway,
    ) = synchronized(stateLock) {
        val candidate = verified.value?.takeIf { it.ticket == ticket }
        val claimed = candidate?.takeIf { verified.compareAndSet(it, null) }
        val record = claimed?.record
        val sameGeneration = claimed?.generation == generation.value
        val age = record?.let { nowEpochMs - it.routeLostAtEpochMs }
        val fresh = age != null && age in 0..CARPLAY_CONTINUITY_TTL_MS
        val eligible = claimed != null &&
            sameGeneration &&
            fresh &&
            routeAvailable &&
            !otherAudioOwner &&
            record.wasPlaying
        val nativeGeneration = if (eligible) gateway.play(requireNotNull(record).playerId) else null
        val shouldPlay = nativeGeneration != null && nativeGeneration >= 0L
        if (nativeGeneration != null && nativeGeneration >= 0L) {
            latestNativeSafetyGeneration = maxOf(
                latestNativeSafetyGeneration,
                nativeGeneration,
            )
        }
        val suppression = when {
            claimed == null -> "no_verified_intent"
            !sameGeneration -> "stale_generation"
            !fresh -> "expired_at_confirmation"
            !routeAvailable -> "carplay_route_unavailable"
            otherAudioOwner -> "secondary_audio_owner"
            !record.wasPlaying -> "prior_intent_paused"
            !shouldPlay -> "native_route_rejected"
            else -> "none"
        }
        log.i {
            "transition=live_confirmation priorIntent=${record?.wasPlaying} " +
                "identityMatch=${claimed != null && sameGeneration} ageMs=${age ?: "unknown"} " +
                "action=${if (shouldPlay) "play" else "remain_paused"} suppression=$suppression"
        }
        if (shouldPlay) {
            settings.saveCarPlayContinuity(null)
            settings.saveCarPlayRouteLossHoldAt(null)
        } else if (!eligible && claimed != null && sameGeneration) {
            if (!fresh || routeAvailable) settings.saveCarPlayContinuity(null)
        }
    }

    private fun suppress(
        reason: String,
        record: CarPlayContinuityRecord?,
        age: Long?,
    ): Long? {
        log.i {
            "transition=prepare priorIntent=${record?.wasPlaying} identityMatch=false " +
                "ageMs=${age ?: "unknown"} action=remain_paused suppression=$reason"
        }
        return null
    }
}

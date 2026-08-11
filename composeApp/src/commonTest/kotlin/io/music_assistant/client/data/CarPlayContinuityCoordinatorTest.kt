package io.music_assistant.client.data

import com.russhwolf.settings.MapSettings
import io.music_assistant.client.data.model.server.PlayerState
import io.music_assistant.client.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarPlayContinuityCoordinatorTest {
    private val record = CarPlayContinuityRecord(
        serverId = "server",
        playerId = "local",
        queueId = "queue",
        currentItemId = "item-7",
        wasPlaying = true,
        routeLostAtEpochMs = 1_000,
        reason = ContinuityDisconnectReason.CarPlayRouteLoss,
    )
    private val authority = CarPlayAuthority(
        serverId = "server",
        playerId = "local",
        queueId = "queue",
        currentItemId = "item-7",
        queueItemIds = setOf("item-1", "item-7", "item-9"),
        playerState = PlayerState.PAUSED,
        elapsedTime = 42.0,
    )

    @Test
    fun `route loss hold policy is fail closed without manufacturing paused intent`() {
        assertTrue(shouldPersistCarPlayRouteLossHold(serverReportsPlaying = true, wasInterrupted = false))
        assertFalse(shouldPersistCarPlayRouteLossHold(serverReportsPlaying = false, wasInterrupted = false))
        assertTrue(shouldPersistCarPlayRouteLossHold(serverReportsPlaying = false, wasInterrupted = true))
        assertTrue(shouldPersistCarPlayRouteLossHold(serverReportsPlaying = null, wasInterrupted = false))
    }

    @Test
    fun `only explicit CarPlay route provenance qualifies`() {
        assertEquals(true, isCarPlayRouteLoss("pause", "carplay_route_loss"))
        assertEquals(true, isCarPlayRouteLoss("pause", "carplay_route_loss_interruption"))
        assertFalse(isCarPlayRouteLoss("play", "carplay_route_loss_interruption"))
        listOf(
            "route_loss",
            "headphones",
            "airpods",
            "bluetooth",
            "transport_reconnect",
        ).forEach { source ->
            assertFalse(isCarPlayRouteLoss("pause", source))
        }
    }

    @Test
    fun `production coordinator orders one refresh then permits one play`() = runTest {
        val coordinator = coordinatorWith(record)
        val gateway = RecordingGateway(authority)
        val ticket = assertNotNull(coordinator.prepare(2_000, gateway))
        assertEquals(
            listOf(
                ContinuityOutboundCommand.GetPlayers,
                ContinuityOutboundCommand.GetQueues,
                ContinuityOutboundCommand.GetQueueItems("queue"),
            ),
            gateway.commands,
        )

        val replacementTicket = assertNotNull(coordinator.prepare(2_001, gateway))
        assertNotEquals(ticket, replacementTicket)
        assertEquals(
            6,
            gateway.commands.size,
            "a new preparation must refresh authority and issue a unique ticket",
        )
        coordinator.confirm(ticket, 2_002, true, false, gateway)
        coordinator.confirm(replacementTicket, 2_002, true, false, gateway)
        coordinator.confirm(replacementTicket, 2_002, true, false, gateway)
        assertEquals(1, gateway.commands.count { it is ContinuityOutboundCommand.Play })
        assertEquals(true, coordinator.allowsTransportAutoResume())
    }

    @Test
    fun `native route rejection preserves hold and sends no play`() = runTest {
        val coordinator = coordinatorWith(record)
        val gateway = RecordingGateway(authority, playGeneration = null)
        val ticket = assertNotNull(coordinator.prepare(2_000, gateway))

        coordinator.confirm(
            ticket,
            2_001,
            routeAvailable = true,
            otherAudioOwner = false,
            gateway = gateway,
        )

        assertFalse(gateway.commands.any { it is ContinuityOutboundCommand.Play })
        assertFalse(coordinator.allowsTransportAutoResume())
    }

    @Test
    fun `overlapping preparation is rejected until the owner releases`() = runTest {
        val coordinator = coordinatorWith(record)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gateway = object : CarPlayContinuityGateway {
            override suspend fun refreshPlayers(): Boolean {
                entered.complete(Unit)
                release.await()
                return true
            }

            override suspend fun refreshQueues(): Boolean = true
            override suspend fun fetchQueueItems(queueId: String): Boolean = true
            override fun authority(): CarPlayAuthority = authority
            override fun play(playerId: String): Long = 1L
        }

        val owner = async { coordinator.prepare(2_000, gateway) }
        entered.await()
        assertNull(coordinator.prepare(2_001, gateway))
        release.complete(Unit)
        assertNotNull(owner.await())
    }

    @Test
    fun `stale preparation cannot erase newer route metadata`() = runTest {
        val coordinator = coordinatorWith(record)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleGateway = object : CarPlayContinuityGateway {
            override suspend fun refreshPlayers(): Boolean {
                entered.complete(Unit)
                release.await()
                return true
            }

            override suspend fun refreshQueues(): Boolean = true
            override suspend fun fetchQueueItems(queueId: String): Boolean = true
            override fun authority(): CarPlayAuthority = authority
            override fun play(playerId: String): Long = 1L
        }

        val stalePreparation = async { coordinator.prepare(2_000, staleGateway) }
        entered.await()
        val newerRecord = record.copy(currentItemId = "item-8", routeLostAtEpochMs = 1_500)
        coordinator.recordRouteLossHold(newerRecord.routeLostAtEpochMs, 2L)
        coordinator.recordRouteLoss(newerRecord)
        release.complete(Unit)
        assertNull(stalePreparation.await())

        val newerAuthority = authority.copy(
            currentItemId = "item-8",
            queueItemIds = authority.queueItemIds + "item-8",
        )
        assertNotNull(coordinator.prepare(2_001, RecordingGateway(newerAuthority)))
    }

    @Test
    fun `disconnect fences prepared restoration`() = runTest {
        val coordinator = coordinatorWith(record)
        val gateway = RecordingGateway(authority)
        val ticket = assertNotNull(coordinator.prepare(2_000, gateway))

        coordinator.disconnect()
        coordinator.confirm(ticket, 2_001, true, false, gateway)

        assertFalse(gateway.commands.any { it is ContinuityOutboundCommand.Play })
    }

    @Test
    fun `native route loss hold persists without continuity metadata`() {
        val settings = SettingsRepository(MapSettings())
        val coordinator = CarPlayContinuityCoordinator(settings)
        val routeLostAt = 12_345L

        coordinator.recordRouteLossHold(routeLostAt, 1L)

        assertFalse(coordinator.allowsTransportAutoResume())
        assertEquals(routeLostAt, settings.loadCarPlayRouteLossHoldAt())
        assertEquals(null, settings.loadCarPlayContinuity())
    }

    @Test
    fun `late route metadata cannot recreate hold after explicit play`() {
        val coordinator = CarPlayContinuityCoordinator(SettingsRepository(MapSettings()))
        coordinator.recordRouteLossHold(record.routeLostAtEpochMs, 1L)
        coordinator.invalidate(ContinuityInvalidation.UserPlay, 2L)

        assertFalse(coordinator.recordRouteLossHold(record.routeLostAtEpochMs, 1L))
        coordinator.recordRouteLoss(record)

        assertTrue(coordinator.allowsTransportAutoResume())
        assertFalse(coordinator.hasPendingRouteLoss())
    }

    @Test
    fun `paused CarPlay loss creates neither restoration record nor durable hold`() {
        val coordinator = CarPlayContinuityCoordinator(SettingsRepository(MapSettings()))
        coordinator.recordRouteLoss(record.copy(wasPlaying = false))

        assertFalse(coordinator.hasPendingRouteLoss())
        assertEquals(true, coordinator.allowsTransportAutoResume())
    }

    @Test
    fun `non-play invalidation keeps transport veto until explicit play`() {
        val coordinator = coordinatorWith(record)
        assertFalse(coordinator.allowsTransportAutoResume())

        coordinator.invalidate(ContinuityInvalidation.ManualPause)
        assertFalse(coordinator.allowsTransportAutoResume())

        coordinator.invalidate(ContinuityInvalidation.UserPlay)
        assertEquals(true, coordinator.allowsTransportAutoResume())
    }

    @Test
    fun `paused expired and explicitly invalidated intents never play`() = runTest {
        val paused = coordinatorWith(record.copy(wasPlaying = false))
        val pausedGateway = RecordingGateway(authority)
        assertNull(paused.prepare(2_000, pausedGateway))
        assertFalse(pausedGateway.commands.any { it is ContinuityOutboundCommand.Play })

        val expired = coordinatorWith(record)
        assertNull(
            expired.prepare(
                1_001 + CARPLAY_CONTINUITY_TTL_MS,
                RecordingGateway(authority),
            ),
        )

        listOf(
            ContinuityInvalidation.ManualPause,
            ContinuityInvalidation.UntrustedRemotePause,
            ContinuityInvalidation.UserPlay,
            ContinuityInvalidation.Stop,
            ContinuityInvalidation.QueueClear,
            ContinuityInvalidation.Logout,
            ContinuityInvalidation.LocalPlayerDisabled,
        ).forEach { reason ->
            val coordinator = coordinatorWith(record)
            coordinator.invalidate(reason)
            assertNull(coordinator.prepare(2_000, RecordingGateway(authority)))
        }
    }

    @Test
    fun `confirmation rechecks expiry after authority refresh`() = runTest {
        val coordinator = coordinatorWith(record)
        val gateway = RecordingGateway(authority)
        val ticket = assertNotNull(coordinator.prepare(300_999, gateway))

        coordinator.confirm(ticket, 301_001, true, false, gateway)

        assertFalse(gateway.commands.any { it is ContinuityOutboundCommand.Play })
        assertNull(coordinator.prepare(301_001, gateway))
    }

    @Test
    fun `unsafe confirmation keeps transport veto until explicit play`() = runTest {
        val coordinator = coordinatorWith(record)
        val gateway = RecordingGateway(authority)
        val ticket = assertNotNull(coordinator.prepare(2_000, gateway))
        coordinator.confirm(ticket, 2_001, false, false, gateway)

        assertFalse(coordinator.allowsTransportAutoResume())
        assertNotNull(coordinator.prepare(2_001, gateway))
        assertFalse(coordinator.allowsTransportAutoResume())
        coordinator.invalidate(ContinuityInvalidation.UserPlay)
        assertEquals(true, coordinator.allowsTransportAutoResume())
    }

    @Test
    fun `live route and audio ownership suppress at decision boundary`() = runTest {
        listOf(
            false to false,
            true to true,
        ).forEach { (routeAvailable, otherAudioOwner) ->
            val coordinator = coordinatorWith(record)
            val gateway = RecordingGateway(authority)
            val ticket = assertNotNull(coordinator.prepare(2_000, gateway))

            coordinator.confirm(ticket, 2_001, routeAvailable, otherAudioOwner, gateway)

            assertFalse(gateway.commands.any { it is ContinuityOutboundCommand.Play })
        }
    }

    @Test
    fun `all identities and current item membership are authoritative`() = runTest {
        listOf(
            authority.copy(serverId = "other"),
            authority.copy(playerId = "other"),
            authority.copy(queueId = "other"),
            authority.copy(currentItemId = "other"),
            authority.copy(queueItemIds = setOf("item-1")),
            authority.copy(playerState = PlayerState.PLAYING),
            authority.copy(playerState = PlayerState.IDLE),
            authority.copy(elapsedTime = -1.0),
            authority.copy(elapsedTime = 0.0),
            authority.copy(elapsedTime = Double.NaN),
            authority.copy(elapsedTime = Double.POSITIVE_INFINITY),
        ).forEach { mismatchedAuthority ->
            val coordinator = coordinatorWith(record)
            val gateway = RecordingGateway(mismatchedAuthority)

            assertNull(coordinator.prepare(2_000, gateway))
            assertFalse(gateway.commands.any { it is ContinuityOutboundCommand.Play })
        }
    }

    @Test
    fun `cold launch restores bounded metadata and consumes it once`() = runTest {
        val map = MapSettings()
        val first = CarPlayContinuityCoordinator(SettingsRepository(map))
        first.recordRouteLossHold(record.routeLostAtEpochMs, 1L)
        first.recordRouteLoss(record)
        val raw = map.getString("carplay_continuity", "")
        assertEquals(
            setOf(
                "serverId",
                "playerId",
                "queueId",
                "currentItemId",
                "wasPlaying",
                "routeLostAtEpochMs",
                "reason",
            ),
            Json.parseToJsonElement(raw).jsonObject.keys,
        )

        val relaunched = CarPlayContinuityCoordinator(SettingsRepository(map))
        val gateway = RecordingGateway(authority)
        val ticket = assertNotNull(relaunched.prepare(2_000, gateway))
        relaunched.confirm(ticket, 2_001, true, false, gateway)

        val secondRelaunch = CarPlayContinuityCoordinator(SettingsRepository(map))
        assertNull(secondRelaunch.prepare(2_000, RecordingGateway(authority)))
        assertEquals(1, gateway.commands.count { it is ContinuityOutboundCommand.Play })
    }

    private fun coordinatorWith(record: CarPlayContinuityRecord): CarPlayContinuityCoordinator =
        CarPlayContinuityCoordinator(SettingsRepository(MapSettings())).also {
            it.recordRouteLossHold(record.routeLostAtEpochMs, 1L)
            it.recordRouteLoss(record)
        }
}

private class RecordingGateway(
    private val value: CarPlayAuthority?,
    private val playGeneration: Long? = 1L,
) : CarPlayContinuityGateway {
    val commands = mutableListOf<ContinuityOutboundCommand>()

    override suspend fun refreshPlayers(): Boolean = true.also {
        commands += ContinuityOutboundCommand.GetPlayers
    }

    override suspend fun refreshQueues(): Boolean = true.also {
        commands += ContinuityOutboundCommand.GetQueues
    }

    override suspend fun fetchQueueItems(queueId: String): Boolean = true.also {
        commands += ContinuityOutboundCommand.GetQueueItems(queueId)
    }

    override fun authority(): CarPlayAuthority? = value

    override fun play(playerId: String): Long? {
        if (playGeneration != null) {
            commands += ContinuityOutboundCommand.Play(playerId)
        }
        return playGeneration
    }
}

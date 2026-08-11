import Foundation
import AVFoundation
import AudioToolbox
import ComposeApp

/// Native iOS audio player using AudioQueue
/// Replaces MPVController for better iOS integration
class NativeAudioController: NSObject, PlatformAudioPlayer {

    // MARK: - AudioQueue
    private var audioQueue: AudioQueueRef?
    private var audioFormat: AudioStreamBasicDescription = AudioStreamBasicDescription()

    // MARK: - Audio Buffer
    private var pcmBuffer: [Data] = []
    private let bufferLock = NSLock()
    private let playbackGateLock = NSLock()
    private let audioQueueUseLock = NSLock()
    private let audioQueueLifecycleQueue = DispatchQueue(label: "io.music-assistant.audio-queue-lifecycle")
    private let audioQueueLifecycleKey = DispatchSpecificKey<Void>()
    private let kNumberOfBuffers = 5 // More buffers for smoother playback
    private let kBufferSize: UInt32 = 65536 // 64KB per buffer for less stuttering


    // MARK: - Decoder
    private var decoder: NativeAudioDecoder?
    private let decoderLock = NSLock()
    private var listener: MediaPlayerListener?

    // MARK: - Stream Configuration
    private var currentCodec: String = "flac"
    private var currentSampleRate: Int32 = 48000
    private var currentChannels: Int32 = 2
    private var currentBitDepth: Int32 = 16
    private var codecHeader: Data?

    // MARK: - State
    private var isPlaying = false
    /// True while local playback owns or is claiming the shared audio session.
    var isRenderingAudio: Bool {
        playbackGateLock.lock()
        let rendering = streamStarted || isPlaying
        playbackGateLock.unlock()
        return rendering
    }
    private var streamStarted = false
    // Play-intent gate (mirrors Android's shouldPlayAudio). While false — paused or
    // interrupted — incoming audio is dropped instead of (re)starting the queue, so
    // a packet still in the consumer pipeline can't undo an optimistic pause.
    private var shouldPlay = false
    private var routeLossSafetyInitialized = false
    private var routeLossPlaybackBlocked = false
    private var playbackSafetyGeneration: Int64 = 0
    // True only while we hold a server pause issued in response to an audio-session
    // interruption (phone call, Siri). On .ended we auto-resume the server only if
    // this is set — so we never spontaneously start playback that the user didn't
    // have running before the interruption.
    private var pausedByInterruption = false

    // MARK: - Logging
    // Routes through Kermit (NativeLog) so these reach the shareable in-memory buffer
    // and os.Logger
    private static let logTag = "NativeAudioController"
    private func logInfo(_ message: String) { NativeLog.shared.info(tag: Self.logTag, message: message) }
    private func logError(_ message: String) { NativeLog.shared.error(tag: Self.logTag, message: message) }
    private func logDebug(_ message: String) { NativeLog.shared.debug(tag: Self.logTag, message: message) }

    private func withAudioQueueLifecycle<T>(_ body: () -> T) -> T {
        if DispatchQueue.getSpecific(key: audioQueueLifecycleKey) != nil {
            return body()
        }
        return audioQueueLifecycleQueue.sync(execute: body)
    }

    override init() {
        super.init()
        audioQueueLifecycleQueue.setSpecific(key: audioQueueLifecycleKey, value: ())
        logDebug("Initialized")

        // Handle audio session interruptions (phone calls, Siri, alarms)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioSessionInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )
        // Handle route changes (headphones unplugged, Bluetooth disconnects)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    @objc private func handleAudioSessionInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }

        switch type {
        case .began:
            // System auto-pauses AudioQueue. Tell the server to pause too so playback
            // resumes from the same position afterwards instead of skipping ahead while
            // the call held the audio session.
            logInfo("Audio session interrupted")
            playbackGateLock.lock()
            let ownedPlayback = shouldPlay || streamStarted || isPlaying
            if ownedPlayback { pausedByInterruption = true }
            playbackGateLock.unlock()
            if ownedPlayback {
                logInfo("Pausing server playback due to interruption")
                remoteCommandHandler?.onCommand(
                    command: "pause",
                    source: "interruption",
                    explicitUserIntent: false
                )
            }
        case .ended:
            playbackGateLock.lock()
            let shouldResume = pausedByInterruption
            pausedByInterruption = false
            let routeBlocked = routeLossPlaybackBlocked
            playbackGateLock.unlock()
            guard shouldResume else { break }
            // We deliberately do not use .shouldResume here as it is not guaranteed
            // to be set even in cases it should be. As per Apple, it's a hint not
            // a contract. Instead we track for ourselves if we were interrupted,
            // and once control is handed back, if another app is now using the
            // audio device exclusively.
            if routeBlocked {
                logInfo("Interruption ended while route-loss hold is active — staying paused")
            } else if !AVAudioSession.sharedInstance().secondaryAudioShouldBeSilencedHint {
                logInfo("Resuming server playback after interruption")
                remoteCommandHandler?.onCommand(
                    command: "play",
                    source: "interruption",
                    explicitUserIntent: false
                )
            } else {
                logInfo("Another app holds audio — staying paused")
            }
        @unknown default:
            break
        }
    }

    @objc private func handleAudioRouteChange(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }

        if reason == .oldDeviceUnavailable {
            logInfo("Audio output device disconnected")
            let previousRoute = userInfo[AVAudioSessionRouteChangePreviousRouteKey]
                as? AVAudioSessionRouteDescription
            handleOldDeviceUnavailable(previousRoute: previousRoute)
        }
    }

    /// Pause when the active output route disappears. CarPlay loss additionally
    /// arms the durable route-loss gate so decoded or in-flight PCM cannot fall
    /// through to the phone speaker before the server pause lands. Non-CarPlay
    /// route losses still pause and tear down the queue, but do not latch the
    /// CarPlay safety gate; the user can resume normally on the new route.
    /// Idle route changes are ignored; repeated CarPlay route notifications are
    /// suppressed after the first gated transition.
    private func handleOldDeviceUnavailable(previousRoute: AVAudioSessionRouteDescription?) {
        let wasCarPlay = previousRoute.map { route in
            route.outputs.isEmpty || route.outputs.contains { $0.portType == .carAudio }
        } ?? true
        let prev = previousRoute?.outputs.first?.portType.rawValue ?? "unknown"
        audioQueueUseLock.lock()
        playbackGateLock.lock()
        let interrupted = pausedByInterruption
        let armed = shouldPlay || streamStarted || isPlaying || interrupted
        let blocked = routeLossPlaybackBlocked
        var routeLossGeneration = playbackSafetyGeneration
        if armed {
            playbackSafetyGeneration &+= 1
            routeLossGeneration = playbackSafetyGeneration
            // Close the write/start gate immediately for CarPlay only. Queue
            // teardown is serialized below, but must never delay the CarPlay
            // safety decision behind queue creation or startup. Non-CarPlay
            // route loss pauses/tears down without latching this durable gate.
            if wasCarPlay {
                routeLossPlaybackBlocked = true
            }
            shouldPlay = false
            streamStarted = false
            pausedByInterruption = false
        }
        playbackGateLock.unlock()
        audioQueueUseLock.unlock()

        guard armed else {
            logInfo("\(prev) disappeared while playback was idle — no hold armed")
            return
        }
        var routeLossAccepted = !wasCarPlay
        if wasCarPlay && !blocked {
            routeLossAccepted = KmpHelper.shared.recordCarPlayRouteLossHold(
                wasInterrupted: interrupted,
                nativeGeneration: routeLossGeneration
            )
        }
        guard !blocked else { return }
        guard routeLossAccepted else {
            logInfo("Ignoring stale CarPlay route loss after newer playback intent")
            return
        }
        let source = wasCarPlay && interrupted
            ? "carplay_route_loss_interruption"
            : (wasCarPlay ? "carplay_route_loss" : "route_loss")
        var applied = false
        withAudioQueueLifecycle {
            playbackGateLock.lock()
            let isCurrent = routeLossGeneration == playbackSafetyGeneration
            playbackGateLock.unlock()
            guard isCurrent else { return }

            bufferLock.lock()
            pcmBuffer.removeAll()
            bufferLock.unlock()
            stopAudioQueue()
            logInfo("\(prev) disappeared — pausing playback")
            remoteCommandHandler?.onCommand(
                command: "pause",
                source: source,
                explicitUserIntent: false
            )
            applied = true
        }
        if !applied {
            logInfo("Ignoring superseded route loss after newer playback intent")
        }
    }

    // MARK: - PlatformAudioPlayer Protocol

    func prepareStream(codec: String, sampleRate: Int32, channels: Int32, bitDepth: Int32, codecHeader: String?, listener: MediaPlayerListener) {
        logInfo("prepareStream - codec=\(codec), rate=\(sampleRate), ch=\(channels), bit=\(bitDepth)")

        self.listener = listener
        self.currentCodec = codec.lowercased()
        self.currentSampleRate = sampleRate
        self.currentChannels = channels
        self.currentBitDepth = bitDepth
        withAudioQueueLifecycle {
            playbackGateLock.lock()
            self.streamStarted = false
            self.shouldPlay = routeLossSafetyInitialized && !routeLossPlaybackBlocked
            playbackGateLock.unlock()
            stopAudioQueue()
        }

        // Decode codec header if present
        if let headerBase64 = codecHeader, let headerData = Data(base64Encoded: headerBase64) {
            self.codecHeader = headerData
            logDebug("Decoded codec header: \(headerData.count) bytes")
        } else {
            self.codecHeader = nil
        }

        // Clear buffers
        bufferLock.lock()
        pcmBuffer.removeAll()
        bufferLock.unlock()

        // Create decoder for codec
        do {
            let newDecoder = try AudioDecoderFactory.create(
                codec: currentCodec,
                sampleRate: Int(sampleRate),
                channels: Int(channels),
                bitDepth: Int(bitDepth),
                codecHeader: self.codecHeader
            )
            decoderLock.lock()
            decoder = newDecoder
            decoderLock.unlock()
            logInfo("Created decoder for \(codec)")
        } catch {
            logError("Failed to create decoder: \(error)")
            listener.onError(error: KotlinThrowable(message: error.localizedDescription))
            return
        }

        listener.onReady()
    }

    /// Called from Kotlin via efficient NSData bulk-copy path (avoids per-byte Swift interop).
    func writeRawPcmNSData(data: Data) {
        processAudioData(data)
    }

    /// Legacy path: still satisfies the PlatformAudioPlayer protocol but is no longer
    /// called from Kotlin (Kotlin always uses writeRawPcmNSData now).
    func writeRawPcm(data: KotlinByteArray) {
        let size = Int(data.size)
        var swiftData = Data(count: size)
        for i in 0..<size {
            swiftData[i] = UInt8(bitPattern: data.get(index: Int32(i)))
        }
        processAudioData(swiftData)
    }

    private func processAudioData(_ swiftData: Data) {
        // Suspended (paused / interrupted): drop in-flight audio rather than
        // restart the queue, so a packet still in the consumer pipeline can't
        // undo the pause before the server stops streaming.
        let accepted = withAudioQueueLifecycle { () -> Bool in
            playbackGateLock.lock()
            guard shouldPlay && !routeLossPlaybackBlocked else {
                playbackGateLock.unlock()
                return false
            }
            let needsStart = !streamStarted
            if needsStart { streamStarted = true }
            playbackGateLock.unlock()
            if needsStart {
                logDebug("First data received (\(swiftData.count) bytes)")
                NowPlayingCoordinator.shared.activatePlayback()
                startAudioQueue()
            }
            return true
        }
        guard accepted else { return }

        decoderLock.lock()
        defer { decoderLock.unlock() }

        guard let decoder = decoder else {
            logDebug("No decoder available — dropping packet")
            return
        }

        do {
            let pcmData = try decoder.decode(swiftData)
            playbackGateLock.lock()
            guard shouldPlay && !routeLossPlaybackBlocked else {
                playbackGateLock.unlock()
                return
            }
            bufferLock.lock()
            pcmBuffer.append(pcmData)
            bufferLock.unlock()
            playbackGateLock.unlock()
        } catch {
            logDebug("Decode error: \(error)")
        }
    }

    func stopRawPcmStream() {
        logInfo("Stopping stream")
        withAudioQueueLifecycle {
            playbackGateLock.lock()
            shouldPlay = false
            streamStarted = false
            playbackGateLock.unlock()
            stopAudioQueue()
        }

        bufferLock.lock()
        pcmBuffer.removeAll()
        bufferLock.unlock()
    }

    /// Tear down rather than `AudioQueuePause`: a paused queue replays its stale
    /// primed buffers on resume, then underruns. `shouldPlay = false` drops any
    /// in-flight audio so the consumer can't immediately rebuild the queue;
    /// resume then rebuilds clean on the next packet, like a cold start.
    func pauseSink() {
        logInfo("pauseSink")
        withAudioQueueLifecycle {
            playbackGateLock.lock()
            shouldPlay = false
            streamStarted = false
            playbackGateLock.unlock()
            tearDownQueue()
        }
    }

    /// Reactivating the session reclaims audio from another app that grabbed it.
    /// `shouldPlay = true` re-opens the write gate; the queue rebuilds on the next
    /// audio packet, or is started here if one still exists (gapless restart).
    func resumeSink() {
        logInfo("resumeSink")
        withAudioQueueLifecycle {
            playbackGateLock.lock()
            guard routeLossSafetyInitialized && !routeLossPlaybackBlocked else {
                playbackGateLock.unlock()
                logInfo("resumeSink suppressed by route-loss safety hold")
                return
            }
            shouldPlay = true
            isPlaying = true
            playbackGateLock.unlock()
            NowPlayingCoordinator.shared.activatePlayback()
            if let queue = audioQueue {
                playbackGateLock.lock()
                let mayStart = shouldPlay && routeLossSafetyInitialized && !routeLossPlaybackBlocked
                playbackGateLock.unlock()
                guard mayStart else {
                    tearDownQueue()
                    return
                }
                let status = AudioQueueStart(queue, nil)
                playbackGateLock.lock()
                let stillAllowed = shouldPlay && routeLossSafetyInitialized && !routeLossPlaybackBlocked
                isPlaying = status == noErr && stillAllowed
                playbackGateLock.unlock()
                if status != noErr || !stillAllowed {
                    tearDownQueue()
                }
            }
        }
    }

    func restoreRouteLossPlaybackBlock() {
        playbackGateLock.lock()
        routeLossSafetyInitialized = true
        routeLossPlaybackBlocked = true
        shouldPlay = false
        playbackGateLock.unlock()
        bufferLock.lock()
        pcmBuffer.removeAll()
        bufferLock.unlock()
    }

    func initializeRouteLossPlaybackSafety() {
        playbackGateLock.lock()
        routeLossSafetyInitialized = true
        playbackGateLock.unlock()
    }

    func isRouteLossPlaybackBlocked() -> Bool {
        playbackGateLock.lock()
        let blocked = routeLossPlaybackBlocked || !routeLossSafetyInitialized
        playbackGateLock.unlock()
        return blocked
    }

    func allowPlaybackAfterUserIntent() -> Int64 {
        return withAudioQueueLifecycle {
            audioQueueUseLock.lock()
            playbackGateLock.lock()
            playbackSafetyGeneration &+= 1
            routeLossSafetyInitialized = true
            routeLossPlaybackBlocked = false
            shouldPlay = true
            let generation = playbackSafetyGeneration
            playbackGateLock.unlock()
            audioQueueUseLock.unlock()
            return generation
        }
    }

    func authorizeVerifiedContinuityPlayback() -> Int64 {
        // Match route-loss lock ordering so a route callback already in progress wins.
        audioQueueUseLock.lock()
        playbackGateLock.lock()
        let session = AVAudioSession.sharedInstance()
        let carPlayAvailable = session.currentRoute.outputs.contains { $0.portType == .carAudio }
        let safe = routeLossSafetyInitialized &&
            routeLossPlaybackBlocked &&
            carPlayAvailable &&
            !session.secondaryAudioShouldBeSilencedHint
        guard safe else {
            playbackGateLock.unlock()
            audioQueueUseLock.unlock()
            return -1
        }
        playbackSafetyGeneration &+= 1
        routeLossPlaybackBlocked = false
        shouldPlay = true
        let generation = playbackSafetyGeneration
        playbackGateLock.unlock()
        audioQueueUseLock.unlock()
        return generation
    }

    /// Drop buffered PCM (track transition / playback-delay re-phase).
    func flush() {
        bufferLock.lock()
        pcmBuffer.removeAll()
        bufferLock.unlock()
    }

    func setVolume(volume: Int32) {
        withAudioQueueLifecycle {
            guard let queue = audioQueue else { return }
            let floatVolume = Float(volume) / 100.0
            AudioQueueSetParameter(queue, kAudioQueueParam_Volume, floatVolume)
        }
    }

    func setMuted(muted: Bool) {
        withAudioQueueLifecycle {
            guard let queue = audioQueue else { return }
            AudioQueueSetParameter(queue, kAudioQueueParam_Volume, muted ? 0.0 : 1.0)
        }
    }

    func dispose() {
        // The Now Playing surface is cleared by the track channel going null
        // (pipeline teardown removes the current item); no direct clear here.
        withAudioQueueLifecycle {
            playbackGateLock.lock()
            shouldPlay = false
            streamStarted = false
            playbackGateLock.unlock()
            stopAudioQueue()
        }
        decoderLock.lock()
        decoder = nil
        decoderLock.unlock()
    }

    // MARK: - AudioQueue Management

    private func startAudioQueue() {
        // Configure audio format (always output PCM)
        audioFormat.mSampleRate = Float64(currentSampleRate)
        audioFormat.mFormatID = kAudioFormatLinearPCM
        audioFormat.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked
        audioFormat.mFramesPerPacket = 1
        audioFormat.mChannelsPerFrame = UInt32(currentChannels)

        // FLAC decoder always outputs Int32 (scaled to full range).
        // PCM 24-bit is unpacked to Int32 by PCMPassthroughDecoder.
        // All other cases use the negotiated bit depth directly.
        let effectiveBitDepth: Int32
        if currentCodec == "flac" || currentBitDepth == 24 {
            effectiveBitDepth = 32
        } else {
            effectiveBitDepth = currentBitDepth
        }
        let bytesPerSample = effectiveBitDepth / 8

        audioFormat.mBitsPerChannel = UInt32(effectiveBitDepth)
        audioFormat.mBytesPerFrame = UInt32(currentChannels) * UInt32(bytesPerSample)
        audioFormat.mBytesPerPacket = audioFormat.mBytesPerFrame

        logDebug("Audio format - \(currentSampleRate)Hz, \(currentChannels)ch, \(effectiveBitDepth)bit")

        // Create AudioQueue
        let selfPointer = Unmanaged.passUnretained(self).toOpaque()

        var queue: AudioQueueRef?
        let status = AudioQueueNewOutput(
            &audioFormat,
            audioQueueCallback,
            selfPointer,
            nil,
            nil,
            0,
            &queue
        )

        guard status == noErr, let queue = queue else {
            logError("Failed to create AudioQueue: \(status)")
            return
        }

        audioQueueUseLock.lock()
        audioQueue = queue
        audioQueueUseLock.unlock()

        // Allocate and prime buffers
        for _ in 0..<kNumberOfBuffers {
            var buffer: AudioQueueBufferRef?
            let allocStatus = AudioQueueAllocateBuffer(queue, kBufferSize, &buffer)

            if allocStatus == noErr, let buffer = buffer {
                fillBuffer(queue: queue, buffer: buffer)
            }
        }

        // Route-loss notification closes this gate without waiting for this lifecycle queue.
        // Recheck immediately before and after AudioQueueStart so a route transition racing
        // queue creation cannot leave a queue running on the replacement output.
        playbackGateLock.lock()
        let mayStart = shouldPlay && routeLossSafetyInitialized && !routeLossPlaybackBlocked
        playbackGateLock.unlock()
        guard mayStart else {
            tearDownQueue()
            return
        }

        let startStatus = AudioQueueStart(queue, nil)
        playbackGateLock.lock()
        let stillAllowed = shouldPlay && routeLossSafetyInitialized && !routeLossPlaybackBlocked
        isPlaying = startStatus == noErr && stillAllowed
        if !isPlaying { streamStarted = false }
        playbackGateLock.unlock()

        if startStatus == noErr && stillAllowed {
            logInfo("AudioQueue started")
        } else {
            if startStatus != noErr {
                logError("Failed to start AudioQueue: \(startStatus)")
            } else {
                logInfo("AudioQueue start cancelled by route-loss gate")
            }
            tearDownQueue()
        }
    }

    private func stopAudioQueue() {
        tearDownQueue()
        playbackGateLock.lock()
        pausedByInterruption = false // Stream stopped — no auto-resume on .ended.
        playbackGateLock.unlock()
        AudioSessionCoordinator.shared.deactivatePlayback()
    }
    /// `AudioQueueStop(_, true)` discards enqueued hardware buffers, so a rebuilt
    /// queue never replays stale audio. Leaves `pausedByInterruption` untouched —
    /// a pause issued during `.began` must still auto-resume on `.ended`.
    private func tearDownQueue() {
        audioQueueUseLock.lock()
        let queue = audioQueue
        audioQueue = nil
        audioQueueUseLock.unlock()
        if let queue {
            AudioQueueStop(queue, true)
            AudioQueueDispose(queue, true)
        }
        playbackGateLock.lock()
        isPlaying = false
        playbackGateLock.unlock()
        logInfo("AudioQueue stopped")
    }

    fileprivate func fillBuffer(queue: AudioQueueRef, buffer: AudioQueueBufferRef) {
        // Queue ownership is detached before teardown. A callback that arrives after
        // detachment returns without touching a queue being stopped or disposed.
        audioQueueUseLock.lock()
        guard audioQueue == queue else {
            audioQueueUseLock.unlock()
            return
        }
        defer { audioQueueUseLock.unlock() }

        playbackGateLock.lock()
        let mayEnqueue = shouldPlay && routeLossSafetyInitialized && !routeLossPlaybackBlocked
        playbackGateLock.unlock()
        guard mayEnqueue else { return }

        // Get next PCM data from buffer
        bufferLock.lock()
        let pcmData = pcmBuffer.isEmpty ? nil : pcmBuffer.removeFirst()
        bufferLock.unlock()

        if let data = pcmData {
            // Copy PCM data to buffer
            let copySize = min(data.count, Int(buffer.pointee.mAudioDataBytesCapacity))
            _ = data.withUnsafeBytes { srcBytes in
                memcpy(buffer.pointee.mAudioData, srcBytes.baseAddress, copySize)
            }
            buffer.pointee.mAudioDataByteSize = UInt32(copySize)
        } else {
            // No data - output silence
            memset(buffer.pointee.mAudioData, 0, Int(buffer.pointee.mAudioDataBytesCapacity))
            buffer.pointee.mAudioDataByteSize = buffer.pointee.mAudioDataBytesCapacity
        }

        playbackGateLock.lock()
        let stillAllowed = shouldPlay && routeLossSafetyInitialized && !routeLossPlaybackBlocked
        playbackGateLock.unlock()
        guard stillAllowed else { return }

        // Re-enqueue while queue ownership is still held. Route-loss gate closure
        // takes the same short ownership lock, so no enqueue can follow it.
        AudioQueueEnqueueBuffer(queue, buffer, 0, nil)
    }

    // MARK: - Now Playing (Control Center / Lock Screen)

    private var remoteCommandHandler: RemoteCommandHandler?

    func setLongFormSeekIntervals(backSeconds: Int64, forwardSeconds: Int64) {
        NowPlayingCoordinator.shared.setLongFormSeekIntervals(
            backSeconds: backSeconds,
            forwardSeconds: forwardSeconds
        )
    }

    func setRemoteCommandHandler(handler: RemoteCommandHandler?) {
        self.remoteCommandHandler = handler

        NowPlayingCoordinator.shared.setCommandHandler { [weak self] command in
            self?.logInfo("Remote command: \(command)")
            self?.remoteCommandHandler?.onCommand(
                command: command,
                source: "remote",
                explicitUserIntent: false
            )
        }
    }
}

// MARK: - AudioQueue Callback

private let audioQueueCallback: AudioQueueOutputCallback = { userData, queue, buffer in
    guard let userData = userData else { return }

    let controller = Unmanaged<NativeAudioController>.fromOpaque(userData).takeUnretainedValue()
    controller.fillBuffer(queue: queue, buffer: buffer)
}

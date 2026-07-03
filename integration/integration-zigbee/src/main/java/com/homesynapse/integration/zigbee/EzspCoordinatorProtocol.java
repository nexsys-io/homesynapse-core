/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.integration.PermanentIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * EZSP implementation of {@link CoordinatorProtocol} (D-M92-6): version negotiation
 * with the tiered acceptance band, the single-in-flight paced command pipeline
 * (D-M92-5), the watchdog keepalive, and network formation/resume orchestration via
 * the {@link NetworkParameterStore} seam.
 *
 * <p><strong>Pacing (D-M92-5):</strong> exactly ONE EZSP command is outstanding at any
 * time — ASH beneath is stop-and-wait (window 1), and burst submission is the
 * documented ASH-collapse mode (the bench EmberZNet dump pins
 * {@code CONFIG_APS_ACK_TIMEOUT=1600} ms, from which the per-command timeout floor
 * derives). Commands queue on the {@link ReentrantLock} and execute strictly serially
 * on the submitting thread; each submission yields a {@link CompletableFuture}-style
 * result whose failure carries the per-command timeout.
 *
 * <p><strong>Version negotiation (AMD-96):</strong> runs exactly once per session,
 * before any other command, always in the legacy frame format (W8). Tiers: negotiated
 * 13–14 → accept (14 pins the wide {@code sl_status_t} seam, D-M92-4); 8–12 →
 * structured WARN {@code zigbee.ezsp_legacy_version}, then best-effort proceed;
 * below 8 → {@link PermanentIntegrationException} (frame format incompatible); above
 * 14 → {@link PermanentIntegrationException} (unknown frame dialect — EmberZNet 8.1+
 * negotiates v15+). The version truth is the negotiation response at stack init,
 * never an external registry (AMD-96/E6).
 *
 * <p><strong>D-M92-6 scope:</strong> {@code formNetwork}/{@code resumeNetwork}/
 * {@code permitJoin}/{@code ping} are implemented; {@code interview} (M9.3),
 * {@code sendZclFrame} (M9.4), and {@code topologyScan} (post-M9.4 §3.11 unit) are
 * stubbed with {@link UnsupportedOperationException} naming the completing milestone.
 *
 * <p>Thread-safe ({@link ReentrantLock} only, LTD-11): callers are virtual threads;
 * the lock also guards the NOT-thread-safe transport underneath. The watchdog
 * keepalive uses {@code tryLock}, so it can never interleave an in-flight command.
 *
 * @see CoordinatorProtocol
 * @see EzspAshTransport
 * @see NetworkFormation
 */
final class EzspCoordinatorProtocol implements CoordinatorProtocol {

    /** The negotiation opener — the frozen Wave-1 acceptance baseline (AMD-96/E1). */
    static final int PREFERRED_PROTOCOL_VERSION = 13;
    /** Below this the EZSP frame format is incompatible (Doc 08 §3.3). */
    static final int MINIMUM_PROTOCOL_VERSION = 8;
    /** The AMD-96 acceptance band lower edge; below it → WARN + best-effort. */
    static final int TARGET_PROTOCOL_VERSION = 13;
    /** The AMD-96 acceptance band upper edge (v14 = synthetic-tested, D-M92-4). */
    static final int MAX_SUPPORTED_PROTOCOL_VERSION = 14;
    /**
     * Per-command timeout floor: the bench EmberZNet dump pins
     * {@code CONFIG_APS_ACK_TIMEOUT=1600} ms (D-M92-5).
     */
    static final long COMMAND_TIMEOUT_FLOOR_MILLIS = 1600;
    /** Default per-command timeout: 2× the APS ACK timeout floor. */
    static final long DEFAULT_COMMAND_TIMEOUT_MILLIS = 2 * COMMAND_TIMEOUT_FLOOR_MILLIS;
    /** Watchdog keepalive fires after this much idle time (Doc 08 §3.3). */
    static final long KEEPALIVE_IDLE_MILLIS = 30_000;

    // EZSP frame IDs (UG100; re-derived against bellows commands.py before pinning).
    static final int FRAME_VERSION = 0x0000;
    static final int FRAME_NOP = 0x0005;
    static final int FRAME_NETWORK_INIT = 0x0017;
    static final int FRAME_NETWORK_STATE = 0x0018;
    static final int FRAME_START_SCAN = 0x001A;
    static final int FRAME_SCAN_COMPLETE_HANDLER = 0x001C;
    static final int FRAME_FORM_NETWORK = 0x001E;
    static final int FRAME_PERMIT_JOINING = 0x0022;
    static final int FRAME_GET_NETWORK_PARAMETERS = 0x0028;
    static final int FRAME_ENERGY_SCAN_RESULT_HANDLER = 0x0048;
    static final int FRAME_SET_INITIAL_SECURITY_STATE = 0x0068;

    /** EmberNetworkStatus JOINED_NETWORK (1 byte on every version — not widened). */
    static final int EMBER_NETWORK_STATUS_JOINED = 0x02;
    /** EmberStatus NOT_JOINED (v13 dialect; bench-verify at M9.4). */
    static final int EMBER_STATUS_NOT_JOINED = 0x93;
    /**
     * sl_status_t SL_STATUS_NOT_JOINED = 0x0017 (sl_status.h, mirrored in bellows
     * {@code sl_Status}; v14 dialect, synthetic-tested only). NOT 0x000B — that is
     * SL_STATUS_IS_WAITING (adversarial-derivation catch, 2026-07-03).
     */
    static final int SL_STATUS_NOT_JOINED = 0x17;

    /**
     * EmberInitialSecurityState bitmask for formation — the bellows/zigpy formation
     * baseline (§3.13 step 5): HAVE_PRECONFIGURED_KEY (0x0100) | HAVE_NETWORK_KEY
     * (0x0200) | TRUST_CENTER_GLOBAL_LINK_KEY (0x0004) | REQUIRE_ENCRYPTED_KEY
     * (0x0800, joining devices must request the network key encrypted under the TC
     * link key) | NO_FRAME_COUNTER_RESET (0x1000, frame-counter continuity on
     * re-form). The plaintext ZigBeeAlliance09 key is kept per §3.13 step 5; the
     * hashed-TCLK mode (0x0084) bellows adds on EZSP &gt; 4 is deferred to the M9.4
     * bench pass ([REVIEW] — security-posture election).
     */
    static final int INITIAL_SECURITY_BITMASK = 0x1B04;
    /** The well-known Trust Center link key "ZigBeeAlliance09" (§3.13 step 5). */
    private static final byte[] TC_LINK_KEY = {
        0x5A, 0x69, 0x67, 0x42, 0x65, 0x65, 0x41, 0x6C,
        0x6C, 0x69, 0x61, 0x6E, 0x63, 0x65, 0x30, 0x39
    };

    /** Energy-scan duration exponent (chosen constant; M9.3 config binds it). */
    static final int ENERGY_SCAN_DURATION_EXPONENT = 3;
    /** Radio TX power in dBm for formation (chosen constant; M9.3 config binds it). */
    static final int DEFAULT_RADIO_TX_POWER_DBM = 8;
    private static final long ENERGY_SCAN_COLLECT_TIMEOUT_MILLIS = 20_000;
    private static final byte[] NO_PARAMETERS = new byte[0];
    private static final int PERMIT_JOIN_MAX_SECONDS = 254;

    private static final Logger log =
            LoggerFactory.getLogger(EzspCoordinatorProtocol.class);

    private final EzspAshTransport transport;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<EzspFrame> pendingCallbacks = new ArrayDeque<>();
    private final NetworkFormation formation;

    private EzspCodec codec;
    private int negotiatedVersion = -1;
    private int sequence;
    private Instant lastActivity;
    private int keepaliveMisses;

    /**
     * Creates the protocol facade. Performs no I/O (INV-RF-03).
     *
     * @param transport the EZSP transport, never {@code null}
     * @param store the network parameter/key store seam, never {@code null}
     * @param clock the time source, never {@code null}
     */
    EzspCoordinatorProtocol(EzspAshTransport transport, NetworkParameterStore store,
            Clock clock) {
        this(transport, store, clock, new SecureRandom());
    }

    /**
     * Creates the protocol facade with an injected randomness source (deterministic
     * formation tests).
     *
     * @param transport the EZSP transport, never {@code null}
     * @param store the network parameter/key store seam, never {@code null}
     * @param clock the time source, never {@code null}
     * @param random the randomness source for network identity, never {@code null}
     */
    EzspCoordinatorProtocol(EzspAshTransport transport, NetworkParameterStore store,
            Clock clock, SecureRandom random) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(random, "random");
        this.formation = new NetworkFormation(new EzspOps(), store, random);
        this.lastActivity = clock.instant();
    }

    /**
     * Runs version negotiation — exactly once per session, before any other command
     * (W8: always the legacy frame format). Applies the AMD-96 tiered acceptance band
     * and pins the codec variant for the session.
     *
     * @throws PermanentIntegrationException if the negotiated version is below 8 or
     *                                       above the supported band
     * @throws EzspCommandTimeoutException if the NCP does not answer
     */
    void startSession() throws PermanentIntegrationException {
        lock.lock();
        try {
            if (negotiatedVersion > 0) {
                return;
            }
            EzspCodec.LegacyVersionResponse first =
                    executeVersionLocked(PREFERRED_PROTOCOL_VERSION);
            int ncpVersion = first.protocolVersion();
            if (ncpVersion < MINIMUM_PROTOCOL_VERSION) {
                throw new PermanentIntegrationException(
                        "zigbee.ezsp_version_unsupported",
                        "Zigbee coordinator EZSP protocol version " + ncpVersion
                                + " is not supported; minimum required: "
                                + MINIMUM_PROTOCOL_VERSION);
            }
            if (ncpVersion > MAX_SUPPORTED_PROTOCOL_VERSION) {
                throw new PermanentIntegrationException(
                        "zigbee.ezsp_version_unsupported",
                        "Zigbee coordinator EZSP protocol version " + ncpVersion
                                + " is newer than the supported band "
                                + TARGET_PROTOCOL_VERSION + "-"
                                + MAX_SUPPORTED_PROTOCOL_VERSION
                                + "; the frame dialect is unknown to this adapter");
            }
            if (ncpVersion != PREFERRED_PROTOCOL_VERSION) {
                // UG100: the host must re-send version at the NCP's version before
                // any other command is accepted.
                executeVersionLocked(ncpVersion);
            }
            if (ncpVersion < TARGET_PROTOCOL_VERSION) {
                log.warn("zigbee.ezsp_legacy_version: negotiated EZSP version {} is "
                                + "below the supported band {}-{}; update the "
                                + "coordinator firmware; proceeding best-effort",
                        ncpVersion, TARGET_PROTOCOL_VERSION,
                        MAX_SUPPORTED_PROTOCOL_VERSION);
            }
            codec = EzspCodec.forVersion(ncpVersion);
            transport.pinVersion(ncpVersion);
            negotiatedVersion = ncpVersion;
            lastActivity = clock.instant();
            log.info("EZSP session negotiated: protocolVersion={} stackType={} "
                            + "stackVersion=0x{}", ncpVersion, first.stackType(),
                    Integer.toHexString(first.stackVersion()));
        } finally {
            lock.unlock();
        }
    }

    /** Returns the negotiated protocol version, or {@code -1} before negotiation. */
    int negotiatedVersion() {
        lock.lock();
        try {
            return negotiatedVersion;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the count of watchdog keepalives that received no response. */
    int keepaliveMisses() {
        lock.lock();
        try {
            return keepaliveMisses;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets the session state so a transport reopen can renegotiate — the M9.1
     * relaunch discipline (irreversible resources must be recreatable on relaunch):
     * {@link EzspAshTransport#close()} discards the pinned codec, and UG100 requires
     * {@code version} to be the FIRST command after an NCP reset, so the M9.4
     * {@link PortWatchdog.ReopenAction} must call this after reopening the transport
     * and then {@link #startSession()} before any other command. Pending callbacks
     * from the dead session are discarded (stale after an NCP reset).
     */
    void resetSession() {
        lock.lock();
        try {
            negotiatedVersion = -1;
            codec = null;
            sequence = 0;
            keepaliveMisses = 0;
            pendingCallbacks.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Watchdog tick: sends a {@code nop()} keepalive when the session has been idle
     * for {@value #KEEPALIVE_IDLE_MILLIS} ms (Doc 08 §3.3). Never interleaves an
     * in-flight command ({@code tryLock}); a missed response feeds ASH-liveness
     * accounting ({@link #keepaliveMisses()}), never a direct throw. The M9.4 adapter
     * drives this from its scheduler.
     */
    void maybeSendKeepalive() {
        if (lock.isHeldByCurrentThread()) {
            // A reentrant tryLock would SUCCEED and nest the keepalive inside an
            // in-flight command on the same thread — guard makes "never
            // interleaves" unconditional, not just cross-thread.
            return;
        }
        if (!lock.tryLock()) {
            return; // a command is in flight — the watchdog never interleaves
        }
        try {
            if (negotiatedVersion <= 0) {
                return;
            }
            long idleMillis =
                    Duration.between(lastActivity, clock.instant()).toMillis();
            if (idleMillis < KEEPALIVE_IDLE_MILLIS) {
                return;
            }
            try {
                executeLocked(FRAME_NOP, NO_PARAMETERS,
                        DEFAULT_COMMAND_TIMEOUT_MILLIS);
            } catch (EzspCommandTimeoutException | TransportFailureException
                    | IllegalStateException e) {
                // The watchdog never throws: every failure mode (missed response,
                // transport failure mid-nop, post-FAILED fast-reject) feeds
                // ASH-liveness accounting for PortWatchdog.onAshLivenessLost.
                keepaliveMisses++;
                log.warn("zigbee.ezsp_keepalive_missed: {} (misses={})",
                        e.getMessage(), keepaliveMisses);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drains callbacks received while pumping command responses — the M9.3 ingestion
     * layer consumes these.
     *
     * @return the callbacks in arrival order
     */
    List<EzspFrame> drainPendingCallbacks() {
        lock.lock();
        try {
            List<EzspFrame> drained = new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
            return drained;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void formNetwork(NetworkParameters params) {
        Objects.requireNonNull(params, "params");
        lock.lock();
        try {
            requireNegotiated();
            formation.form(params);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Full §3.13 first-run formation: energy scan → two-tier channel selection →
     * random PAN identity → key generation/custody → formation → persistence. The
     * M9.3/M9.4 adapter calls this when the {@link NetworkParameterStore} holds no
     * parameters; the frozen {@link #formNetwork(NetworkParameters)} covers the
     * caller-selected-parameters path.
     *
     * @return the formed network's parameters (persisted)
     */
    NetworkParameters formNetworkAutomatically() {
        lock.lock();
        try {
            requireNegotiated();
            return formation.form();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void resumeNetwork() {
        try {
            resumeStored();
        } catch (PermanentIntegrationException e) {
            // The frozen CoordinatorProtocol surface declares no checked
            // exceptions. The M9.4 adapter calls resumeStored() directly and
            // propagates the permanent classification to the supervisor
            // (Doc 05 §3.7); a generic interface caller gets the same terminal
            // signal unchecked, cause-chained.
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Resumes the stored network, surfacing the permanent-failure classification
     * as its checked type — the M9.4 adapter path (adapter lifecycle methods
     * propagate {@link PermanentIntegrationException} to the supervisor, which
     * maps it to FAILED-no-retry per Doc 05 §3.7).
     *
     * @return the resumed network's parameters
     * @throws PermanentIntegrationException if the stored key is missing or the
     *                                       coordinator is on a different network
     *                                       (§3.13 mismatch — never re-form silently)
     */
    NetworkParameters resumeStored() throws PermanentIntegrationException {
        lock.lock();
        try {
            requireNegotiated();
            return formation.resume();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void permitJoin(int durationSeconds) {
        if (durationSeconds < 0 || durationSeconds > PERMIT_JOIN_MAX_SECONDS) {
            throw new IllegalArgumentException(
                    "durationSeconds must be 0-" + PERMIT_JOIN_MAX_SECONDS + ", got "
                            + durationSeconds);
        }
        EzspFrame response = execute(FRAME_PERMIT_JOINING,
                new byte[] {(byte) durationSeconds}, DEFAULT_COMMAND_TIMEOUT_MILLIS);
        requireSuccess("permitJoining", response);
    }

    @Override
    public void sendZclFrame(ZclFrame frame, IEEEAddress target) {
        throw new UnsupportedOperationException(
                "ZCL command dispatch is delivered in M9.4; the M9.2 transport/EZSP "
                        + "layer does not implement it");
    }

    @Override
    public InterviewResult interview(IEEEAddress device) {
        throw new UnsupportedOperationException(
                "device interview is delivered in M9.3; the M9.2 transport/EZSP "
                        + "layer does not implement it");
    }

    @Override
    public List<NeighborTableEntry> topologyScan() {
        throw new UnsupportedOperationException(
                "topology scan (Doc 08 §3.11) is delivered in a post-M9.4 breadth "
                        + "unit; the M9.2 transport/EZSP layer does not implement it");
    }

    @Override
    public boolean ping() {
        try {
            execute(FRAME_NOP, NO_PARAMETERS, DEFAULT_COMMAND_TIMEOUT_MILLIS);
            return true;
        } catch (EzspCommandTimeoutException e) {
            return false;
        }
    }

    /**
     * Submits a command to the single-in-flight pipeline (D-M92-5). The command
     * executes strictly serially on the submitting thread under the pipeline lock;
     * the returned future is complete on return — exceptionally on per-command
     * timeout or NCP failure. (M9.4's write-queue architecture can substitute a
     * dedicated executor behind this same seam.)
     *
     * @param frameId the EZSP frame ID
     * @param parameters the command parameters, never {@code null}
     * @param timeoutMillis the per-command timeout (floored at
     *                      {@value #COMMAND_TIMEOUT_FLOOR_MILLIS} ms)
     * @return the command's async-style result
     */
    CompletableFuture<EzspFrame> submit(int frameId, byte[] parameters,
            long timeoutMillis) {
        Objects.requireNonNull(parameters, "parameters");
        CompletableFuture<EzspFrame> result = new CompletableFuture<>();
        lock.lock();
        try {
            // Every failure mode — including a not-yet-negotiated session — is
            // delivered through the future, never thrown synchronously.
            requireNegotiated();
            result.complete(executeLocked(frameId, parameters, timeoutMillis));
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        } finally {
            lock.unlock();
        }
        return result;
    }

    private EzspFrame execute(int frameId, byte[] parameters, long timeoutMillis) {
        try {
            return submit(frameId, parameters, timeoutMillis).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    private EzspFrame executeLocked(int frameId, byte[] parameters,
            long timeoutMillis) {
        long timeout = Math.max(timeoutMillis, COMMAND_TIMEOUT_FLOOR_MILLIS);
        int seq = nextSequence();
        byte[] frame = codec.encodeCommand(seq, frameId, parameters);
        Instant start = clock.instant();
        transport.sendFrame(frame);
        Instant deadline = start.plusMillis(timeout);
        while (true) {
            long remaining = Duration.between(clock.instant(), deadline).toMillis();
            if (remaining <= 0) {
                throw new EzspCommandTimeoutException(frameId,
                        Duration.between(start, clock.instant()).toMillis());
            }
            Optional<EzspAshTransport.Inbound> inbound =
                    transport.receiveDecoded(remaining);
            if (inbound.isEmpty()) {
                continue;
            }
            EzspFrame received = inbound.get().frame();
            if (received.isCallback()) {
                pendingCallbacks.add(received);
                continue;
            }
            if (inbound.get().sequence() != seq) {
                log.warn("zigbee.ezsp_stale_response: sequence {} while awaiting {}; "
                        + "discarded", inbound.get().sequence(), seq);
                continue;
            }
            if (received.frameId() != frameId) {
                log.warn("zigbee.ezsp_stale_response: frameId 0x{} while awaiting "
                                + "0x{}; discarded",
                        Integer.toHexString(received.frameId()),
                        Integer.toHexString(frameId));
                continue;
            }
            lastActivity = clock.instant();
            return received;
        }
    }

    private EzspCodec.LegacyVersionResponse executeVersionLocked(int desiredVersion) {
        int seq = nextSequence();
        transport.sendFrame(EzspCodec.encodeLegacyVersionCommand(seq, desiredVersion));
        Instant start = clock.instant();
        Instant deadline = start.plusMillis(DEFAULT_COMMAND_TIMEOUT_MILLIS);
        while (true) {
            long remaining = Duration.between(clock.instant(), deadline).toMillis();
            if (remaining <= 0) {
                throw new EzspCommandTimeoutException(FRAME_VERSION,
                        Duration.between(start, clock.instant()).toMillis());
            }
            Optional<EzspAshTransport.Inbound> inbound =
                    transport.receiveDecoded(remaining);
            if (inbound.isEmpty()) {
                continue;
            }
            EzspFrame received = inbound.get().frame();
            if (received.frameId() != FRAME_VERSION
                    || inbound.get().sequence() != seq) {
                log.warn("zigbee.ezsp_stale_response: discarded frame 0x{} during "
                        + "version negotiation",
                        Integer.toHexString(received.frameId()));
                continue;
            }
            byte[] parameters = received.parameters();
            if (parameters.length < 4) {
                throw new EzspFormatException(
                        "version response parameters too short: "
                                + parameters.length + " bytes, expected 4");
            }
            return new EzspCodec.LegacyVersionResponse(
                    inbound.get().sequence(),
                    parameters[0] & 0xFF,
                    parameters[1] & 0xFF,
                    (parameters[2] & 0xFF) | ((parameters[3] & 0xFF) << 8));
        }
    }

    private void requireSuccess(String command, EzspFrame response) {
        int status = codec.decodeStatus(response.parameters(), 0);
        if (status != 0) {
            throw new EzspCommandException(String.format(
                    "%s rejected by the coordinator: status=0x%X", command, status),
                    status);
        }
    }

    private void requireNegotiated() {
        if (negotiatedVersion <= 0) {
            throw new IllegalStateException(
                    "EZSP session not started; call startSession() before commands");
        }
    }

    private int nextSequence() {
        sequence = (sequence + 1) & 0xFF;
        return sequence;
    }

    private boolean isNotJoinedStatus(int status) {
        if (negotiatedVersion >= 14) {
            return status == SL_STATUS_NOT_JOINED;
        }
        return status == EMBER_STATUS_NOT_JOINED;
    }

    /**
     * The {@link NetworkFormation.CoordinatorOps} binding over the EZSP pipeline.
     * All methods run under the pipeline lock (reentrant) via formation's callers.
     */
    private final class EzspOps implements NetworkFormation.CoordinatorOps {

        /** Binds the ops seam to the enclosing protocol's pipeline. */
        private EzspOps() {
        }

        @Override
        public Map<Integer, Integer> energyScan(List<Integer> channels) {
            int mask = 0;
            for (int channel : channels) {
                mask |= 1 << channel;
            }
            byte[] parameters = new byte[6];
            parameters[0] = 0x00; // EZSP_ENERGY_SCAN
            parameters[1] = (byte) (mask & 0xFF);
            parameters[2] = (byte) ((mask >> 8) & 0xFF);
            parameters[3] = (byte) ((mask >> 16) & 0xFF);
            parameters[4] = (byte) ((mask >> 24) & 0xFF);
            parameters[5] = (byte) ENERGY_SCAN_DURATION_EXPONENT;
            EzspFrame response = executeLocked(FRAME_START_SCAN, parameters,
                    DEFAULT_COMMAND_TIMEOUT_MILLIS);
            requireSuccess("startScan", response);

            Map<Integer, Integer> energyByChannel = new HashMap<>();
            Instant start = clock.instant();
            Instant deadline = start.plusMillis(ENERGY_SCAN_COLLECT_TIMEOUT_MILLIS);
            while (true) {
                long remaining =
                        Duration.between(clock.instant(), deadline).toMillis();
                if (remaining <= 0) {
                    throw new EzspCommandTimeoutException(
                            FRAME_SCAN_COMPLETE_HANDLER,
                            Duration.between(start, clock.instant()).toMillis());
                }
                Optional<EzspAshTransport.Inbound> inbound =
                        transport.receiveDecoded(remaining);
                if (inbound.isEmpty()) {
                    continue;
                }
                EzspFrame frame = inbound.get().frame();
                if (!frame.isCallback()) {
                    log.warn("zigbee.ezsp_stale_response: non-callback frame 0x{} "
                                    + "during energy scan; discarded",
                            Integer.toHexString(frame.frameId()));
                    continue;
                }
                if (frame.frameId() == FRAME_ENERGY_SCAN_RESULT_HANDLER) {
                    byte[] p = frame.parameters();
                    if (p.length >= 2) {
                        energyByChannel.put(p[0] & 0xFF, (int) p[1]); // signed dBm
                    }
                    continue;
                }
                if (frame.frameId() == FRAME_SCAN_COMPLETE_HANDLER) {
                    lastActivity = clock.instant();
                    return energyByChannel;
                }
                pendingCallbacks.add(frame); // unrelated callback: kept for M9.3
            }
        }

        @Override
        public void formNetwork(int channel, int panId, long extendedPanId,
                byte[] networkKey) {
            EzspFrame securityResponse = executeLocked(
                    FRAME_SET_INITIAL_SECURITY_STATE,
                    encodeInitialSecurityState(networkKey),
                    DEFAULT_COMMAND_TIMEOUT_MILLIS);
            requireSuccess("setInitialSecurityState", securityResponse);

            EzspFrame formResponse = executeLocked(FRAME_FORM_NETWORK,
                    encodeNetworkParameters(channel, panId, extendedPanId),
                    DEFAULT_COMMAND_TIMEOUT_MILLIS);
            requireSuccess("formNetwork", formResponse);
            // The EMBER_NETWORK_UP stackStatusHandler callback is deliberately not
            // awaited in M9.2 (its frame ID is unpinned by the derivation pass);
            // M9.4 bench acceptance hardens formation with the NETWORK_UP await.
        }

        @Override
        public boolean resumeFromNvram() {
            // networkInit takes an EmberNetworkInitStruct { bitmask: uint16 LE };
            // NETWORK_INIT_NO_OPTIONS = 0x0000. Flagged for bench verification at
            // M9.4 (the parameter arity is dialect-sensitive across EZSP versions).
            EzspFrame response = executeLocked(FRAME_NETWORK_INIT,
                    new byte[] {0x00, 0x00}, DEFAULT_COMMAND_TIMEOUT_MILLIS);
            int status = codec.decodeStatus(response.parameters(), 0);
            if (status == 0) {
                return true;
            }
            if (isNotJoinedStatus(status)) {
                return false;
            }
            throw new EzspCommandException(String.format(
                    "networkInit failed: status=0x%X", status), status);
        }

        @Override
        public CoordinatorNetwork currentNetwork() {
            EzspFrame stateResponse = executeLocked(FRAME_NETWORK_STATE,
                    NO_PARAMETERS, DEFAULT_COMMAND_TIMEOUT_MILLIS);
            byte[] stateParameters = stateResponse.parameters();
            if (stateParameters.length < 1) {
                throw new EzspFormatException("networkState response is empty");
            }
            // EmberNetworkStatus is 1 byte on every protocol version (not widened
            // by the v14 sl_status_t migration).
            boolean joined =
                    (stateParameters[0] & 0xFF) == EMBER_NETWORK_STATUS_JOINED;
            if (!joined) {
                return new CoordinatorNetwork(false, 0, 0, 0L);
            }
            EzspFrame parametersResponse = executeLocked(
                    FRAME_GET_NETWORK_PARAMETERS, NO_PARAMETERS,
                    DEFAULT_COMMAND_TIMEOUT_MILLIS);
            requireSuccess("getNetworkParameters", parametersResponse);
            byte[] p = parametersResponse.parameters();
            int offset = codec.statusWidthBytes() + 1; // status + nodeType
            if (p.length < offset + 20) {
                throw new EzspFormatException(
                        "getNetworkParameters response too short: " + p.length
                                + " bytes");
            }
            long extendedPanId = 0;
            for (int i = 0; i < 8; i++) {
                extendedPanId |= (long) (p[offset + i] & 0xFF) << (8 * i);
            }
            int panId = (p[offset + 8] & 0xFF) | ((p[offset + 9] & 0xFF) << 8);
            int channel = p[offset + 11] & 0xFF;
            return new CoordinatorNetwork(true, channel, panId, extendedPanId);
        }

        private byte[] encodeInitialSecurityState(byte[] networkKey) {
            if (networkKey.length != 16) {
                throw new IllegalArgumentException(
                        "networkKey must be 16 bytes, got " + networkKey.length);
            }
            byte[] struct = new byte[43];
            struct[0] = (byte) (INITIAL_SECURITY_BITMASK & 0xFF);
            struct[1] = (byte) ((INITIAL_SECURITY_BITMASK >> 8) & 0xFF);
            System.arraycopy(TC_LINK_KEY, 0, struct, 2, 16);
            System.arraycopy(networkKey, 0, struct, 18, 16);
            struct[34] = 0; // networkKeySequenceNumber
            // preconfiguredTrustCenterEui64: zeros (we are the trust center)
            return struct;
        }

        private byte[] encodeNetworkParameters(int channel, int panId,
                long extendedPanId) {
            byte[] struct = new byte[20];
            for (int i = 0; i < 8; i++) {
                struct[i] = (byte) ((extendedPanId >> (8 * i)) & 0xFF);
            }
            struct[8] = (byte) (panId & 0xFF);
            struct[9] = (byte) ((panId >> 8) & 0xFF);
            struct[10] = (byte) DEFAULT_RADIO_TX_POWER_DBM;
            struct[11] = (byte) channel;
            struct[12] = 0; // joinMethod: USE_MAC_ASSOCIATION
            struct[13] = 0; // nwkManagerId LE low
            struct[14] = 0; // nwkManagerId LE high
            struct[15] = 0; // nwkUpdateId
            int channels = 1 << channel;
            struct[16] = (byte) (channels & 0xFF);
            struct[17] = (byte) ((channels >> 8) & 0xFF);
            struct[18] = (byte) ((channels >> 16) & 0xFF);
            struct[19] = (byte) ((channels >> 24) & 0xFF);
            return struct;
        }
    }
}

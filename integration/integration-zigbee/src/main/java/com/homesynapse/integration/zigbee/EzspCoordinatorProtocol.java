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
import java.util.function.IntFunction;
import java.util.function.Predicate;

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
 * <p><strong>Version negotiation (AMD-96, band narrowed by the M9.4 consolidated
 * amendment):</strong> runs exactly once per session, before any other command,
 * always in the legacy frame format (W8). Tiers: negotiated 13 → accept; 8–12 →
 * structured WARN {@code zigbee.ezsp_legacy_version}, then best-effort proceed;
 * below 8 → {@link PermanentIntegrationException} (frame format incompatible);
 * above 13 → {@link PermanentIntegrationException} (unknown frame dialect — the
 * v14 0x0034/0x0045 dialect awaits Wave-2 characterization; the {@code decodeStatus}
 * width seam stays, D-M92-4). The version truth is the negotiation response at
 * stack init, never an external registry (AMD-96/E6).
 *
 * <p><strong>Scope (D-M92-6, updated M9.4a):</strong> {@code formNetwork}/
 * {@code resumeNetwork}/{@code permitJoin}/{@code ping} (M9.2), {@code interview}
 * (M9.3 — one attempt per call; retry/sleepy orchestration is
 * {@link PendingInterviewQueue}'s), and {@code sendZclFrame} (M9.4a — the command
 * write path over the bench-proven v13 unicast) are implemented;
 * {@code topologyScan} (post-M9.4 §3.11 unit) remains stubbed with
 * {@link UnsupportedOperationException} naming the completing milestone.
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
    /**
     * The acceptance band upper edge — narrowed 14 &rarr; 13 by the M9.4 consolidated
     * amendment (correcting AMD-96's edge): the v14 0x0034/0x0045 frame dialect is
     * uncharacterized on owned silicon, and half-right v14 support is a deaf radio
     * that looks paired (never-false-ALIVE). Acceptance stays ==13 until the Wave-2
     * v14-batch unit characterizes the dialect; the {@code decodeStatus} width seam
     * is untouched (synthetic-tested, D-M92-4).
     */
    static final int MAX_SUPPORTED_PROTOCOL_VERSION = 13;
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
    // M9.3 interview frames (bellows commands.py, v4 lineage inherited by v13;
    // re-derived 2026-07-03 per the double-derivation discipline).
    static final int FRAME_SEND_UNICAST = 0x0034;
    static final int FRAME_INCOMING_MESSAGE_HANDLER = 0x0045;
    static final int FRAME_LOOKUP_NODE_ID_BY_EUI64 = 0x0060;

    /** The ZDO/ZDP profile id (endpoint 0). */
    static final int ZDO_PROFILE_ID = 0x0000;
    /** The Home Automation profile id. */
    static final int HA_PROFILE_ID = 0x0104;
    /**
     * EmberApsOption RETRY (0x0040) | ENABLE_ROUTE_DISCOVERY (0x0100) — the
     * bellows unicast baseline for reliable interview exchanges.
     */
    static final int APS_OPTIONS_RETRY_ROUTE_DISCOVERY = 0x0140;
    /** EmberNodeId broadcast/unknown sentinel from lookupNodeIdByEui64. */
    static final int NODE_ID_UNKNOWN = 0xFFFF;
    /** Basic-cluster identity attributes read at interview step 5 (Doc 08 §3.4). */
    static final int[] BASIC_IDENTITY_ATTRIBUTES = {0x0004, 0x0005, 0x0007, 0x4000};
    /**
     * The pending-callback bound (§G): the deque is drained by the ingestion
     * cycle; a chatty network between drains must never grow it unbounded.
     * Overflow drops the OLDEST frame with a WARN and a running count — newest
     * state observations are the ones worth keeping.
     */
    static final int MAX_PENDING_CALLBACKS = 1024;

    /**
     * The EZSP {@code stackStatusHandler} callback frame id (bellows-derived;
     * BENCH-VERIFY — synthetic-tested until silicon, the P22/F-1 discipline).
     * M9.4b §5.3: form/resume returning OK does not mean the stack is up.
     */
    static final int FRAME_STACK_STATUS_HANDLER = 0x0019;

    /**
     * EmberStatus NETWORK_UP (0x90, v13 1-byte dialect; bellows-derived,
     * BENCH-VERIFY). The {@code stackStatusHandler} payload byte that ends the
     * §5.3 await — a radio we won't lie about (never-false-ALIVE).
     */
    static final int EMBER_NETWORK_UP = 0x90;

    /** The §5.3 NETWORK_UP await window (chosen constant, M9.4b). */
    static final long NETWORK_UP_TIMEOUT_MS = 10_000;

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
     * baseline (§3.13 step 5) PLUS the hashed-TCLK mode: HAVE_PRECONFIGURED_KEY
     * (0x0100) | HAVE_NETWORK_KEY (0x0200) | TRUST_CENTER_USES_HASHED_LINK_KEY
     * (0x0084 — includes TRUST_CENTER_GLOBAL_LINK_KEY 0x0004) | REQUIRE_ENCRYPTED_KEY
     * (0x0800, joining devices must request the network key encrypted under the TC
     * link key) | NO_FRAME_COUNTER_RESET (0x1000, frame-counter continuity on
     * re-form).
     *
     * <p><strong>The security-posture election is RULED</strong> (Nick, 2026-07-04,
     * pm-handoff v18 beat 2 — SD-5, verbatim): "Elect hashed TCLK for the M9.4
     * formation, and if the bench shows join instability attributable to it, drop to
     * plain for Wave-1 with the reason recorded and a W2 row — evidence-first, one
     * variable at a time, characterized before any user network exists rather than
     * after." The fallback is a one-constant revert of this value to {@code 0x1B04}
     * (plain), carried in the bench protocol — never a runtime branch. The
     * preconfigured key stays the well-known ZigBeeAlliance09 (§3.13 step 5) — see
     * the M9.4b completion report's bellows re-derivation note ([REVIEW]: bellows
     * supplies a GENERATED random seed under {@code use_hashed_tclk}).</p>
     */
    static final int INITIAL_SECURITY_BITMASK = 0x1B84;
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
    private int zdoSequence;
    private Instant lastActivity;
    private int keepaliveMisses;
    private long droppedCallbacks;

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
                                + "; the frame dialect is unknown to this adapter. "
                                + "Supported coordinator firmware: EmberZNet 7.4.x "
                                + "(EZSP v13) — reflash per the AMD-96 contingency, or "
                                + "await the v14 dialect characterization.");
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
     * layer consumes these BEFORE live NCP interaction each cycle (§G
     * bound-and-drain; the queue itself is bounded at
     * {@value #MAX_PENDING_CALLBACKS} with a drop-oldest overflow policy).
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

    /** Returns the count of callbacks dropped at the queue bound (§G). */
    long droppedCallbacks() {
        lock.lock();
        try {
            return droppedCallbacks;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The NETWORK_UP await (M9.4b §5.3): form/resume returning OK does not mean the
     * stack is fully up — presenting a deaf radio as paired is the never-false-ALIVE
     * class. Reads inbound frames under the pipeline lock until
     * {@code stackStatusHandler} reports {@link #EMBER_NETWORK_UP} or the
     * {@value #NETWORK_UP_TIMEOUT_MS} ms window closes; unrelated callbacks are
     * preserved for ingestion. Both frame constants are BENCH-VERIFY
     * (bellows-derived; synthetic-tested until silicon).
     *
     * @throws IllegalStateException on timeout — classifies TRANSIENT at the
     *         supervisor (a stack that did not come up is retryable; a radio we
     *         won't lie about)
     */
    void awaitNetworkUp() {
        lock.lock();
        try {
            // The signal may ALREADY be buffered: a stackStatusHandler arriving
            // during the resume exchanges (networkInit → getNetworkParameters) is
            // enqueued by that command's own response loop — the await must not
            // deafly re-read the transport past an answered radio.
            if (pendingCallbacks.removeIf(frame ->
                    frame.frameId() == FRAME_STACK_STATUS_HANDLER
                            && frame.parameters().length >= 1
                            && (frame.parameters()[0] & 0xFF) == EMBER_NETWORK_UP)) {
                log.info("zigbee.network_up: stackStatusHandler reported "
                        + "EMBER_NETWORK_UP (buffered)");
                return;
            }
            Instant deadline = clock.instant().plusMillis(NETWORK_UP_TIMEOUT_MS);
            while (true) {
                long remaining =
                        Duration.between(clock.instant(), deadline).toMillis();
                if (remaining <= 0) {
                    throw new IllegalStateException(String.format(
                            "zigbee network did not report NETWORK_UP within %d ms",
                            NETWORK_UP_TIMEOUT_MS));
                }
                Optional<EzspAshTransport.Inbound> inbound =
                        transport.receiveDecoded(remaining);
                if (inbound.isEmpty()) {
                    continue;
                }
                EzspFrame frame = inbound.get().frame();
                byte[] parameters = frame.parameters();
                if (frame.frameId() == FRAME_STACK_STATUS_HANDLER
                        && parameters.length >= 1
                        && (parameters[0] & 0xFF) == EMBER_NETWORK_UP) {
                    lastActivity = clock.instant();
                    log.info("zigbee.network_up: stackStatusHandler reported "
                            + "EMBER_NETWORK_UP");
                    return;
                }
                enqueueCallbackLocked(frame);   // unrelated callback: kept for ingestion
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * The production inbound pump (M9.4b §5.1): unsolicited frames reach the protocol
     * only while it reads (the single-in-flight pipeline), so the adapter's production
     * cycle parks ON the serial read itself — the read IS the park (no sleep; the
     * dedicated SERIAL platform thread blocks on the bounded read, LTD-01/W4).
     * Collected callbacks feed the next ingestion drain. Skips silently when a command
     * is in flight — that command's own response loop collects callbacks.
     *
     * @param maxWaitMillis the bounded read window (the cycle cadence)
     */
    void pumpInbound(long maxWaitMillis) {
        if (lock.isHeldByCurrentThread() || !lock.tryLock()) {
            return; // a command is in flight — its response loop collects callbacks
        }
        try {
            if (negotiatedVersion <= 0) {
                return;
            }
            Optional<EzspAshTransport.Inbound> inbound =
                    transport.receiveDecoded(maxWaitMillis);
            if (inbound.isEmpty()) {
                return;
            }
            EzspFrame frame = inbound.get().frame();
            if (frame.isCallback()) {
                enqueueCallbackLocked(frame);
                lastActivity = clock.instant();
            } else {
                log.warn("zigbee.stray_response_dropped: frameId=0x{} arrived with "
                                + "no command in flight",
                        Integer.toHexString(frame.frameId()));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Enqueues a callback under the {@value #MAX_PENDING_CALLBACKS} bound,
     * dropping the oldest on overflow (WARN + running count) — the §G policy:
     * an un-drained deque on a chatty network must degrade loudly, never grow
     * into a slow memory leak. Callers hold the pipeline lock.
     */
    private void enqueueCallbackLocked(EzspFrame frame) {
        if (pendingCallbacks.size() >= MAX_PENDING_CALLBACKS) {
            pendingCallbacks.pollFirst();
            droppedCallbacks++;
            log.warn("zigbee.callback_queue_overflow: pending callbacks at the {} "
                            + "bound; oldest dropped (total dropped: {})",
                    MAX_PENDING_CALLBACKS, droppedCallbacks);
        }
        pendingCallbacks.addLast(frame);
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
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(target, "target");
        lock.lock();
        try {
            requireNegotiated();
            // The frozen surface is no-throws/void: rejection is already WARN-logged
            // by the unicast path; the M9.4a command handler uses the package-private
            // boolean seam below to render an honest command_result instead.
            sendZclFrameLocked(frame, lookupNetworkAddress(target));
        } finally {
            lock.unlock();
        }
    }

    /**
     * The M9.4a command-dispatch seam (§3.10): sends one ZCL frame as an APS
     * unicast and reports NCP acceptance — the failure surface the zigbee
     * {@code CommandHandler} converts into an honest {@code command_result}
     * (the frozen {@link CoordinatorProtocol} surface gains no new throws).
     *
     * @param frame the ZCL frame, never {@code null}
     * @param networkAddress the target's 16-bit network address
     * @return {@code true} if the NCP accepted the frame for transmission
     */
    boolean sendZclFrame(ZclFrame frame, int networkAddress) {
        Objects.requireNonNull(frame, "frame");
        lock.lock();
        try {
            requireNegotiated();
            return sendZclFrameLocked(frame, networkAddress);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encodes the ZCL header ([fc][mfr LE]?[tsn][commandId]) + payload and rides the
     * bench-proven v13 unicast path — the wire layout is {@link #sendUnicastLocked}'s,
     * never re-implemented. TSN and APS sequence follow the interview-path convention
     * ({@code nextZdoSequenceLocked()} mirrored into both).
     */
    private boolean sendZclFrameLocked(ZclFrame frame, int networkAddress) {
        int tsn = nextZdoSequenceLocked();
        byte[] payload = frame.payload();
        boolean manufacturerSpecific = frame.manufacturerCode() > 0;
        int headerLength = manufacturerSpecific ? 5 : 3;
        byte[] zcl = new byte[headerLength + payload.length];
        int frameControl = frame.isClusterSpecific() ? 0x01 : 0x00;
        if (manufacturerSpecific) {
            frameControl |= 0x04;
            zcl[1] = (byte) (frame.manufacturerCode() & 0xFF);
            zcl[2] = (byte) ((frame.manufacturerCode() >> 8) & 0xFF);
        }
        zcl[0] = (byte) frameControl;
        zcl[headerLength - 2] = (byte) tsn;
        zcl[headerLength - 1] = (byte) frame.commandId();
        System.arraycopy(payload, 0, zcl, headerLength, payload.length);
        return sendUnicastLocked(networkAddress, HA_PROFILE_ID, frame.clusterId(),
                frame.sourceEndpoint(), frame.destinationEndpoint(), tsn, zcl);
    }

    /**
     * Runs ONE interview attempt (Doc 08 §3.4 steps 2–5) through the ZDO/ZCL
     * binding of the {@link InterviewOps} seam. The 3-retry/backoff ladder and
     * the sleepy park/resume machine live in {@link PendingInterviewQueue} —
     * driven by the ingestion cycle, never by sleeping here.
     *
     * <p>Failure reporting follows the frozen no-throws surface's precedent
     * ({@code resumeNetwork()}): a result that IS constructible reports failure
     * through {@link InterviewResult#interviewStatus()} (PARTIAL); an attempt
     * that gathered no endpoints has no constructible result (the record pins a
     * non-empty endpoint list) and surfaces as an unchecked
     * {@link IllegalStateException}. Never a new checked throw on the frozen
     * surface (the M9.2 seam map).
     */
    @Override
    public InterviewResult interview(IEEEAddress device) {
        Objects.requireNonNull(device, "device");
        int networkAddress = lookupNetworkAddress(device);
        InterviewAttempt attempt =
                new InterviewStateMachine(new EzspInterviewOps(), clock)
                        .attempt(device, networkAddress);
        return attempt.toInterviewResult().orElseThrow(() -> new IllegalStateException(
                "Interview for device " + device + " gathered no endpoint metadata"
                        + " (failed step: " + attempt.failedStep() + "); retry and"
                        + " sleepy resume are the pending-interview queue's"));
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
                enqueueCallbackLocked(received);
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

    // ── M9.3 interview binding (ZDO/ZCL over sendUnicast) ──────────────────

    /**
     * Resolves an IEEE address to its current 16-bit network address via
     * {@code lookupNodeIdByEui64} (0x0060). The v13 reply is a bare
     * {@code EmberNodeId} (the bellows v4-lineage shape, no leading status);
     * the v14 dialect of this reply is bench-verified at M9.4.
     */
    // Package-private (M9.4a): the command handler's F-6 re-resolution seam.
    int lookupNetworkAddress(IEEEAddress device) {
        lock.lock();
        try {
            requireNegotiated();
            byte[] eui64 = new byte[8];
            long value = device.value();
            for (int i = 0; i < 8; i++) {
                eui64[i] = (byte) (value >> (8 * i));
            }
            EzspFrame response = executeLocked(FRAME_LOOKUP_NODE_ID_BY_EUI64,
                    eui64, DEFAULT_COMMAND_TIMEOUT_MILLIS);
            byte[] parameters = response.parameters();
            if (parameters.length < 2) {
                throw new EzspFormatException(
                        "lookupNodeIdByEui64 response too short: "
                                + parameters.length + " bytes, expected 2");
            }
            int nodeId = (parameters[0] & 0xFF) | ((parameters[1] & 0xFF) << 8);
            if (nodeId == NODE_ID_UNKNOWN) {
                throw new IllegalStateException("Device " + device
                        + " is not in the coordinator address table; interview "
                        + "requires a joined device");
            }
            return nodeId;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sends one APS unicast under the pipeline lock.
     *
     * @return {@code true} if the NCP accepted the frame for transmission
     */
    private boolean sendUnicastLocked(int networkAddress, int profileId,
            int clusterId, int sourceEndpoint, int destinationEndpoint,
            int apsSequence, byte[] message) {
        byte[] parameters = new byte[16 + message.length];
        parameters[0] = 0x00; // EMBER_OUTGOING_DIRECT
        parameters[1] = (byte) (networkAddress & 0xFF);
        parameters[2] = (byte) ((networkAddress >> 8) & 0xFF);
        parameters[3] = (byte) (profileId & 0xFF);
        parameters[4] = (byte) ((profileId >> 8) & 0xFF);
        parameters[5] = (byte) (clusterId & 0xFF);
        parameters[6] = (byte) ((clusterId >> 8) & 0xFF);
        parameters[7] = (byte) sourceEndpoint;
        parameters[8] = (byte) destinationEndpoint;
        parameters[9] = (byte) (APS_OPTIONS_RETRY_ROUTE_DISCOVERY & 0xFF);
        parameters[10] = (byte) ((APS_OPTIONS_RETRY_ROUTE_DISCOVERY >> 8) & 0xFF);
        parameters[11] = 0; // groupId LE low
        parameters[12] = 0; // groupId LE high
        parameters[13] = (byte) apsSequence;
        parameters[14] = (byte) apsSequence; // messageTag: mirrors the sequence
        parameters[15] = (byte) message.length;
        System.arraycopy(message, 0, parameters, 16, message.length);
        EzspFrame response = executeLocked(FRAME_SEND_UNICAST, parameters,
                DEFAULT_COMMAND_TIMEOUT_MILLIS);
        int status = codec.decodeStatus(response.parameters(), 0);
        if (status != 0) {
            log.warn("zigbee.aps_unicast_rejected: cluster=0x{} nwk=0x{} "
                            + "status=0x{}", Integer.toHexString(clusterId),
                    Integer.toHexString(networkAddress),
                    Integer.toHexString(status));
            return false;
        }
        return true;
    }

    /**
     * Pumps inbound frames under the pipeline lock until a matching
     * {@code incomingMessageHandler} arrives or the deadline passes. Every
     * non-matching callback is preserved for the ingestion drain.
     */
    private Optional<byte[]> awaitIncomingLocked(int profileId, int clusterId,
            Predicate<byte[]> messageMatcher, Instant deadline) {
        while (true) {
            long remaining = Duration.between(clock.instant(), deadline).toMillis();
            if (remaining <= 0) {
                return Optional.empty();
            }
            Optional<EzspAshTransport.Inbound> inbound =
                    transport.receiveDecoded(remaining);
            if (inbound.isEmpty()) {
                continue;
            }
            EzspFrame frame = inbound.get().frame();
            if (!frame.isCallback()) {
                log.warn("zigbee.ezsp_stale_response: non-callback frame 0x{} "
                                + "while awaiting an incoming message; discarded",
                        Integer.toHexString(frame.frameId()));
                continue;
            }
            if (frame.frameId() == FRAME_INCOMING_MESSAGE_HANDLER) {
                Optional<EzspIncomingMessage> message =
                        EzspIncomingMessage.parse(frame.parameters());
                if (message.isPresent()
                        && message.get().profileId() == profileId
                        && message.get().clusterId() == clusterId
                        && messageMatcher.test(message.get().message())) {
                    lastActivity = clock.instant();
                    return Optional.of(message.get().message());
                }
            }
            enqueueCallbackLocked(frame);
        }
    }

    private int nextZdoSequenceLocked() {
        zdoSequence = (zdoSequence + 1) & 0xFF;
        return zdoSequence;
    }

    /**
     * The {@link InterviewOps} binding over the EZSP pipeline (Doc 08 §3.4
     * steps 2–5). Each step is one lock-scoped ZDO/ZCL exchange: the lock is
     * released between steps so a 60 s interview never starves other callers.
     * A step timeout or NCP rejection is an empty result — the retry policy is
     * the queue's, and no checked exception crosses the seam.
     */
    private final class EzspInterviewOps implements InterviewOps {

        /** Binds the ops seam to the enclosing protocol's pipeline. */
        private EzspInterviewOps() {
        }

        @Override
        public Optional<NodeDescriptor> nodeDescriptor(int networkAddress,
                long timeoutMillis) {
            return zdoExchange(networkAddress, ZdoCodec.CLUSTER_NODE_DESC_REQ,
                    ZdoCodec.CLUSTER_NODE_DESC_RSP,
                    tsn -> ZdoCodec.encodeAddressRequest(tsn, networkAddress),
                    timeoutMillis)
                    .flatMap(ZdoCodec::parseNodeDescriptorResponse);
        }

        @Override
        public Optional<List<Integer>> activeEndpoints(int networkAddress,
                long timeoutMillis) {
            return zdoExchange(networkAddress, ZdoCodec.CLUSTER_ACTIVE_EP_REQ,
                    ZdoCodec.CLUSTER_ACTIVE_EP_RSP,
                    tsn -> ZdoCodec.encodeAddressRequest(tsn, networkAddress),
                    timeoutMillis)
                    .flatMap(ZdoCodec::parseActiveEndpointsResponse);
        }

        @Override
        public Optional<EndpointDescriptor> simpleDescriptor(int networkAddress,
                int endpoint, long timeoutMillis) {
            return zdoExchange(networkAddress, ZdoCodec.CLUSTER_SIMPLE_DESC_REQ,
                    ZdoCodec.CLUSTER_SIMPLE_DESC_RSP,
                    tsn -> ZdoCodec.encodeSimpleDescriptorRequest(tsn,
                            networkAddress, endpoint),
                    timeoutMillis)
                    .flatMap(ZdoCodec::parseSimpleDescriptorResponse);
        }

        @Override
        public Optional<BasicInfo> readBasic(int networkAddress, int endpoint,
                long timeoutMillis) {
            lock.lock();
            try {
                requireNegotiated();
                Instant deadline = clock.instant().plusMillis(timeoutMillis);
                int tsn = nextZdoSequenceLocked();
                byte[] zcl = ZclCodec.encodeReadAttributes(tsn,
                        BASIC_IDENTITY_ATTRIBUTES);
                if (!sendUnicastLocked(networkAddress, HA_PROFILE_ID, 0x0000, 1,
                        endpoint, tsn, zcl)) {
                    return Optional.empty();
                }
                return awaitIncomingLocked(HA_PROFILE_ID, 0x0000, message -> {
                    Optional<ZclCodec.ZclHeader> header =
                            ZclCodec.parseHeader(message);
                    return header.isPresent()
                            && !header.get().clusterSpecific()
                            && header.get().commandId()
                                    == ZclCodec.COMMAND_READ_ATTRIBUTES_RESPONSE
                            && header.get().transactionSequence() == tsn;
                }, deadline).flatMap(EzspInterviewOps::toBasicInfo);
            } catch (EzspCommandTimeoutException e) {
                log.warn("zigbee.interview_step_timeout: Basic read nwk=0x{} "
                                + "endpoint={}: {}",
                        Integer.toHexString(networkAddress), endpoint,
                        e.getMessage());
                return Optional.empty();
            } finally {
                lock.unlock();
            }
        }

        private Optional<byte[]> zdoExchange(int networkAddress, int requestCluster,
                int responseCluster, IntFunction<byte[]> requestForTsn,
                long timeoutMillis) {
            lock.lock();
            try {
                requireNegotiated();
                Instant deadline = clock.instant().plusMillis(timeoutMillis);
                int tsn = nextZdoSequenceLocked();
                byte[] request = requestForTsn.apply(tsn);
                if (!sendUnicastLocked(networkAddress, ZDO_PROFILE_ID,
                        requestCluster, 0, 0, tsn, request)) {
                    return Optional.empty();
                }
                return awaitIncomingLocked(ZDO_PROFILE_ID, responseCluster,
                        message -> message.length > 0
                                && (message[0] & 0xFF) == tsn,
                        deadline);
            } catch (EzspCommandTimeoutException e) {
                log.warn("zigbee.interview_step_timeout: ZDO cluster=0x{} "
                                + "nwk=0x{}: {}",
                        Integer.toHexString(requestCluster),
                        Integer.toHexString(networkAddress), e.getMessage());
                return Optional.empty();
            } finally {
                lock.unlock();
            }
        }

        private static Optional<BasicInfo> toBasicInfo(byte[] message) {
            Optional<ZclCodec.ZclHeader> header = ZclCodec.parseHeader(message);
            if (header.isEmpty()) {
                return Optional.empty();
            }
            Map<Integer, Object> attributes = ZclCodec.parseReadAttributesResponse(
                    message, header.get().payloadOffset());
            if (!(attributes.get(0x0004) instanceof String manufacturer)
                    || !(attributes.get(0x0005) instanceof String model)) {
                return Optional.empty();
            }
            int powerSource = attributes.get(0x0007) instanceof Long power
                    ? power.intValue() : 0;
            String swBuildId = attributes.get(0x4000) instanceof String sw
                    ? sw : null;
            return Optional.of(new BasicInfo(manufacturer, model, powerSource,
                    swBuildId));
        }
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
                    // N-4 (M9.4b §6.9): scanCompleteHandler carries [channel,
                    // status] (bellows-derived) — a non-success completion was
                    // silent; the scan map may be partial. WARN, never a throw
                    // (channel selection degrades over what was measured).
                    byte[] complete = frame.parameters();
                    if (complete.length >= 2 && complete[1] != 0) {
                        log.warn("zigbee.energy_scan_incomplete: channel={} "
                                        + "status=0x{} — the scan ended non-success; "
                                        + "selection proceeds over partial energy data",
                                complete[0] & 0xFF,
                                Integer.toHexString(complete[1] & 0xFF));
                    }
                    lastActivity = clock.instant();
                    return energyByChannel;
                }
                enqueueCallbackLocked(frame); // unrelated callback: kept for ingestion
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
            // The EMBER_NETWORK_UP stackStatusHandler await is the CALLER'S step
            // (M9.4b §5.3, awaitNetworkUp()): the adapter's run() awaits it after
            // form/resume returns — not here, so the formation seam stays a pure
            // command exchange (the seam's fakes never script callbacks).
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

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZigbeeDeviceCache} tests (Doc 08 §3.14): announce/interview/frame
 * lifecycle updates, the NWK→IEEE index the ingestion resolves through, the
 * 30 s write debounce, shutdown flush, and the JSON round-trip including the
 * persisted availability sidecar (§8.1 M-1 restart-init input) and the
 * LEARN-PERSIST {@code learnedZoneTypes} top-level section (DP-LP-1: the FILE
 * carries the learns, keyed by IEEE hex, tolerated-additively — absence and
 * malformed content both degrade to unlearned, never a load failure). F-14
 * pins the write-failure backoff: a failed write arms a 60 s suppression,
 * reads stay live through it, and shutdown {@code flush()} still attempts.
 */
class ZigbeeDeviceCacheTest {

    private static final IEEEAddress SNZB = new IEEEAddress(0x00124B0012345678L);

    @TempDir
    Path tempDir;

    private TestClock clock;
    private Path file;
    private ZigbeeDeviceCache cache;

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        file = tempDir.resolve("zigbee-devices.json");
        cache = new ZigbeeDeviceCache(file, clock);
    }

    private static InterviewResult snzbInterview() {
        return new InterviewResult(SNZB, 0x6B9A,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0107,
                        List.of(0x0000, 0x0406), List.of(0x0019))),
                "eWeLink", "SNZB-03P", 3, InterviewStatus.COMPLETE);
    }

    @Test
    @DisplayName("announce creates a PENDING record and indexes the NWK address")
    void announceCreatesPendingRecord() {
        cache.recordAnnounce(SNZB, 0x6B9A);

        assertThat(cache.device(SNZB)).isPresent();
        assertThat(cache.device(SNZB).get().interviewStatus())
                .isEqualTo(InterviewStatus.PENDING);
        assertThat(cache.deviceForNetworkAddress(0x6B9A)).contains(SNZB);
    }

    @Test
    @DisplayName("a rejoin with a new NWK address re-indexes (IEEE never changes)")
    void rejoinReindexes() {
        cache.recordAnnounce(SNZB, 0x6B9A);
        cache.recordInterview(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);

        cache.recordAnnounce(SNZB, 0x1234);

        assertThat(cache.deviceForNetworkAddress(0x1234)).contains(SNZB);
        assertThat(cache.deviceForNetworkAddress(0x6B9A)).isEmpty();
        assertThat(cache.device(SNZB).get().manufacturerName())
                .as("interview metadata survives the rejoin")
                .isEqualTo("eWeLink");
    }

    @Test
    @DisplayName("interview completion fills the record")
    void interviewFillsRecord() {
        cache.recordAnnounce(SNZB, 0x6B9A);

        cache.recordInterview(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);

        ZigbeeDeviceRecord record = cache.device(SNZB).orElseThrow();
        assertThat(record.interviewStatus()).isEqualTo(InterviewStatus.COMPLETE);
        assertThat(record.modelIdentifier()).isEqualTo("SNZB-03P");
        assertThat(record.matchedProfileId())
                .isEqualTo(MeasuredCorpusValues.SNZB_PROFILE_ID);
        assertThat(record.endpoints()).hasSize(1);
    }

    @Test
    @DisplayName("writes debounce to at most once per 30 s")
    void writesDebounce() throws Exception {
        cache.recordAnnounce(SNZB, 0x6B9A);
        cache.maybeFlush();
        assertThat(Files.exists(file)).isTrue();
        byte[] first = Files.readAllBytes(file);

        clock.advance(Duration.ofSeconds(10));
        cache.recordFrame(SNZB);
        cache.maybeFlush();
        assertThat(Files.readAllBytes(file))
                .as("a change 10 s after the last write does not rewrite")
                .isEqualTo(first);

        clock.advance(Duration.ofSeconds(20));
        cache.maybeFlush();
        assertThat(Files.readAllBytes(file))
                .as("the debounce window elapsed; the dirty change flushes")
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("flush() writes immediately (the shutdown path)")
    void flushWritesImmediately() {
        cache.recordAnnounce(SNZB, 0x6B9A);

        cache.flush();

        assertThat(Files.exists(file)).isTrue();
    }

    @Test
    @DisplayName("the cache round-trips through zigbee-devices.json, availability included")
    void roundTripsThroughFile() {
        cache.recordAnnounce(SNZB, 0x6B9A);
        cache.recordInterview(snzbInterview(),
                MeasuredCorpusValues.SNZB_PROFILE_ID);
        cache.setAvailability(SNZB, true);
        cache.flush();

        ZigbeeDeviceCache reloaded = new ZigbeeDeviceCache(file, clock);

        ZigbeeDeviceRecord record = reloaded.device(SNZB).orElseThrow();
        assertThat(record.manufacturerName()).isEqualTo("eWeLink");
        assertThat(record.interviewStatus()).isEqualTo(InterviewStatus.COMPLETE);
        assertThat(record.networkAddress()).isEqualTo(0x6B9A);
        assertThat(record.endpoints()).hasSize(1);
        assertThat(record.endpoints().get(0).inputClusters())
                .containsExactly(0x0000, 0x0406);
        assertThat(reloaded.lastKnownAvailability(SNZB)).contains(true);
        assertThat(reloaded.deviceForNetworkAddress(0x6B9A)).contains(SNZB);
    }

    @Test
    @DisplayName("a missing cache file is an empty cache, not an error")
    void missingFileIsEmptyCache() {
        ZigbeeDeviceCache fresh = new ZigbeeDeviceCache(
                tempDir.resolve("absent.json"), clock);

        assertThat(fresh.all()).isEmpty();
    }

    @Test
    @DisplayName("F-6: a reindex collision invalidates the victim's address to the unknown sentinel — the index follows the new owner")
    void reindexCollision_victimInvalidated() {
        IEEEAddress newcomer = new IEEEAddress(0x00124B00AABBCCDDL);
        cache.recordAnnounce(SNZB, 0x6B9A);

        cache.recordAnnounce(newcomer, 0x6B9A);   // the coordinator reassigned 0x6B9A

        assertThat(cache.deviceForNetworkAddress(0x6B9A)).contains(newcomer);
        assertThat(cache.device(SNZB).orElseThrow().networkAddress())
                .as("the victim must never silently keep the reassigned address")
                .isEqualTo(ZigbeeDeviceCache.NETWORK_ADDRESS_UNKNOWN);
        // The victim's identity metadata survives the invalidation.
        assertThat(cache.device(SNZB).orElseThrow().ieeeAddress()).isEqualTo(SNZB);
    }

    @Test
    @DisplayName("F-6: the same device re-announcing on its own address is NOT a collision")
    void selfReannounce_noInvalidation() {
        cache.recordAnnounce(SNZB, 0x6B9A);

        cache.recordAnnounce(SNZB, 0x6B9A);

        assertThat(cache.device(SNZB).orElseThrow().networkAddress())
                .isEqualTo(0x6B9A);
        assertThat(cache.deviceForNetworkAddress(0x6B9A)).contains(SNZB);
    }

    // ── LEARN-PERSIST — the learnedZoneTypes top-level section (DP-LP-1/2) ──

    @Test
    @DisplayName("learned zone types round-trip through zigbee-devices.json — "
            + "including a device with no record node (the top-level carry)")
    void learnedZoneTypesRoundTripThroughFile() {
        IEEEAddress recordless = new IEEEAddress(0x00124B00AA0004B4L);
        cache.recordAnnounce(SNZB, 0x6B9A);
        cache.recordLearnedZoneType(SNZB, ZoneType.CONTACT.zclId());
        cache.recordLearnedZoneType(recordless, ZoneType.WATER_LEAK.zclId());
        cache.flush();

        ZigbeeDeviceCache reloaded = new ZigbeeDeviceCache(file, clock);

        assertThat(reloaded.learnedZoneTypeIds())
                .as("both learns persist — the recordless device's learn has "
                        + "no devices[] node to ride (DP-LP-1's top-level "
                        + "rationale)")
                .containsOnly(
                        Map.entry(SNZB.value(), (long) ZoneType.CONTACT.zclId()),
                        Map.entry(recordless.value(),
                                (long) ZoneType.WATER_LEAK.zclId()));
        assertThat(reloaded.device(recordless))
                .as("no device record was invented for the recordless learn")
                .isEmpty();
    }

    @Test
    @DisplayName("an existing-format file WITHOUT a learnedZoneTypes section "
            + "loads clean — empty ids, devices intact (the upgrade path)")
    void learnedZoneTypesAbsentSectionLoadsClean() throws IOException {
        Files.writeString(file, """
                {
                  "version" : 1,
                  "devices" : [ {
                    "ieee" : "0x00124B0012345678",
                    "networkAddress" : 27546,
                    "powerSource" : 3,
                    "lastSeen" : "2026-01-01T00:00:00Z",
                    "interviewStatus" : "COMPLETE"
                  } ]
                }
                """, StandardCharsets.UTF_8);

        ZigbeeDeviceCache reloaded = new ZigbeeDeviceCache(file, clock);

        assertThat(reloaded.device(SNZB)).isPresent();
        assertThat(reloaded.learnedZoneTypeIds()).isEmpty();
    }

    @Test
    @DisplayName("a non-object learnedZoneTypes section is skipped with ONE "
            + "WARN — devices load, ids empty, no throw (fail-safe = unlearned)")
    void learnedZoneTypesNonObjectSectionWarnsAndLoadsEmpty() throws IOException {
        Files.writeString(file, """
                {
                  "version" : 1,
                  "devices" : [ {
                    "ieee" : "0x00124B0012345678",
                    "networkAddress" : 27546,
                    "powerSource" : 3,
                    "lastSeen" : "2026-01-01T00:00:00Z",
                    "interviewStatus" : "COMPLETE"
                  } ],
                  "learnedZoneTypes" : [ 21, 42 ]
                }
                """, StandardCharsets.UTF_8);
        ListAppender<ILoggingEvent> capture = attachCacheCapture();
        ZigbeeDeviceCache reloaded;
        try {
            reloaded = new ZigbeeDeviceCache(file, clock);
        } finally {
            cacheLogger().detachAppender(capture);
        }

        assertThat(reloaded.device(SNZB))
                .as("the devices load — a malformed sidecar section never "
                        + "discards the cache")
                .isPresent();
        assertThat(reloaded.learnedZoneTypeIds()).isEmpty();
        assertThat(malformedWarnings(capture)).hasSize(1);
    }

    @Test
    @DisplayName("malformed learnedZoneTypes entries (bad key, non-numeric "
            + "value) are skipped with ONE WARN — the parseable entry applies")
    void learnedZoneTypesMalformedEntriesSkippedWithOneWarn() throws IOException {
        Files.writeString(file, """
                {
                  "version" : 1,
                  "devices" : [ ],
                  "learnedZoneTypes" : {
                    "not-a-hex-key" : 21,
                    "0x00124B0012345678" : "twenty-one",
                    "0x00124B00AA0004B4" : 21
                  }
                }
                """, StandardCharsets.UTF_8);
        ListAppender<ILoggingEvent> capture = attachCacheCapture();
        ZigbeeDeviceCache reloaded;
        try {
            reloaded = new ZigbeeDeviceCache(file, clock);
        } finally {
            cacheLogger().detachAppender(capture);
        }

        assertThat(reloaded.learnedZoneTypeIds())
                .as("what parses, applies — fail-safe never discards the "
                        + "healthy siblings")
                .containsOnly(Map.entry(0x00124B00AA0004B4L, 21L));
        assertThat(malformedWarnings(capture)).hasSize(1);
    }

    private static Logger cacheLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeDeviceCache.class);
    }

    private static ListAppender<ILoggingEvent> attachCacheCapture() {
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        cacheLogger().addAppender(capture);
        return capture;
    }

    private static List<String> malformedWarnings(
            ListAppender<ILoggingEvent> capture) {
        return capture.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message
                        .startsWith("zigbee.learned_zonetypes_malformed"))
                .toList();
    }

    private Path blockedTarget() {
        return tempDir.resolve("blocked").resolve("zigbee-devices.json");
    }

    /**
     * Builds a cache whose write target is unwritable: the target's parent
     * path exists as a regular FILE, so the write path's
     * {@code createDirectories} fails with a real {@code IOException} on
     * every platform (no I/O seam exists — F-14 exercises the production
     * write path directly).
     */
    private ZigbeeDeviceCache blockedCache() throws IOException {
        Files.createFile(tempDir.resolve("blocked"));
        return new ZigbeeDeviceCache(blockedTarget(), clock);
    }

    /** Removes the blocking file so the target directory becomes creatable. */
    private void unblockTarget() throws IOException {
        Files.delete(tempDir.resolve("blocked"));
    }

    @Test
    @DisplayName("F-14: a failed write arms the backoff and recovers after it expires")
    void failedWriteRecoversAfterBackoff() throws IOException {
        ZigbeeDeviceCache blocked = blockedCache();
        blocked.recordAnnounce(SNZB, 0x6B9A);

        blocked.maybeFlush();
        assertThat(Files.exists(blockedTarget()))
                .as("the write against the blocked target fails")
                .isFalse();

        unblockTarget();
        clock.advance(Duration.ofMillis(
                ZigbeeDeviceCache.WRITE_FAILURE_BACKOFF_MILLIS));
        blocked.maybeFlush();

        assertThat(Files.exists(blockedTarget())).isTrue();
        ZigbeeDeviceCache reloaded = new ZigbeeDeviceCache(blockedTarget(), clock);
        assertThat(reloaded.device(SNZB))
                .as("the failed snapshot was re-dirtied, never lost")
                .isPresent();
    }

    @Test
    @DisplayName("F-14: the backoff suppresses the next write until the full 60 s pass")
    void backoffSuppressesUntilExpiry() throws IOException {
        ZigbeeDeviceCache blocked = blockedCache();
        blocked.recordAnnounce(SNZB, 0x6B9A);
        blocked.maybeFlush();
        unblockTarget();

        clock.advance(Duration.ofMillis(
                ZigbeeDeviceCache.WRITE_FAILURE_BACKOFF_MILLIS - 1));
        blocked.maybeFlush();
        assertThat(Files.exists(blockedTarget()))
                .as("one millisecond before expiry the write stays suppressed")
                .isFalse();

        clock.advance(Duration.ofMillis(1));
        blocked.maybeFlush();
        assertThat(Files.exists(blockedTarget())).isTrue();
    }

    @Test
    @DisplayName("F-14: reads succeed between a failed write and recovery")
    void readsSucceedDuringBackoff() throws IOException {
        ZigbeeDeviceCache blocked = blockedCache();
        blocked.recordAnnounce(SNZB, 0x6B9A);
        blocked.setAvailability(SNZB, true);
        blocked.maybeFlush();

        assertThat(blocked.device(SNZB)).isPresent();
        assertThat(blocked.all()).hasSize(1);
        assertThat(blocked.deviceForNetworkAddress(0x6B9A)).contains(SNZB);
        assertThat(blocked.lastKnownAvailability(SNZB)).contains(true);
    }

    @Test
    @DisplayName("F-14: shutdown flush() attempts the write inside the backoff window")
    void flushBypassesBackoff() throws IOException {
        ZigbeeDeviceCache blocked = blockedCache();
        blocked.recordAnnounce(SNZB, 0x6B9A);
        blocked.maybeFlush();
        unblockTarget();

        blocked.flush();

        assertThat(Files.exists(blockedTarget()))
                .as("shutdown is the last persistence chance; the backoff "
                        + "guards the cycle path, never the shutdown flush")
                .isTrue();
    }
}

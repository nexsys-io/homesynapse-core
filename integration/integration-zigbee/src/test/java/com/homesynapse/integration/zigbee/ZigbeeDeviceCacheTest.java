/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ZigbeeDeviceCache} tests (Doc 08 §3.14): announce/interview/frame
 * lifecycle updates, the NWK→IEEE index the ingestion resolves through, the
 * 30 s write debounce, shutdown flush, and the JSON round-trip including the
 * persisted availability sidecar (§8.1 M-1 restart-init input).
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
}

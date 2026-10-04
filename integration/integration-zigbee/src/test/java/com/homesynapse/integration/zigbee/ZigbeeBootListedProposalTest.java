/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.homesynapse.config.ConfigurationAccess;
import com.homesynapse.device.InMemoryDeviceRegistry;
import com.homesynapse.device.InMemoryEntityRegistry;
import com.homesynapse.device.RegistryProjection;
import com.homesynapse.integration.HealthReporter;
import com.homesynapse.integration.HealthState;
import com.homesynapse.integration.IntegrationContext;
import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.IntegrationId;
import com.homesynapse.platform.identity.UlidFactory;
import com.homesynapse.state.EntityState;
import com.homesynapse.state.StateQueryService;
import com.homesynapse.state.StateSnapshot;
import com.homesynapse.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * J2a (IR-114) — the boot re-proposal: right after {@code rehydrateAdoptionMaps()}
 * and the interview queue's construction, every IEEE on the adopt-accept list that
 * the announce cache knows and the adoption maps do not is scheduled on the
 * interview queue with {@link PendingInterviewQueue.Source#BOOT_LISTED}; the
 * interview then proposes (the slice's one proposal site). Exercised at the
 * {@code initialize()} grain — the {@link ZigbeeAvailabilityWiringTest} boot form
 * without the transport ladder, because the boot proposal runs before any port is
 * opened — with the cache file pre-seeded the way a prior process left it.
 *
 * <p>The miss this closes (IR-114): before J2a a listed, cached, unadopted device
 * got NOTHING at boot — the boot read the list once and rehydrated the REGISTRY
 * only; a sleepy sensor that never re-announced stayed un-proposed for days.
 */
@DisplayName("ZigbeeIntegrationAdapter — the boot re-proposal of a listed, cached "
        + "device (J2a / IR-114)")
class ZigbeeBootListedProposalTest {

    private static final long SNZB_IEEE = 0x00124B0012345678L;
    private static final int SNZB_NWK = 0x6B9A;
    /** The list entry as an operator writes it (the loader canonicalizes case). */
    private static final String SNZB_LISTED = "0x00124b0012345678";
    private static final String SNZB_HEX = "0x00124B0012345678";

    @TempDir
    Path tempDir;

    private TestClock clock;
    private RecordingEventPublisher publisher;
    private IntegrationId integrationId;
    private InMemoryDeviceRegistry deviceRegistry;
    private InMemoryEntityRegistry entityRegistry;
    private ListAppender<ILoggingEvent> adapterLogCapture;
    private Level priorAdapterLevel;

    /** Default constructor required by JUnit 5 under {@code -Xlint:all -Werror}. */
    ZigbeeBootListedProposalTest() {
        // Defaults are sufficient.
    }

    @BeforeEach
    void setUp() {
        clock = TestClock.createDefault();
        publisher = new RecordingEventPublisher(clock);
        integrationId = new IntegrationId(UlidFactory.generate(clock));
        deviceRegistry = new InMemoryDeviceRegistry();
        entityRegistry = new InMemoryEntityRegistry();
        adapterLogCapture = new ListAppender<>();
        adapterLogCapture.start();
        priorAdapterLevel = adapterLogger().getLevel();
        adapterLogger().setLevel(Level.DEBUG);   // the skip reasons are DEBUG lines
        adapterLogger().addAppender(adapterLogCapture);
    }

    @AfterEach
    void tearDown() {
        adapterLogger().detachAppender(adapterLogCapture);
        adapterLogger().setLevel(priorAdapterLevel);
    }

    @Test
    @DisplayName("T10: a LISTED, CACHED, UNADOPTED device is scheduled ONCE at initialize() "
            + "with source BOOT_LISTED and ONE zigbee.boot_listed_candidate INFO")
    void listedCachedUnadopted_scheduledOnce() throws Exception {
        seedCache(SNZB_NWK);

        ZigbeeIntegrationAdapter adapter = initialized(List.<Object>of(SNZB_LISTED));

        assertThat(adapter.interviewQueue().due())
                .as("exactly one pending interview, the listed device, boot provenance")
                .extracting(PendingInterviewQueue.Pending::ieeeAddress,
                        PendingInterviewQueue.Pending::networkAddress,
                        PendingInterviewQueue.Pending::source)
                .containsExactly(tuple(new IEEEAddress(SNZB_IEEE), SNZB_NWK,
                        PendingInterviewQueue.Source.BOOT_LISTED));
        assertThat(adapterMessages(Level.INFO, "zigbee.boot_listed_candidate"))
                .containsExactly("zigbee.boot_listed_candidate: device=" + SNZB_HEX
                        + " nwk=0x6b9a");
        assertThat(adapterMessages(Level.DEBUG, "zigbee.boot_listed_skipped")).isEmpty();
        assertThat(publisher.published())
                .as("a schedule is in-memory: no registry write, no event").isEmpty();
    }

    @Test
    @DisplayName("T10b: a LISTED, CACHED device the registries already carry is NOT "
            + "re-proposed — boot_listed_skipped reason=already_adopted; the queue stays empty")
    void listedCachedAdopted_notScheduled() throws Exception {
        // The prior process: interview + adopt (the AWT adoptDirect idiom), the cache
        // flushed as the shutdown would; the registries carry the device.
        ZigbeeIntegrationAdapter first = initialized(null);
        first.deviceCache().recordInterview(snzbInterview(), null);
        first.adoptionSlice().onDeviceDiscovered(snzbInterview(), null);
        first.adoptionSlice().adopt(new IEEEAddress(SNZB_IEEE));
        first.deviceCache().flush();
        adapterLogCapture.list.clear();

        ZigbeeIntegrationAdapter restarted = initialized(List.<Object>of(SNZB_LISTED));

        assertThat(restarted.interviewQueue().due()).isEmpty();
        assertThat(adapterMessages(Level.INFO, "zigbee.boot_listed_candidate")).isEmpty();
        assertThat(adapterMessages(Level.DEBUG, "zigbee.boot_listed_skipped"))
                .containsExactly("zigbee.boot_listed_skipped: device=" + SNZB_HEX
                        + " reason=already_adopted");
    }

    @Test
    @DisplayName("T10c: a CACHED, UNADOPTED device that is NOT listed is left alone — "
            + "no schedule, no line (the list is the operator's consent)")
    void cachedUnlisted_nothing() throws Exception {
        seedCache(SNZB_NWK);

        ZigbeeIntegrationAdapter adapter = initialized(null);

        assertThat(adapter.interviewQueue().due()).isEmpty();
        assertThat(adapterMessages(Level.INFO, "zigbee.boot_listed_candidate")).isEmpty();
        assertThat(adapterMessages(Level.DEBUG, "zigbee.boot_listed_skipped")).isEmpty();
    }

    @Test
    @DisplayName("T10d: a LISTED device the cache has never seen is skipped with "
            + "reason=not_cached — nothing to interview until it announces")
    void listedNotCached_skipped() throws Exception {
        ZigbeeIntegrationAdapter adapter = initialized(List.<Object>of(SNZB_LISTED));

        assertThat(adapter.interviewQueue().due()).isEmpty();
        assertThat(adapterMessages(Level.INFO, "zigbee.boot_listed_candidate")).isEmpty();
        assertThat(adapterMessages(Level.DEBUG, "zigbee.boot_listed_skipped"))
                .containsExactly("zigbee.boot_listed_skipped: device=" + SNZB_HEX
                        + " reason=not_cached");
    }

    @Test
    @DisplayName("T10e: a LISTED, CACHED device whose record carries the unknown-address "
            + "sentinel (0xFFFF) is skipped with reason=address_unknown")
    void listedCachedAddressUnknown_skipped() throws Exception {
        seedCache(ZigbeeDeviceCache.NETWORK_ADDRESS_UNKNOWN);

        ZigbeeIntegrationAdapter adapter = initialized(List.<Object>of(SNZB_LISTED));

        assertThat(adapter.interviewQueue().due()).isEmpty();
        assertThat(adapterMessages(Level.INFO, "zigbee.boot_listed_candidate")).isEmpty();
        assertThat(adapterMessages(Level.DEBUG, "zigbee.boot_listed_skipped"))
                .containsExactly("zigbee.boot_listed_skipped: device=" + SNZB_HEX
                        + " reason=address_unknown");
    }

    // ── the prior process's cache + the initialize()-grain boot ─────────────

    /** What a prior process left on disk: the device announced once, never interviewed. */
    private void seedCache(int networkAddress) {
        ZigbeeDeviceCache priorProcess = new ZigbeeDeviceCache(
                tempDir.resolve("zigbee-devices.json"), clock);
        priorProcess.recordAnnounce(new IEEEAddress(SNZB_IEEE), networkAddress);
        priorProcess.flush();
    }

    /**
     * The boot through {@code initialize()} only — the boot proposal runs there,
     * before any port is resolved or opened (the enumerator and opener are never
     * reached at this grain).
     */
    private ZigbeeIntegrationAdapter initialized(List<Object> adoptDevices)
            throws Exception {
        ZigbeeIntegrationAdapter adapter = new ZigbeeIntegrationAdapter(
                context(configAccess(adoptDevices)),
                deviceRegistry,
                new RegistryProjection(deviceRegistry, entityRegistry),
                tempDir, clock, null,
                () -> List.of(),
                candidate -> {
                    throw new IllegalStateException("no port is opened at this grain");
                });
        adapter.initialize();
        return adapter;
    }

    private static InterviewResult snzbInterview() {
        return new InterviewResult(new IEEEAddress(SNZB_IEEE), SNZB_NWK,
                new NodeDescriptor(2, 0x1286, 82, 128),
                List.of(new EndpointDescriptor(1, 0x0104, 0x0107,
                        List.of(0x0000, 0x0001, 0x0003, 0x0020, 0x0406, 0x0500),
                        List.of(0x0003, 0x0019))),
                "eWeLink", "SNZB-03P", 3, InterviewStatus.COMPLETE);
    }

    private IntegrationContext context(ConfigurationAccess configAccess) {
        return new IntegrationContext(
                integrationId, "zigbee", publisher,
                entityRegistry, unusedQueryService(),
                unusedHealthReporter(), configAccess,
                null, null, null, null, null);
    }

    // ── log capture ──────────────────────────────────────────────────────────

    private static Logger adapterLogger() {
        return (Logger) LoggerFactory.getLogger(ZigbeeIntegrationAdapter.class);
    }

    /** The captured adapter lines at {@code level} starting with {@code token}, in order. */
    private List<String> adapterMessages(Level level, String token) {
        return adapterLogCapture.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(token))
                .toList();
    }

    // ── inert context stubs (the ZigbeeAvailabilityWiringTest idiom) ────────

    private static ConfigurationAccess configAccess(List<Object> adoptDevices) {
        return new ConfigurationAccess() {
            @Override
            public Map<String, Object> getConfig() {
                return adoptDevices == null ? Map.of()
                        : Map.of(ZigbeeIntegrationAdapter.ADOPT_DEVICES_KEY,
                                adoptDevices);
            }

            @Override
            public Optional<String> getString(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Integer> getInt(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> getBoolean(String key) {
                return Optional.empty();
            }
        };
    }

    private static StateQueryService unusedQueryService() {
        return new StateQueryService() {
            @Override
            public Optional<EntityState> getState(EntityId entityId) {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public Map<EntityId, EntityState> getStates(Set<EntityId> entityIds) {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public StateSnapshot getSnapshot() {
                throw new UnsupportedOperationException("not used by the adapter");
            }

            @Override
            public long getViewPosition() {
                return 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }
        };
    }

    private static HealthReporter unusedHealthReporter() {
        return new HealthReporter() {
            @Override
            public void reportHeartbeat() {
            }

            @Override
            public void reportKeepalive(Instant lastSuccess) {
            }

            @Override
            public void reportError(Throwable error) {
            }

            @Override
            public void reportHealthTransition(HealthState state, String reason) {
            }
        };
    }
}

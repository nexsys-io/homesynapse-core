/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static com.homesynapse.automation.AutomationTestSupport.FIXED_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.homesynapse.automation.AutomationTestSupport.MutableClock;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AUTO-ID-1 — {@link CompanionAutomationIdentityStore}: the identity companion's POLICY
 * (Doc 07 §4.1; AMD-93 §2.3) against an in-memory {@link AutomationIdentityCompanion} —
 * no filesystem, no YAML (the bytes are lifecycle's
 * {@code FileAutomationIdentityCompanionTest}).
 *
 * <blockquote><strong>Tests must inject {@code Clock}.</strong> Every instant here is a
 * {@link MutableClock}'s; the retention tests START 31 days before
 * {@link AutomationTestSupport#FIXED_INSTANT} and advance TO it, so no mint ever pushes
 * {@code UlidFactory}'s JVM-wide monotonic guard past the instant every other class in
 * this module mints at.</blockquote>
 */
@DisplayName("CompanionAutomationIdentityStore — the identity companion's policy (AUTO-ID-1)")
class CompanionAutomationIdentityStoreTest {

    private static final String LOCATION = "memory:automations.ids.yaml";
    private static final String ULID_A = "01M1PRQN03X8H4MNEZQ62F76F1";
    private static final String ULID_B = "01M1PRQN03X8H4MNEZQ62F76F2";

    private MapCompanion companion;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        companion = new MapCompanion();
        clock = AutomationTestSupport.mutableClock(
                FIXED_INSTANT.minus(Duration.ofDays(31)), ZoneOffset.UTC);
    }

    // ── T2 — durable across instances ───────────────────────────────────────

    @Test
    @DisplayName("T2: a second store over the same companion returns the same automation ids")
    void secondStoreOverTheSameCompanion_returnsTheSameIds() {
        CompanionAutomationIdentityStore first = newStore();
        first.beginLoad();
        AutomationId hero = first.automationIdFor("hero-light");
        AutomationId porch = first.automationIdFor("porch");
        first.endLoad();

        CompanionAutomationIdentityStore second = newStore();
        second.beginLoad();
        assertThat(second.automationIdFor("hero-light")).isEqualTo(hero);
        assertThat(second.automationIdFor("porch")).isEqualTo(porch);
        second.endLoad();
        assertThat(hero).isNotEqualTo(porch);
    }

    @Test
    @DisplayName("T2b: through the real loader — a definition's automationId and its generated "
            + "trigger id are the same on a second store over the same companion")
    void loaderOverTheCompanionStore_keepsDefinitionIdentityAcrossInstances() {
        AutomationDefinition first = loadScene(newStore());
        AutomationDefinition second = loadScene(newStore());

        assertThat(second.automationId()).isEqualTo(first.automationId());
        assertThat(((ManualTrigger) second.triggers().get(0)).triggerId())
                .isEqualTo(((ManualTrigger) first.triggers().get(0)).triggerId());
    }

    @Test
    @DisplayName("T2c: whatever slug the loader hands over round-trips — a document this store "
            + "wrote is never one it refuses to read (a blank slug included)")
    void anySlugTheStoreAccepted_isOneItReadsBack() {
        List<String> slugs = List.of("", " ", "123", "true", "a: b", "hero-light");
        CompanionAutomationIdentityStore first = newStore();
        first.beginLoad();
        List<AutomationId> minted = slugs.stream().map(first::automationIdFor).toList();
        first.endLoad();

        CompanionAutomationIdentityStore second = newStore();
        assertThatCode(second::beginLoad).doesNotThrowAnyException();
        assertThat(slugs.stream().map(second::automationIdFor).toList()).isEqualTo(minted);
        second.endLoad();
    }

    // ── T3 — mint, keep, the document ───────────────────────────────────────

    @Test
    @DisplayName("T3: an unknown slug mints, a known slug keeps, and endLoad writes the R2 "
            + "document — schema_version 1, slugs sorted, id / last_seen / triggers per entry")
    void unknownSlugMints_knownSlugKeeps_andTheDocumentHasTheR2Shape() {
        CompanionAutomationIdentityStore store = newStore();
        store.beginLoad();
        AutomationId porch = store.automationIdFor("porch");
        AutomationId hero = store.automationIdFor("hero-light");
        assertThat(store.automationIdFor("porch")).isEqualTo(porch);
        store.endLoad();

        Map<String, Object> document = companion.document();
        assertThat(document.keySet()).containsExactly("schema_version", "automations");
        assertThat(document.get("schema_version")).isEqualTo(1);
        Map<String, Object> automations = map(document.get("automations"));
        assertThat(automations.keySet()).containsExactly("hero-light", "porch");
        Map<String, Object> heroEntry = map(automations.get("hero-light"));
        assertThat(heroEntry.keySet()).containsExactly("id", "last_seen", "triggers");
        assertThat(heroEntry.get("id")).isEqualTo(hero.toString());
        assertThat(heroEntry.get("last_seen")).isEqualTo("2025-12-01T00:00:00Z");
        assertThat(map(heroEntry.get("triggers"))).isEmpty();
        assertThat(map(automations.get("porch")).get("id")).isEqualTo(porch.toString());
    }

    @Test
    @DisplayName("T3b: a later load that adds a slug keeps every earlier id and writes once more")
    void laterLoadThatAddsASlug_keepsEarlierIds() {
        CompanionAutomationIdentityStore first = newStore();
        first.beginLoad();
        AutomationId hero = first.automationIdFor("hero-light");
        first.endLoad();

        CompanionAutomationIdentityStore second = newStore();
        second.beginLoad();
        assertThat(second.automationIdFor("hero-light")).isEqualTo(hero);
        AutomationId added = second.automationIdFor("added");
        second.endLoad();

        assertThat(added).isNotEqualTo(hero);
        assertThat(companion.replaces()).isEqualTo(2);
        assertThat(map(companion.document().get("automations")).keySet())
                .containsExactly("added", "hero-light");
    }

    // ── P3 / P6 — write-once-if-changed ─────────────────────────────────────

    @Test
    @DisplayName("P3: a load that minted writes ONCE at endLoad; a load that changes nothing "
            + "writes nothing and leaves the document equal (P6)")
    void writeOnce_perLoadThatChangedAnything() {
        CompanionAutomationIdentityStore first = newStore();
        first.beginLoad();
        first.automationIdFor("hero-light");
        first.automationIdFor("porch");
        first.generatedTriggerIdFor("porch", 0);
        assertThat(companion.replaceAttempts()).as("no write before endLoad").isZero();
        first.endLoad();
        assertThat(companion.replaces()).isEqualTo(1);
        Map<String, Object> written = companion.document();

        // The same store, a second bracket that touches the same slugs at the same instant.
        first.beginLoad();
        first.automationIdFor("hero-light");
        first.automationIdFor("porch");
        first.generatedTriggerIdFor("porch", 0);
        first.endLoad();
        // A second store (the next boot) that does the same.
        CompanionAutomationIdentityStore second = newStore();
        second.beginLoad();
        second.automationIdFor("hero-light");
        second.automationIdFor("porch");
        second.generatedTriggerIdFor("porch", 0);
        second.endLoad();

        assertThat(companion.replaceAttempts()).isEqualTo(1);
        assertThat(companion.document()).isEqualTo(written);
    }

    @Test
    @DisplayName("P3b: a load at a LATER instant re-stamps last_seen on the slugs it touched — "
            + "one write — so retention counts from the last sighting (Doc 07 §4.1), never "
            + "from the mint")
    void laterLoad_restampsLastSeen_soRetentionCountsFromTheLastSighting() {
        CompanionAutomationIdentityStore first = newStore();
        first.beginLoad();
        AutomationId removedLater = first.automationIdFor("removed-later");
        first.automationIdFor("stays");
        first.endLoad();

        clock.advance(Duration.ofDays(15));                 // day 15: both still defined
        CompanionAutomationIdentityStore second = newStore();
        second.beginLoad();
        second.automationIdFor("removed-later");
        second.automationIdFor("stays");
        second.endLoad();
        assertThat(companion.replaces()).isEqualTo(2);
        assertThat(map(map(companion.document().get("automations")).get("removed-later"))
                .get("last_seen")).isEqualTo("2025-12-16T00:00:00Z");

        clock.advance(Duration.ofDays(16));                 // day 31: 'removed-later' is gone
        CompanionAutomationIdentityStore third = newStore();
        third.beginLoad();
        third.automationIdFor("stays");
        third.endLoad();

        // 31 days since the mint, 16 since the last sighting: kept.
        CompanionAutomationIdentityStore fourth = newStore();
        fourth.beginLoad();
        assertThat(fourth.automationIdFor("removed-later")).isEqualTo(removedLater);
        fourth.endLoad();
    }

    // ── T4 — retention ──────────────────────────────────────────────────────

    @Test
    @DisplayName("T4: an entry unseen for 29 days is KEPT — removed and re-added inside the "
            + "window, the slug keeps its id")
    void entryUnseenFor29Days_isKept() {
        AutomationId kept = mintKeptAndOther();

        clock.advance(Duration.ofDays(29));
        touchOnlyOther();

        assertThat(map(companion.document().get("automations")).keySet())
                .containsExactly("kept", "other");
        CompanionAutomationIdentityStore readded = newStore();
        readded.beginLoad();
        assertThat(readded.automationIdFor("kept")).isEqualTo(kept);
        readded.endLoad();
    }

    @Test
    @DisplayName("T4b: an entry unseen for exactly 30 days is KEPT — retention drops only what "
            + "is OLDER than the window")
    void entryUnseenForExactly30Days_isKept() {
        mintKeptAndOther();

        clock.advance(CompanionAutomationIdentityStore.RETENTION);
        touchOnlyOther();

        assertThat(map(companion.document().get("automations")).keySet())
                .containsExactly("kept", "other");
    }

    @Test
    @DisplayName("T4c: an entry unseen for 31 days is DROPPED at load, and the slug's next mint "
            + "is a NEW id — a retired id is never handed out again")
    void entryUnseenFor31Days_isDropped_andNeverReminted() {
        AutomationId retired = mintKeptAndOther();

        clock.advance(Duration.ofDays(31));
        touchOnlyOther();

        assertThat(map(companion.document().get("automations")).keySet())
                .containsExactly("other");
        CompanionAutomationIdentityStore readded = newStore();
        readded.beginLoad();
        assertThat(readded.automationIdFor("kept")).isNotEqualTo(retired);
        readded.endLoad();
    }

    // ── T5 — fail closed; the first boot ────────────────────────────────────

    @Test
    @DisplayName("T5: a malformed companion fails CLOSED at beginLoad with the location in the "
            + "message — nothing is minted over it, nothing is written")
    void malformedCompanion_failsClosed_withTheLocationInTheMessage() {
        SoftAssertions softly = new SoftAssertions();
        for (Map.Entry<String, Map<String, Object>> malformed : malformedDocuments().entrySet()) {
            MapCompanion holder = new MapCompanion();
            holder.preset(malformed.getValue());
            CompanionAutomationIdentityStore store =
                    new CompanionAutomationIdentityStore(holder, clock);

            softly.assertThatThrownBy(store::beginLoad)
                    .as("beginLoad over a companion with %s", malformed.getKey())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(LOCATION);
            softly.assertThatThrownBy(() -> store.automationIdFor("hero-light"))
                    .as("no identity is minted after a fail-closed load (%s)", malformed.getKey())
                    .isInstanceOf(IllegalStateException.class);
            softly.assertThat(holder.replaceAttempts())
                    .as("nothing is written over a companion with %s", malformed.getKey())
                    .isZero();
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("T5b: a companion that cannot be READ fails closed — the seam's exception "
            + "propagates unchanged")
    void unreadableCompanion_propagatesTheSeamsFailure() {
        companion.failReads(true);
        CompanionAutomationIdentityStore store = newStore();

        assertThatThrownBy(store::beginLoad)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LOCATION);
        assertThat(companion.replaceAttempts()).isZero();
    }

    @Test
    @DisplayName("T5c: a MISSING companion is the first boot — beginLoad succeeds, the load "
            + "mints, endLoad writes the first document")
    void missingCompanion_isTheFirstBoot() {
        CompanionAutomationIdentityStore store = newStore();

        assertThatCode(store::beginLoad).doesNotThrowAnyException();
        AutomationId minted = store.automationIdFor("hero-light");
        store.endLoad();

        assertThat(companion.replaces()).isEqualTo(1);
        assertThat(map(map(companion.document().get("automations")).get("hero-light")).get("id"))
                .isEqualTo(minted.toString());
    }

    // ── T6 — generated trigger ids ──────────────────────────────────────────

    @Test
    @DisplayName("T6: generated trigger ids are durable across instances, keyed by (slug, index), "
            + "written under the automation's entry in NUMERIC index order")
    void generatedTriggerIds_durableAcrossInstances() {
        CompanionAutomationIdentityStore first = newStore();
        first.beginLoad();
        List<String> minted = new ArrayList<>();
        for (int index = 0; index <= 10; index++) {
            minted.add(first.generatedTriggerIdFor("scene", index));
        }
        AutomationId scene = first.automationIdFor("scene");
        first.endLoad();

        assertThat(minted).doesNotHaveDuplicates().allMatch(Ulid::isValid);
        Map<String, Object> entry = map(map(companion.document().get("automations")).get("scene"));
        assertThat(entry.get("id")).isEqualTo(scene.toString());
        assertThat(map(entry.get("triggers")).keySet())
                .containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10");

        CompanionAutomationIdentityStore second = newStore();
        second.beginLoad();
        for (int index = 0; index <= 10; index++) {
            assertThat(second.generatedTriggerIdFor("scene", index)).isEqualTo(minted.get(index));
        }
        assertThat(second.automationIdFor("scene")).isEqualTo(scene);
        second.endLoad();
    }

    @Test
    @DisplayName("T6b: a negative trigger index is rejected — the interface's contract")
    void negativeTriggerIndex_isRejected() {
        CompanionAutomationIdentityStore store = newStore();
        store.beginLoad();

        assertThatThrownBy(() -> store.generatedTriggerIdFor("scene", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── R3 — a write failure never bricks the load ──────────────────────────

    @Test
    @DisplayName("R3: a write failure does not throw — the load's ids stay valid for this "
            + "process, and the NEXT endLoad retries the write")
    void writeFailure_doesNotThrow_idsStayValid_andTheNextLoadRetries() {
        companion.failWrites(true);
        CompanionAutomationIdentityStore store = newStore();
        store.beginLoad();
        AutomationId hero = store.automationIdFor("hero-light");

        assertThatCode(store::endLoad).doesNotThrowAnyException();
        assertThat(companion.replaceAttempts()).isEqualTo(1);
        assertThat(companion.replaces()).isZero();

        companion.failWrites(false);
        store.beginLoad();
        assertThat(store.automationIdFor("hero-light")).isEqualTo(hero);
        store.endLoad();
        assertThat(companion.replaceAttempts()).isEqualTo(2);
        assertThat(companion.replaces()).isEqualTo(1);
        assertThat(map(map(companion.document().get("automations")).get("hero-light")).get("id"))
                .isEqualTo(hero.toString());
    }

    // ── the bracket ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the bracket is the contract: an identity asked for outside beginLoad/endLoad "
            + "throws — a load without the bracket fails loudly instead of persisting nothing")
    void identityOutsideTheBracket_throws() {
        CompanionAutomationIdentityStore store = newStore();

        assertThatThrownBy(() -> store.automationIdFor("hero-light"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("beginLoad");
        assertThatThrownBy(() -> store.generatedTriggerIdFor("hero-light", 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("beginLoad");
        assertThatThrownBy(store::endLoad)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("beginLoad");
        assertThat(companion.replaceAttempts()).isZero();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private CompanionAutomationIdentityStore newStore() {
        return new CompanionAutomationIdentityStore(companion, clock);
    }

    /** Mints {@code kept} and {@code other} in one bracket; returns {@code kept}'s id. */
    private AutomationId mintKeptAndOther() {
        CompanionAutomationIdentityStore store = newStore();
        store.beginLoad();
        AutomationId kept = store.automationIdFor("kept");
        store.automationIdFor("other");
        store.endLoad();
        return kept;
    }

    /** A new store (the next boot) whose load names {@code other} alone. */
    private void touchOnlyOther() {
        CompanionAutomationIdentityStore store = newStore();
        store.beginLoad();
        store.automationIdFor("other");
        store.endLoad();
    }

    private AutomationDefinition loadScene(CompanionAutomationIdentityStore store) {
        AutomationDefinitionLoader loader = new AutomationDefinitionLoader(store,
                new AutomationTestSupport.StubEntityRegistry(List.of()),
                new AutomationTestSupport.StubAreaRegistry(List.of()));
        store.beginLoad();
        LoadResult result = loader.load(Map.of("automations", List.of(Map.of(
                "name", "scene",
                "triggers", List.of(Map.of("type", "manual")),
                "actions", List.of(Map.of("type", "emit_event",
                        "event_type", "custom.notify"))))));
        store.endLoad();
        assertThat(result.failures()).isEmpty();
        return result.loaded().get(0);
    }

    private static Map<String, Map<String, Object>> malformedDocuments() {
        Map<String, Map<String, Object>> cases = new LinkedHashMap<>();
        cases.put("schema_version 2", document(2, Map.of()));
        cases.put("no schema_version", Map.of("automations", Map.of()));
        cases.put("a non-integer schema_version", documentOf("schema_version", "1",
                "automations", Map.of()));
        cases.put("no automations", Map.of("schema_version", 1));
        cases.put("an unknown top-level key", documentOf("schema_version", 1,
                "automations", Map.of(), "note", "hand edit"));
        cases.put("automations that is not a map", documentOf("schema_version", 1,
                "automations", List.of()));
        cases.put("an entry that is not a map", document(1, Map.of("hero-light", ULID_A)));
        cases.put("an entry with an unknown key", document(1, Map.of("hero-light",
                Map.of("id", ULID_A, "last_seen", "2025-12-01T00:00:00Z", "name", "x"))));
        cases.put("an entry with no id", document(1, Map.of("hero-light",
                Map.of("last_seen", "2025-12-01T00:00:00Z"))));
        cases.put("an id that is not a ULID", document(1, Map.of("hero-light",
                Map.of("id", "not-a-ulid", "last_seen", "2025-12-01T00:00:00Z"))));
        cases.put("an entry with no last_seen", document(1, Map.of("hero-light",
                Map.of("id", ULID_A))));
        cases.put("a last_seen that is not an instant", document(1, Map.of("hero-light",
                Map.of("id", ULID_A, "last_seen", "yesterday"))));
        cases.put("triggers that is not a map", document(1, Map.of("hero-light",
                Map.of("id", ULID_A, "last_seen", "2025-12-01T00:00:00Z",
                        "triggers", List.of()))));
        cases.put("a trigger key that is not an index", document(1, Map.of("hero-light",
                Map.of("id", ULID_A, "last_seen", "2025-12-01T00:00:00Z",
                        "triggers", Map.of("first", ULID_B)))));
        cases.put("a negative trigger index", document(1, Map.of("hero-light",
                Map.of("id", ULID_A, "last_seen", "2025-12-01T00:00:00Z",
                        "triggers", Map.of("-1", ULID_B)))));
        cases.put("a non-canonical trigger index", document(1, Map.of("hero-light",
                Map.of("id", ULID_A, "last_seen", "2025-12-01T00:00:00Z",
                        "triggers", Map.of("01", ULID_B)))));
        cases.put("a trigger id that is not a ULID", document(1, Map.of("hero-light",
                Map.of("id", ULID_A, "last_seen", "2025-12-01T00:00:00Z",
                        "triggers", Map.of("0", "nope")))));
        cases.put("one id under two slugs", document(1, Map.of(
                "hero-light", Map.of("id", ULID_A, "last_seen", "2025-12-01T00:00:00Z"),
                "porch", Map.of("id", ULID_A, "last_seen", "2025-12-01T00:00:00Z"))));
        return cases;
    }

    private static Map<String, Object> document(int schemaVersion, Map<String, Object> automations) {
        return documentOf("schema_version", schemaVersion, "automations", automations);
    }

    private static Map<String, Object> documentOf(Object... keysAndValues) {
        Map<String, Object> document = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            document.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return document;
    }

    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        Map<String, Object> typed = new LinkedHashMap<>();
        ((Map<?, ?>) value).forEach((key, element) -> typed.put((String) key, element));
        return typed;
    }

    /**
     * The in-memory companion: a {@code Map} holder that copies on the way in and on the
     * way out (a file shares no object with its reader), counts the writes, and can be
     * told to fail either direction.
     */
    private static final class MapCompanion implements AutomationIdentityCompanion {

        private Map<String, Object> document;
        private int replaceAttempts;
        private int replaces;
        private boolean failReads;
        private boolean failWrites;

        @Override
        public Optional<Map<String, Object>> read() {
            if (failReads) {
                throw new IllegalStateException("companion cannot be read: path=" + this);
            }
            return Optional.ofNullable(document).map(MapCompanion::copy);
        }

        @Override
        public void replace(Map<String, Object> newDocument) {
            replaceAttempts++;
            if (failWrites) {
                throw new IllegalStateException("companion cannot be written: path=" + this);
            }
            document = copy(newDocument);
            replaces++;
        }

        void preset(Map<String, Object> presetDocument) {
            document = copy(presetDocument);
        }

        void failReads(boolean fail) {
            failReads = fail;
        }

        void failWrites(boolean fail) {
            failWrites = fail;
        }

        Map<String, Object> document() {
            assertThat(document).as("the companion was written").isNotNull();
            return copy(document);
        }

        int replaceAttempts() {
            return replaceAttempts;
        }

        int replaces() {
            return replaces;
        }

        @Override
        public String toString() {
            return LOCATION;
        }

        private static Map<String, Object> copy(Map<String, Object> source) {
            Map<String, Object> copied = new LinkedHashMap<>();
            source.forEach((key, value) -> copied.put(key, copyValue(value)));
            return copied;
        }

        private static Object copyValue(Object value) {
            if (value instanceof Map<?, ?> nested) {
                Map<String, Object> copied = new LinkedHashMap<>();
                nested.forEach((key, element) -> copied.put((String) key, copyValue(element)));
                return copied;
            }
            return value;
        }
    }
}

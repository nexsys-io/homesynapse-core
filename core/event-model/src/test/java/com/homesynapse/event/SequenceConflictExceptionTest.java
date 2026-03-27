/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.homesynapse.platform.identity.EntityId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SequenceConflictException} — optimistic concurrency control exception.
 */
@DisplayName("SequenceConflictException")
class SequenceConflictExceptionTest {

    private static final Ulid ULID_A = new Ulid(0x0191B3C4D5E6F708L, 0x0123456789ABCDEFL);
    private static final SubjectRef SUBJECT_REF =
            SubjectRef.entity(EntityId.of(ULID_A));

    // ── Construction ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("fields accessible after construction")
        void fieldsAccessible() {
            var ex = new SequenceConflictException(SUBJECT_REF, 42L);

            assertThat(ex.subjectRef()).isEqualTo(SUBJECT_REF);
            assertThat(ex.conflictingSequence()).isEqualTo(42L);
        }

        @Test
        @DisplayName("message contains sequence number and subject")
        void messageContainsDetails() {
            var ex = new SequenceConflictException(SUBJECT_REF, 7L);

            assertThat(ex.getMessage())
                    .contains("7")
                    .contains(SUBJECT_REF.toString());
        }

        @Test
        @DisplayName("extends Exception (checked, not RuntimeException)")
        void extendsException() {
            var ex = new SequenceConflictException(SUBJECT_REF, 1L);

            assertThat(ex).isInstanceOf(Exception.class);
            assertThat(ex).isNotInstanceOf(RuntimeException.class);
        }
    }

    // ── Null validation ──────────────────────────────────────────────────

    @Test
    @DisplayName("null subjectRef throws NullPointerException")
    void nullSubjectRef() {
        assertThatNullPointerException().isThrownBy(() ->
                new SequenceConflictException(null, 1L))
                .withMessageContaining("subjectRef");
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.test.assertions;

import com.homesynapse.event.SubjectRef;
import com.homesynapse.event.SubjectType;
import com.homesynapse.platform.identity.Ulid;

import org.assertj.core.api.AbstractAssert;

import java.util.Objects;

/**
 * AssertJ assertion class for {@link SubjectRef}.
 *
 * <p>Usage:
 * <pre>{@code
 * assertThat(subjectRef)
 *     .isEntity()
 *     .hasId(expectedEntityId.value());
 * }</pre>
 *
 * @see HomeSynapseAssertions#assertThat(SubjectRef)
 */
public final class SubjectRefAssert
        extends AbstractAssert<SubjectRefAssert, SubjectRef> {

    SubjectRefAssert(SubjectRef actual) {
        super(actual, SubjectRefAssert.class);
    }

    /**
     * Verifies the subject type matches the expected value.
     *
     * @param expectedType the expected subject type
     * @return this assertion for chaining
     */
    public SubjectRefAssert hasType(SubjectType expectedType) {
        isNotNull();
        if (actual.type() != expectedType) {
            failWithMessage("Expected subject type <%s> but was <%s>",
                    expectedType, actual.type());
        }
        return this;
    }

    /**
     * Verifies the subject ULID matches the expected value.
     *
     * @param expectedId the expected ULID
     * @return this assertion for chaining
     */
    public SubjectRefAssert hasId(Ulid expectedId) {
        isNotNull();
        if (!Objects.equals(actual.id(), expectedId)) {
            failWithMessage("Expected subject ID <%s> but was <%s>",
                    expectedId, actual.id());
        }
        return this;
    }

    /**
     * Verifies the subject is an entity.
     *
     * @return this assertion for chaining
     */
    public SubjectRefAssert isEntity() {
        return hasType(SubjectType.ENTITY);
    }

    /**
     * Verifies the subject is a device.
     *
     * @return this assertion for chaining
     */
    public SubjectRefAssert isDevice() {
        return hasType(SubjectType.DEVICE);
    }

    /**
     * Verifies the subject is an integration adapter.
     *
     * @return this assertion for chaining
     */
    public SubjectRefAssert isIntegration() {
        return hasType(SubjectType.INTEGRATION);
    }

    /**
     * Verifies the subject is an automation rule.
     *
     * @return this assertion for chaining
     */
    public SubjectRefAssert isAutomation() {
        return hasType(SubjectType.AUTOMATION);
    }

    /**
     * Verifies the subject is the system instance.
     *
     * @return this assertion for chaining
     */
    public SubjectRefAssert isSystem() {
        return hasType(SubjectType.SYSTEM);
    }

    /**
     * Verifies the subject is a person.
     *
     * @return this assertion for chaining
     */
    public SubjectRefAssert isPerson() {
        return hasType(SubjectType.PERSON);
    }
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Tests for {@link JsonSchemaCompositeValidator} — the {@link ConfigValidator}
 * implementation over networknt json-schema-validator in allErrors mode
 * (Doc 06 §3.1 stage 5, §3.6).
 *
 * <p>Severity classification under the three-tier model (§3.6): violations of
 * {@code required} are FATAL (a missing required section is structural),
 * {@code additionalProperties} violations are WARNING (unknown key — possible
 * typo), and every value-level violation (type, enum, range, pattern) is
 * ERROR. ERROR issues carry the schema default that the startup pipeline
 * applies in their place (DP-2).</p>
 */
@DisplayName("JsonSchemaCompositeValidator (Doc 06 §3.6 three-tier model)")
class JsonSchemaCompositeValidatorTest {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "event_bus": {
                  "type": "object",
                  "properties": {
                    "queue_capacity": { "type": "integer", "minimum": 1, "default": 1024 },
                    "dispatch_mode": {
                      "type": "string",
                      "enum": ["serial", "parallel"],
                      "default": "serial"
                    }
                  },
                  "additionalProperties": false
                },
                "security": {
                  "type": "object",
                  "properties": {
                    "api_token": { "type": "string" }
                  },
                  "required": ["api_token"],
                  "additionalProperties": false
                }
              },
              "additionalProperties": false
            }
            """;

    private final JsonSchemaCompositeValidator validator =
            new JsonSchemaCompositeValidator();

    /** Creates a new test instance. */
    JsonSchemaCompositeValidatorTest() {
        // Explicit constructor per -Xlint:all -Werror requirement.
    }

    // ──────────────────────────────────────────────────────────────────
    // Contract basics
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Contract")
    class ContractTests {

        /** Creates a new test instance. */
        ContractTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("a fully valid configuration produces no issues")
        void validConfigProducesNoIssues() {
            Map<String, Object> config = Map.of(
                    "event_bus", Map.of("queue_capacity", 64, "dispatch_mode", "serial"));

            List<ConfigIssue> issues = validator.validate(config, SCHEMA);

            assertThat(issues).isEmpty();
        }

        @Test
        @DisplayName("the returned list is unmodifiable")
        void returnedListIsUnmodifiable() {
            List<ConfigIssue> issues = validator.validate(
                    Map.of("event_bus", Map.of("queue_capacity", "wrong")), SCHEMA);

            assertThat(issues).isNotEmpty();
            assertThat(issues).isUnmodifiable();
        }

        @Test
        @DisplayName("null arguments are rejected")
        void nullArgumentsRejected() {
            assertThatNullPointerException()
                    .isThrownBy(() -> validator.validate(null, SCHEMA));
            assertThatNullPointerException()
                    .isThrownBy(() -> validator.validate(Map.of(), null));
        }

        @Test
        @DisplayName("a malformed composed schema is rejected")
        void malformedSchemaRejected() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> validator.validate(Map.of(), "{not json"))
                    .withMessageContaining("JSON");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // allErrors mode (DP-2 / frozen ConfigValidator contract)
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("all issues are collected in a single pass (allErrors)")
    void allIssuesCollectedInOnePass() {
        Map<String, Object> config = Map.of(
                "event_bus", Map.of(
                        "queue_capacity", 0,
                        "dispatch_mode", "warp"));

        List<ConfigIssue> issues = validator.validate(config, SCHEMA);

        assertThat(issues).hasSize(2);
        assertThat(issues).extracting(ConfigIssue::path).containsExactlyInAnyOrder(
                "event_bus.queue_capacity", "event_bus.dispatch_mode");
    }

    // ──────────────────────────────────────────────────────────────────
    // Severity classification (Doc 06 §3.6)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Severity classification")
    class SeverityClassificationTests {

        /** Creates a new test instance. */
        SeverityClassificationTests() {
            // Explicit constructor per -Xlint:all -Werror requirement.
        }

        @Test
        @DisplayName("range violation is ERROR with the schema default attached")
        void rangeViolationIsError() {
            List<ConfigIssue> issues = validator.validate(
                    Map.of("event_bus", Map.of("queue_capacity", -5)), SCHEMA);

            assertThat(issues).hasSize(1);
            ConfigIssue issue = issues.get(0);
            assertThat(issue.severity()).isEqualTo(Severity.ERROR);
            assertThat(issue.path()).isEqualTo("event_bus.queue_capacity");
            assertThat(issue.invalidValue()).isEqualTo(-5);
            assertThat(issue.appliedDefault()).isEqualTo(1024);
        }

        @Test
        @DisplayName("type mismatch is ERROR")
        void typeMismatchIsError() {
            List<ConfigIssue> issues = validator.validate(
                    Map.of("event_bus", Map.of("queue_capacity", "many")), SCHEMA);

            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).severity()).isEqualTo(Severity.ERROR);
            assertThat(issues.get(0).invalidValue()).isEqualTo("many");
        }

        @Test
        @DisplayName("enum violation is ERROR with the schema default attached")
        void enumViolationIsError() {
            List<ConfigIssue> issues = validator.validate(
                    Map.of("event_bus", Map.of("dispatch_mode", "warp")), SCHEMA);

            assertThat(issues).hasSize(1);
            assertThat(issues.get(0).severity()).isEqualTo(Severity.ERROR);
            assertThat(issues.get(0).appliedDefault()).isEqualTo("serial");
        }

        @Test
        @DisplayName("unknown key is WARNING (possible typo; value accepted)")
        void unknownKeyIsWarning() {
            List<ConfigIssue> issues = validator.validate(
                    Map.of("event_bus", Map.of("qeue_capacity", 64)), SCHEMA);

            assertThat(issues).hasSize(1);
            ConfigIssue issue = issues.get(0);
            assertThat(issue.severity()).isEqualTo(Severity.WARNING);
            assertThat(issue.path()).isEqualTo("event_bus.qeue_capacity");
            assertThat(issue.appliedDefault()).isNull();
        }

        @Test
        @DisplayName("missing required key is FATAL with null invalidValue")
        void missingRequiredKeyIsFatal() {
            List<ConfigIssue> issues = validator.validate(
                    Map.of("security", Map.of()), SCHEMA);

            assertThat(issues).hasSize(1);
            ConfigIssue issue = issues.get(0);
            assertThat(issue.severity()).isEqualTo(Severity.FATAL);
            assertThat(issue.path()).isEqualTo("security.api_token");
            assertThat(issue.invalidValue()).isNull();
            assertThat(issue.appliedDefault()).isNull();
        }

        @Test
        @DisplayName("schema-validation issues carry no YAML line number")
        void schemaIssuesCarryNoYamlLine() {
            List<ConfigIssue> issues = validator.validate(
                    Map.of("event_bus", Map.of("queue_capacity", -5)), SCHEMA);

            assertThat(issues.get(0).yamlLine()).isNull();
        }
    }
}

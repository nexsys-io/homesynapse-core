/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.homesynapse.automation.RunCausalChain.ChainLink;
import com.homesynapse.platform.identity.AutomationId;
import com.homesynapse.platform.identity.Ulid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AMD-91 §5 — the per-Run causal lineage: derived depth, extend, chain-membership. */
@DisplayName("RunCausalChain (AMD-91)")
class RunCausalChainTest {

    private static final AutomationId A = AutomationId.of(Ulid.parse("00000000000000000000000000"));
    private static final AutomationId B = AutomationId.of(Ulid.parse("00000000000000000000000001"));
    private static final AutomationId C = AutomationId.of(Ulid.parse("00000000000000000000000002"));
    private static final ChainLink LINK_A = new ChainLink(run("00000000000000000000000010"), A);
    private static final ChainLink LINK_B = new ChainLink(run("00000000000000000000000011"), B);

    private static RunId run(String crockford) {
        return new RunId(Ulid.parse(crockford));
    }

    @Test
    @DisplayName("root has no ancestors and depth 0")
    void rootIsEmpty() {
        RunCausalChain root = RunCausalChain.root();
        assertThat(root.ancestors()).isEmpty();
        assertThat(root.depth()).isZero();
        assertThat(root.containsAutomation(A)).isFalse();
    }

    @Test
    @DisplayName("extend appends a link and increments depth, leaving the original unchanged")
    void extendIsImmutableAndAppends() {
        RunCausalChain root = RunCausalChain.root();
        RunCausalChain one = root.extend(LINK_A);
        RunCausalChain two = one.extend(LINK_B);

        assertThat(one.depth()).isEqualTo(1);
        assertThat(one.ancestors()).containsExactly(LINK_A);
        assertThat(two.depth()).isEqualTo(2);
        assertThat(two.ancestors()).containsExactly(LINK_A, LINK_B);

        // Originals are unchanged — extend returns a new chain.
        assertThat(root.depth()).isZero();
        assertThat(one.depth()).isEqualTo(1);
        assertThat(one.ancestors()).containsExactly(LINK_A);
    }

    @Test
    @DisplayName("containsAutomation is true for a member, false otherwise")
    void containsAutomation() {
        RunCausalChain chain = RunCausalChain.root().extend(LINK_A).extend(LINK_B);
        assertThat(chain.containsAutomation(A)).isTrue();
        assertThat(chain.containsAutomation(B)).isTrue();
        assertThat(chain.containsAutomation(C)).isFalse();
    }

    @Test
    @DisplayName("ancestors is unmodifiable (defensively copied)")
    void ancestorsUnmodifiable() {
        RunCausalChain chain = RunCausalChain.root().extend(LINK_A);
        assertThatThrownBy(() -> chain.ancestors().add(LINK_B))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the compact constructor copies the supplied list defensively")
    void constructorDefensiveCopy() {
        var ancestors = new java.util.ArrayList<>(List.of(LINK_A));
        RunCausalChain chain = new RunCausalChain(ancestors);
        ancestors.add(LINK_B);
        assertThat(chain.ancestors()).containsExactly(LINK_A);
    }

    @Test
    @DisplayName("equal chains are value-equal")
    void valueEquality() {
        RunCausalChain a = RunCausalChain.root().extend(LINK_A);
        RunCausalChain b = RunCausalChain.root().extend(LINK_A);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(RunCausalChain.root().extend(LINK_B));
    }

    @Test
    @DisplayName("ChainLink and chain reject nulls")
    void nullValidation() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ChainLink(null, A)).withMessageContaining("runId");
        assertThatNullPointerException()
                .isThrownBy(() -> new ChainLink(run("00000000000000000000000010"), null))
                .withMessageContaining("automationId");
        assertThatNullPointerException()
                .isThrownBy(() -> new RunCausalChain(null)).withMessageContaining("ancestors");
        assertThatNullPointerException()
                .isThrownBy(() -> RunCausalChain.root().extend(null)).withMessageContaining("parent");
        assertThatNullPointerException()
                .isThrownBy(() -> RunCausalChain.root().containsAutomation(null))
                .withMessageContaining("id");
    }
}

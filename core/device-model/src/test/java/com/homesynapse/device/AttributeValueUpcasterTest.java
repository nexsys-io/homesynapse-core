/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import com.homesynapse.value.AttributeType;
import com.homesynapse.value.AttributeValue;
import com.homesynapse.value.DegradedAttributeValue;
import com.homesynapse.value.QuantityValue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link AttributeValueUpcaster} SPI strict/lenient contract
 * (AMD-47 §5 test #4, AMD-47-INV-02/-04), exercised via a small test upcaster.
 *
 * <p><strong>Carried to M4.0b-3 (DP-1):</strong> the AMD-47-INV-02 both-paths path test —
 * proving the upcaster runs strictly before {@code DerivationRule.evaluate()} on BOTH the
 * {@code onEvent} and {@code processBatch} projection paths, and failing if either path
 * reaches {@code evaluate()} with an un-upcast stored value — lands with the production
 * projection-path wiring in M4.0b-3. It is intentionally not attempted here; M4.B3 exercises
 * the SPI contract directly, because through M4.0b-2 the production rule is still string
 * change-detect and no typed value is produced or stored to upcast.</p>
 */
@DisplayName("AttributeValueUpcaster strict/lenient contract (AMD-47-INV-02/-04)")
class AttributeValueUpcasterTest {

    private static final String KNOWN = "LegacyTemperatureValue";

    /**
     * Test upcaster: transforms one known stored type to a {@link QuantityValue} and rejects
     * everything else. A malformed raw form for the known type surfaces as a thrown
     * {@link RuntimeException}.
     */
    private static final class FixedUpcaster implements AttributeValueUpcaster {
        @Override
        public boolean canUpcast(String storedTypeName, int fromSchemaVersion) {
            return KNOWN.equals(storedTypeName);
        }

        @Override
        public AttributeValue upcast(String storedTypeName, String rawForm, int fromSchemaVersion) {
            if (!KNOWN.equals(storedTypeName)) {
                throw new IllegalArgumentException(
                        "no upcaster for type " + storedTypeName + " at version " + fromSchemaVersion);
            }
            return new QuantityValue(Double.parseDouble(rawForm), "°C");
        }
    }

    private final AttributeValueUpcaster upcaster = new FixedUpcaster();

    @Test
    @DisplayName("strict upcast returns the value on success")
    void strictUpcastReturnsValueOnSuccess() {
        AttributeValue result = upcaster.upcast(KNOWN, "22.5", 1);
        assertThat(result).isEqualTo(new QuantityValue(22.5, "°C"));
    }

    @Test
    @DisplayName("strict upcast throws on an un-upcastable value and never returns a degraded value")
    void strictUpcastThrowsOnUnupcastable() {
        assertThatThrownBy(() -> upcaster.upcast("UnknownType", "x", 1))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("lenient upcast yields a DegradedAttributeValue on an unsupported type")
    void lenientYieldsDegradedOnFailure() {
        AttributeValue result = upcaster.upcastLenient("UnknownType", "raw-form", 1);

        assertThat(result).isInstanceOf(DegradedAttributeValue.class);
        DegradedAttributeValue degraded = (DegradedAttributeValue) result;
        assertThat(degraded.rawForm()).isEqualTo("raw-form");
        assertThat(degraded.originalTypeName()).isEqualTo("UnknownType");
        assertThat(degraded.failureReason()).isNotBlank();
        assertThat(degraded.attributeType()).isEqualTo(AttributeType.DEGRADED);
    }

    @Test
    @DisplayName("lenient upcast yields a DegradedAttributeValue when a supported upcast throws")
    void lenientYieldsDegradedWhenUpcastThrows() {
        // canUpcast(KNOWN) is true, but a malformed raw form makes upcast() throw — the
        // lenient default catches the RuntimeException and degrades rather than propagating.
        AttributeValue result = upcaster.upcastLenient(KNOWN, "not-a-number", 1);

        assertThat(result).isInstanceOf(DegradedAttributeValue.class);
        DegradedAttributeValue degraded = (DegradedAttributeValue) result;
        assertThat(degraded.rawForm()).isEqualTo("not-a-number");
        assertThat(degraded.failureReason()).isNotBlank();
    }

    @Test
    @DisplayName("lenient upcast returns the value (not degraded) on success")
    void lenientReturnsValueOnSuccess() {
        AttributeValue result = upcaster.upcastLenient(KNOWN, "18.0", 1);
        assertThat(result).isInstanceOf(QuantityValue.class);
        assertThat(result).isEqualTo(new QuantityValue(18.0, "°C"));
    }

    @Test
    @DisplayName("canUpcast reflects supported types")
    void canUpcastReflectsSupport() {
        assertThat(upcaster.canUpcast(KNOWN, 1)).isTrue();
        assertThat(upcaster.canUpcast("UnknownType", 1)).isFalse();
    }
}

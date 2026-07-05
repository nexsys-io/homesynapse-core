/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.device;

import java.util.Map;

/**
 * Standard capability for device self-identification (blink/breathe locate).
 *
 * <p>Identify is issuable-but-inherently-unconfirmable: it declares NO
 * attributes (a locate effect has no reportable state — nothing to confirm,
 * by construction) and a single {@code identify} command with an optional
 * {@code duration_s} parameter. The confirmation policy is
 * {@link ConfirmationMode#DISABLED} at the capability root, so the pending
 * command ledger never tracks it (AMD-97-INV-01 structural); the issuing
 * integration adapter owns the immediate honest verdict (Doc 02 §3.8 — SD-3,
 * pinned: "an immediate rendered {@code UNCONFIRMED} verdict with recorded
 * reason, not the silent DISABLED bypass; never-tracked and honestly-verdicted
 * are different promises").</p>
 *
 * <p>Cross-cutting — attached wherever the protocol exposes a locate surface
 * (e.g. a Zigbee endpoint carrying the Identify cluster).</p>
 *
 * <p>Capability ID: {@code "identify"}. Doc 02 §3.8; M9.4b §3.1.</p>
 *
 * @param capabilityId the capability identifier, always {@code "identify"} for standard instances
 * @param version the schema version
 * @param namespace the owning namespace, always {@code "core"} for standard instances
 * @param attributeSchemas the attribute schemas keyed by attribute key; unmodifiable (empty)
 * @param commandDefinitions the command definitions keyed by command type; unmodifiable
 * @param confirmationPolicy the confirmation policy
 * @see OnOff
 * @since 1.0
 */
public record Identify(
        String capabilityId,
        int version,
        String namespace,
        Map<String, AttributeSchema> attributeSchemas,
        Map<String, CommandDefinition> commandDefinitions,
        ConfirmationPolicy confirmationPolicy
) implements Capability { }

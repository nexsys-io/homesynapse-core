/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */

/**
 * Value model — the self-contained {@code AttributeValue} hierarchy and
 * {@code AttributeType}. A leaf module that requires nothing but {@code java.base},
 * shared by {@code com.homesynapse.event} and {@code com.homesynapse.device} as
 * peers over a common value contract (AttributeValue Module Relocation, 2026-05-31).
 */
module com.homesynapse.value {
    exports com.homesynapse.value;
}

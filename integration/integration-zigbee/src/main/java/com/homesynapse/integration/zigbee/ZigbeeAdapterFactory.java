/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.homesynapse.integration.IntegrationFactory;

/**
 * Factory for the Zigbee integration adapter.
 *
 * <p>Extends {@link IntegrationFactory} with no additional methods — the base
 * {@link IntegrationFactory#descriptor()} and
 * {@link IntegrationFactory#create(com.homesynapse.integration.IntegrationContext)}
 * are sufficient for the Zigbee adapter's factory contract.
 *
 * <p>The {@code descriptor()} method returns a pre-populated
 * {@link com.homesynapse.integration.IntegrationDescriptor} with:
 * <ul>
 *   <li>integrationType: {@code "zigbee"}</li>
 *   <li>displayName: {@code "Zigbee"}</li>
 *   <li>ioType: {@link com.homesynapse.integration.IoType#SERIAL}</li>
 *   <li>requiredServices: {@link com.homesynapse.integration.RequiredService#SCHEDULER},
 *       {@link com.homesynapse.integration.RequiredService#TELEMETRY_WRITER}</li>
 *   <li>dataPaths: {@link com.homesynapse.integration.DataPath#DOMAIN},
 *       {@link com.homesynapse.integration.DataPath#TELEMETRY}</li>
 *   <li>healthParameters: per Doc 08 §4.1 (heartbeatTimeout 600s, healthWindowSize 20, etc.)</li>
 * </ul>
 *
 * <p>Per DECIDE-04, this factory is instantiated directly by the application module
 * ({@code new ZigbeeAdapterFactoryImpl()}), not discovered via ServiceLoader.
 *
 * <p>Doc 08 §8.1.
 *
 * <p>Thread-safe: implementations must be safe for concurrent access.
 *
 * @see IntegrationFactory
 * @see com.homesynapse.integration.IntegrationDescriptor
 * @see ZigbeeAdapter
 */
public interface ZigbeeAdapterFactory extends IntegrationFactory {
}

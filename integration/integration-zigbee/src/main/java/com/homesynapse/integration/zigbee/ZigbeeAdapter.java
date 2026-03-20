package com.homesynapse.integration.zigbee;

import com.homesynapse.integration.IntegrationAdapter;

import java.util.Collection;
import java.util.Optional;

/**
 * Zigbee integration adapter extending the base {@link IntegrationAdapter} lifecycle.
 *
 * <p>Lifecycle phases:
 * <ul>
 *   <li>{@link IntegrationAdapter#initialize()} — configures the serial port and prepares
 *       the coordinator protocol layer but does NOT connect to the serial device
 *       (INV-RF-03 — startup independence).</li>
 *   <li>{@link IntegrationAdapter#run()} — opens the serial connection, auto-detects
 *       coordinator type (ZNP vs EZSP), forms or resumes the Zigbee network, and enters
 *       the main frame processing loop.</li>
 *   <li>{@link IntegrationAdapter#close()} — gracefully shuts down the coordinator
 *       connection and persists the device metadata cache to
 *       {@code zigbee-devices.json}.</li>
 * </ul>
 *
 * <p>The adapter runs on a platform thread ({@code IoType.SERIAL}) for the transport
 * layer and spawns virtual threads for the protocol layer. The coordinator type (ZNP
 * vs EZSP) is an internal detail invisible to the rest of the system (INV-CE-04).
 *
 * <p>Doc 08 §8.1.
 *
 * <p>Thread-safe: query methods are safe for concurrent access from API and diagnostic
 * threads.
 *
 * @see IntegrationAdapter
 * @see ZigbeeAdapterFactory
 * @see ZigbeeDeviceRecord
 * @see NetworkParameters
 */
public interface ZigbeeAdapter extends IntegrationAdapter {

    /**
     * Returns the local metadata cache entry for a device by IEEE address.
     *
     * @param address the device's IEEE address, never {@code null}
     * @return the device record, or empty if no device with that address is known
     */
    Optional<ZigbeeDeviceRecord> device(IEEEAddress address);

    /**
     * Returns all cached device records.
     *
     * @return an unmodifiable collection of all device records, never {@code null}
     */
    Collection<ZigbeeDeviceRecord> allDevices();

    /**
     * Returns the matched device profile for a device by IEEE address.
     *
     * @param address the device's IEEE address, never {@code null}
     * @return the matched device profile, or empty if no profile matches
     */
    Optional<DeviceProfile> deviceProfile(IEEEAddress address);

    /**
     * Returns the current Zigbee network parameters.
     *
     * @return the network parameters (channel, PAN ID, etc.), never {@code null}
     */
    NetworkParameters networkParameters();

    /**
     * Returns whether permit-join is currently enabled on the coordinator.
     *
     * @return {@code true} if the pairing window is open, {@code false} otherwise
     */
    boolean isPermitJoinActive();
}

/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.integration.zigbee;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Production {@link PortLocator.PortEnumerator}: enumerates via
 * {@code SerialPort.getCommPorts()} and resolves {@code /dev/serial/by-id} symlinks
 * best-effort (Linux hosts; silently absent elsewhere).
 *
 * <p>VID/PID come from enumeration — jSerialComm reports {@code -1} for both on a
 * path-constructed port, which is why identity capture happens at enumeration time,
 * never from a reopened handle.
 *
 * <p>Deliberately thin and untested in the M9.2 unit tree (zero real serial I/O —
 * D-M92-3); exercised at M9.4 bench acceptance.
 *
 * <p>Thread-safe: stateless.
 */
final class JSerialCommPortEnumerator implements PortLocator.PortEnumerator {

    private static final Logger log =
            LoggerFactory.getLogger(JSerialCommPortEnumerator.class);
    private static final Path BY_ID_DIRECTORY = Path.of("/dev/serial/by-id");

    /** Creates the enumerator. Performs no I/O (INV-RF-03). */
    JSerialCommPortEnumerator() {
    }

    @Override
    public List<PortCandidate> enumerate() {
        Map<String, String> byIdBySystemPath = resolveByIdLinks();
        List<PortCandidate> candidates = new ArrayList<>();
        for (SerialPort port : SerialPort.getCommPorts()) {
            String systemPath = port.getSystemPortPath();
            candidates.add(new PortCandidate(
                    systemPath,
                    byIdBySystemPath.get(systemPath),
                    port.getVendorID(),
                    port.getProductID(),
                    port.getDescriptivePortName()));
        }
        return candidates;
    }

    /** Maps real device paths to their stable by-id symlink paths, best-effort. */
    private static Map<String, String> resolveByIdLinks() {
        Map<String, String> links = new HashMap<>();
        if (!Files.isDirectory(BY_ID_DIRECTORY)) {
            return links;
        }
        try (Stream<Path> entries = Files.list(BY_ID_DIRECTORY)) {
            for (Path link : entries.toList()) {
                try {
                    links.put(link.toRealPath().toString(), link.toString());
                } catch (IOException e) {
                    log.debug("unresolvable by-id link {}: {}", link, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("cannot list {}: {}", BY_ID_DIRECTORY, e.getMessage());
        }
        return links;
    }
}

package com.homesynapse.integration.zigbee;

/**
 * Abstraction over serial protocol framing for Zigbee coordinator communication.
 *
 * <p>Two implementations exist: {@code ZnpTransport} for ZNP coordinators (UNPI framing,
 * XOR checksum) and {@code EzspAshTransport} for EZSP coordinators (ASH framing,
 * CRC-CCITT, byte stuffing, data derandomization, ACK/NAK).
 *
 * <p>The transport layer runs on a dedicated platform thread ({@code IoType.SERIAL} per
 * LTD-01, Doc 05 §3.2) to isolate JNI-induced carrier thread pinning from the virtual
 * thread pool. Serial I/O via jSerialComm uses JNI calls that permanently pin carrier
 * threads; dedicating a platform thread prevents 25% capacity loss on the Pi's 4 cores.
 *
 * <p>Doc 08 §8.1.
 *
 * <p>Not thread-safe: single-threaded access by the transport thread.
 *
 * @see ZigbeeFrame
 * @see CoordinatorProtocol
 */
public interface CoordinatorTransport {

    /**
     * Opens the serial connection to the coordinator.
     *
     * <p>Phase 2 declares the parameter as {@code Object}; Phase 3 uses jSerialComm's
     * {@code SerialPort} type.
     *
     * @param serialPort the serial port to open, never {@code null}
     */
    void open(Object serialPort);

    /**
     * Closes the serial connection to the coordinator.
     *
     * <p>Idempotent: calling close on an already-closed transport has no effect.
     */
    void close();

    /**
     * Serializes and transmits a raw frame to the coordinator.
     *
     * @param data the raw frame bytes to transmit, never {@code null}
     */
    void sendFrame(byte[] data);

    /**
     * Blocking read of the next complete frame from the serial port.
     *
     * <p>Blocks until a complete, valid frame is received from the coordinator.
     * Invalid frames (checksum failures, incomplete reads) are silently discarded
     * and the method continues waiting for the next valid frame.
     *
     * @return the next complete frame, never {@code null}
     */
    ZigbeeFrame receiveFrame();
}

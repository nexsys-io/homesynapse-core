/*
 * HomeSynapse Core
 * Copyright (c) 2026 NexSys. All rights reserved.
 */
package com.homesynapse.api.ws;

import com.homesynapse.api.rest.ApiException;

/**
 * Serializes and deserializes WebSocket messages between JSON text frames
 * and typed {@link WsMessage} subtypes.
 *
 * <p>Uses the shared Jackson ObjectMapper (LTD-08) with {@code SNAKE_CASE}
 * property naming. The {@link #decode(String)} method inspects the
 * {@code type} field in the JSON envelope to determine which {@link WsMessage}
 * record subtype to instantiate. Field names follow the wire format
 * (snake_case per LTD-08) — Jackson handles the translation to Java
 * camelCase.</p>
 *
 * <p>Thread-safe and stateless.</p>
 *
 * @see WsMessage
 * @see WebSocketHandler
 * @see <a href="Doc 10 §8.1">Service Interfaces</a>
 */
public interface MessageCodec {

    /**
     * Deserializes a JSON text frame to the appropriate {@link WsMessage} subtype.
     *
     * <p>Inspects the {@code type} field to determine the target record type.
     * Throws {@link ApiException} with
     * {@link com.homesynapse.api.rest.ProblemType#INVALID_PARAMETERS} if the
     * JSON is malformed or the {@code type} field is unrecognized.</p>
     *
     * @param json the raw JSON text frame content, never {@code null}
     * @return the deserialized message, never {@code null}
     * @throws ApiException if the JSON is malformed or contains an
     *                      unrecognized message type
     */
    WsMessage decode(String json) throws ApiException;

    /**
     * Serializes a {@link WsMessage} subtype to a JSON text frame.
     *
     * @param message the message to serialize, never {@code null}
     * @return the JSON text frame content, never {@code null}
     */
    String encode(WsMessage message);
}

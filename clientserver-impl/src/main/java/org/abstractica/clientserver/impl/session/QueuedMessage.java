package org.abstractica.clientserver.impl.session;

import org.abstractica.clientserver.impl.protocol.Disconnect;
import org.abstractica.clientserver.impl.protocol.Heartbeat;
import org.abstractica.clientserver.impl.protocol.HeartbeatAck;

import java.util.Objects;

/**
 * Messages enqueued to a session for processing by its virtual thread.
 *
 * <p>This sealed interface allows exhaustive pattern matching on message types.</p>
 */
public sealed interface QueuedMessage
{
    /**
     * Application message to be deserialized and dispatched to a handler.
     *
     * @param messageTypeId the protocol message type ID
     * @param payload       the serialized message payload
     */
    record ApplicationMessage(int messageTypeId, byte[] payload) implements QueuedMessage
    {
        public ApplicationMessage
        {
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * Heartbeat received from peer, needs response.
     *
     * @param heartbeat the received heartbeat packet
     */
    record ControlHeartbeat(Heartbeat heartbeat) implements QueuedMessage
    {
        public ControlHeartbeat
        {
            Objects.requireNonNull(heartbeat, "heartbeat");
        }
    }

    /**
     * Heartbeat acknowledgment received from peer.
     *
     * @param ack the received heartbeat ack packet
     */
    record ControlHeartbeatAck(HeartbeatAck ack) implements QueuedMessage
    {
        public ControlHeartbeatAck
        {
            Objects.requireNonNull(ack, "ack");
        }
    }

    /**
     * Disconnect request received from peer.
     *
     * @param disconnect the received disconnect packet
     */
    record ControlDisconnect(Disconnect disconnect) implements QueuedMessage
    {
        public ControlDisconnect
        {
            Objects.requireNonNull(disconnect, "disconnect");
        }
    }

    /**
     * Periodic tick for maintenance (heartbeats, retransmits, timeout checks).
     *
     * @param nowMs current time in milliseconds
     */
    record ControlTick(long nowMs) implements QueuedMessage {}
}

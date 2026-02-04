package org.abstractica.clientserver.impl.reliability;

import org.abstractica.clientserver.impl.protocol.Ack;
import org.abstractica.clientserver.impl.protocol.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ReliabilityLayer}.
 */
class ReliabilityLayerTest
{
    private ReliabilityLayer layer;

    @BeforeEach
    void setUp()
    {
        layer = new ReliabilityLayer();
    }

    // ========== sendReliable ==========

    @Test
    void sendReliable_createsSequencedPacket()
    {
        Optional<Data> packet = layer.sendReliable(1, new byte[]{1, 2, 3}, 1000);

        assertTrue(packet.isPresent());
        assertTrue(packet.get().reliable());
        assertEquals(0, packet.get().sequence().orElse(-1));
        assertEquals(1, packet.get().messageTypeId());
        assertArrayEquals(new byte[]{1, 2, 3}, packet.get().payload());
    }

    @Test
    void sendReliable_incrementsSequence()
    {
        Data packet0 = layer.sendReliable(1, new byte[0], 1000).orElseThrow();
        Data packet1 = layer.sendReliable(1, new byte[0], 1000).orElseThrow();
        Data packet2 = layer.sendReliable(1, new byte[0], 1000).orElseThrow();

        assertEquals(0, packet0.sequence().orElse(-1));
        assertEquals(1, packet1.sequence().orElse(-1));
        assertEquals(2, packet2.sequence().orElse(-1));
    }

    @Test
    void sendReliable_backpressure_returnsEmpty()
    {
        // Use small queue
        RttEstimator rtt = new RttEstimator();
        OutboundQueue queue = new OutboundQueue(rtt, 3, 10);
        ReliabilityLayer smallLayer = new ReliabilityLayer(rtt, queue, new InboundBuffer());

        assertTrue(smallLayer.sendReliable(1, new byte[0], 1000).isPresent());
        assertTrue(smallLayer.sendReliable(1, new byte[0], 1000).isPresent());
        assertTrue(smallLayer.sendReliable(1, new byte[0], 1000).isPresent());

        // Queue is now full
        assertTrue(smallLayer.isBackpressured());
        assertFalse(smallLayer.sendReliable(1, new byte[0], 1000).isPresent());
    }

    // ========== sendUnreliable ==========

    @Test
    void sendUnreliable_createsUnsequencedPacket()
    {
        Data packet = layer.sendUnreliable(2, new byte[]{4, 5}, 1000);

        assertFalse(packet.reliable());
        assertTrue(packet.sequence().isEmpty());
        assertEquals(2, packet.messageTypeId());
        assertArrayEquals(new byte[]{4, 5}, packet.payload());
    }

    // ========== receive Data ==========

    @Test
    void receiveData_unreliable_deliversImmediately()
    {
        Data packet = Data.unreliable(1, new byte[]{1, 2});

        ReliabilityLayer.ReceiveResult result = layer.receive(packet, 1000);

        assertEquals(1, result.deliverableMessages().size());
        assertEquals(1, result.deliverableMessages().get(0).messageTypeId());
        assertArrayEquals(new byte[]{1, 2}, result.deliverableMessages().get(0).payload());
        assertFalse(result.isDuplicate());
    }

    @Test
    void receiveData_reliable_inOrder_delivers()
    {
        Data packet0 = Data.reliable(0, 1, new byte[]{0});
        Data packet1 = Data.reliable(1, 2, new byte[]{1});

        ReliabilityLayer.ReceiveResult result0 = layer.receive(packet0, 1000);
        ReliabilityLayer.ReceiveResult result1 = layer.receive(packet1, 1000);

        assertEquals(1, result0.deliverableMessages().size());
        assertEquals(1, result1.deliverableMessages().size());
        assertArrayEquals(new byte[]{0}, result0.deliverableMessages().get(0).payload());
        assertArrayEquals(new byte[]{1}, result1.deliverableMessages().get(0).payload());
    }

    @Test
    void receiveData_reliable_outOfOrder_buffers()
    {
        Data packet2 = Data.reliable(2, 1, new byte[]{2});
        Data packet0 = Data.reliable(0, 1, new byte[]{0});

        // Receive out of order
        ReliabilityLayer.ReceiveResult result2 = layer.receive(packet2, 1000);
        assertEquals(0, result2.deliverableMessages().size()); // Buffered

        // Receive in order
        ReliabilityLayer.ReceiveResult result0 = layer.receive(packet0, 1000);
        assertEquals(1, result0.deliverableMessages().size()); // Only 0 delivered
    }

    @Test
    void receiveData_reliable_fillsGap_deliversMultiple()
    {
        Data packet0 = Data.reliable(0, 1, new byte[]{0});
        Data packet2 = Data.reliable(2, 1, new byte[]{2});
        Data packet1 = Data.reliable(1, 1, new byte[]{1});

        layer.receive(packet0, 1000);
        layer.receive(packet2, 1000); // Buffered

        ReliabilityLayer.ReceiveResult result = layer.receive(packet1, 1000);

        // Should deliver 1 and 2
        assertEquals(2, result.deliverableMessages().size());
        assertArrayEquals(new byte[]{1}, result.deliverableMessages().get(0).payload());
        assertArrayEquals(new byte[]{2}, result.deliverableMessages().get(1).payload());
    }

    @Test
    void receiveData_duplicate_detected()
    {
        Data packet = Data.reliable(0, 1, new byte[]{0});

        layer.receive(packet, 1000);
        ReliabilityLayer.ReceiveResult result = layer.receive(packet, 1000);

        assertTrue(result.isDuplicate());
        assertEquals(0, result.deliverableMessages().size());
    }

    @Test
    void receiveData_withPiggybackedAck_processesAck()
    {
        // Send reliable message
        layer.sendReliable(1, new byte[]{1}, 1000);
        assertEquals(1, layer.getPendingOutboundCount());

        // Receive data with piggybacked ack for seq 0
        Data packetWithAck = Data.reliable(0, 1, new byte[]{0}).withAck(0);
        layer.receive(packetWithAck, 1000);

        // Our outbound should be acked
        assertEquals(0, layer.getPendingOutboundCount());
    }

    // ========== receive Ack ==========

    @Test
    void receiveAck_acknowledgesOutbound()
    {
        layer.sendReliable(1, new byte[0], 1000);
        layer.sendReliable(1, new byte[0], 1000);
        layer.sendReliable(1, new byte[0], 1000);
        assertEquals(3, layer.getPendingOutboundCount());

        int acked = layer.receive(new Ack(1, 0), 1000);

        assertEquals(2, acked); // Seq 0 and 1
        assertEquals(1, layer.getPendingOutboundCount()); // Only seq 2 remains
    }

    @Test
    void receiveAck_selectiveAck()
    {
        layer.sendReliable(1, new byte[0], 1000);
        layer.sendReliable(1, new byte[0], 1000);
        layer.sendReliable(1, new byte[0], 1000);
        layer.sendReliable(1, new byte[0], 1000);

        // Ack seq 0 cumulative, selective ack for seq 3 (bit 2)
        int acked = layer.receive(new Ack(0, 0b100), 1000);

        assertEquals(2, acked); // Seq 0 and 3
        assertEquals(2, layer.getPendingOutboundCount()); // Seq 1 and 2 remain
    }

    // ========== tick ==========

    @Test
    void tick_returnsRetransmits()
    {
        layer.sendReliable(1, new byte[]{1}, 0);

        // Wait for timeout (default RTT 200ms * 1.5 = 300ms, min 50ms)
        ReliabilityLayer.TickResult result = layer.tick(500);

        assertEquals(1, result.retransmits().size());
        assertEquals(0, result.retransmits().get(0).sequence().orElse(-1));
        assertTrue(result.expired().isEmpty());
    }

    @Test
    void tick_noRetransmitsIfAcked()
    {
        layer.sendReliable(1, new byte[]{1}, 0);
        layer.receive(new Ack(0, 0), 100);

        ReliabilityLayer.TickResult result = layer.tick(500);

        assertTrue(result.retransmits().isEmpty());
        assertTrue(result.expired().isEmpty());
    }

    @Test
    void tick_identifiesExpired()
    {
        RttEstimator rtt = new RttEstimator(100);
        OutboundQueue queue = new OutboundQueue(rtt, 10, 2); // Max 2 attempts
        ReliabilityLayer testLayer = new ReliabilityLayer(rtt, queue, new InboundBuffer());

        testLayer.sendReliable(1, new byte[]{1}, 0);

        // First tick - retransmit
        ReliabilityLayer.TickResult result1 = testLayer.tick(200);
        assertEquals(1, result1.retransmits().size());

        // Second tick - expired (2 attempts exhausted)
        ReliabilityLayer.TickResult result2 = testLayer.tick(1000);
        assertEquals(1, result2.expired().size());
        assertEquals(0, (int) result2.expired().get(0));
    }

    // ========== getAckToSend ==========

    @Test
    void getAckToSend_afterReceive()
    {
        // No ack needed initially
        assertFalse(layer.getAckToSend(1000).isPresent());

        // Receive a message
        layer.receive(Data.reliable(0, 1, new byte[0]), 1000);

        // Now ack is pending
        assertTrue(layer.isAckPending());

        // Get ack (with enough delay)
        Optional<Ack> ack = layer.getAckToSend(1100);
        assertTrue(ack.isPresent());
        assertEquals(0, ack.get().ackSequence());
    }

    @Test
    void getAckToSend_includesBitmap()
    {
        layer.receive(Data.reliable(0, 1, new byte[0]), 1000);
        layer.receive(Data.reliable(2, 1, new byte[0]), 1000); // Gap at 1

        Optional<Ack> ack = layer.getAckToSend(1100);

        assertTrue(ack.isPresent());
        assertEquals(0, ack.get().ackSequence()); // Cumulative ack at 0
        assertTrue((ack.get().ackBitmap() & 0b10) != 0); // Seq 2 in bitmap
    }

    @Test
    void sendReliable_piggybacksAck()
    {
        // Receive a message to trigger ack pending
        layer.receive(Data.reliable(0, 1, new byte[0]), 1000);
        assertTrue(layer.isAckPending());

        // Send reliable message - should piggyback the ack
        Data packet = layer.sendReliable(1, new byte[0], 1100).orElseThrow();

        assertTrue(packet.ackSequence().isPresent());
        assertEquals(0, packet.ackSequence().orElse(-1));
        assertFalse(layer.isAckPending());
    }

    @Test
    void sendUnreliable_piggybacksAck()
    {
        layer.receive(Data.reliable(0, 1, new byte[0]), 1000);
        assertTrue(layer.isAckPending());

        Data packet = layer.sendUnreliable(1, new byte[0], 1100);

        assertTrue(packet.ackSequence().isPresent());
        assertFalse(layer.isAckPending());
    }

    // ========== Integration scenarios ==========

    @Test
    void integration_bidirectionalCommunication()
    {
        ReliabilityLayer sender = new ReliabilityLayer();
        ReliabilityLayer receiver = new ReliabilityLayer();

        // Sender sends 3 messages
        Data msg0 = sender.sendReliable(1, new byte[]{0}, 0).orElseThrow();
        Data msg1 = sender.sendReliable(1, new byte[]{1}, 0).orElseThrow();
        Data msg2 = sender.sendReliable(1, new byte[]{2}, 0).orElseThrow();

        // Receiver gets them
        receiver.receive(msg0, 10);
        receiver.receive(msg1, 10);
        receiver.receive(msg2, 10);

        // Receiver sends ack
        Ack ack = receiver.getAckToSend(100).orElseThrow();
        assertEquals(2, ack.ackSequence()); // All received

        // Sender processes ack
        int acked = sender.receive(ack, 100);
        assertEquals(3, acked);
        assertEquals(0, sender.getPendingOutboundCount());
    }

    @Test
    void integration_packetLoss()
    {
        ReliabilityLayer sender = new ReliabilityLayer();
        ReliabilityLayer receiver = new ReliabilityLayer();

        // Sender sends 3 messages
        Data msg0 = sender.sendReliable(1, new byte[]{0}, 0).orElseThrow();
        Data msg1 = sender.sendReliable(1, new byte[]{1}, 0).orElseThrow();
        Data msg2 = sender.sendReliable(1, new byte[]{2}, 0).orElseThrow();

        // Receiver only gets msg0 and msg2 (msg1 lost)
        ReliabilityLayer.ReceiveResult r0 = receiver.receive(msg0, 10);
        ReliabilityLayer.ReceiveResult r2 = receiver.receive(msg2, 10);

        assertEquals(1, r0.deliverableMessages().size()); // msg0 delivered
        assertEquals(0, r2.deliverableMessages().size()); // msg2 buffered

        // Receiver sends ack
        Ack ack = receiver.getAckToSend(100).orElseThrow();
        assertEquals(0, ack.ackSequence()); // Only 0 consecutive
        assertTrue((ack.ackBitmap() & 0b10) != 0); // Seq 2 in bitmap

        // Sender processes ack
        sender.receive(ack, 100);
        assertEquals(1, sender.getPendingOutboundCount()); // Only seq 1 pending

        // Sender retransmits after timeout
        ReliabilityLayer.TickResult tick = sender.tick(500);
        assertEquals(1, tick.retransmits().size());

        // Retransmitted msg1 arrives
        ReliabilityLayer.ReceiveResult r1 = receiver.receive(tick.retransmits().get(0), 500);
        assertEquals(2, r1.deliverableMessages().size()); // Both 1 and 2 now delivered
    }

    @Test
    void integration_reordering()
    {
        ReliabilityLayer sender = new ReliabilityLayer();
        ReliabilityLayer receiver = new ReliabilityLayer();

        Data msg0 = sender.sendReliable(1, new byte[]{0}, 0).orElseThrow();
        Data msg1 = sender.sendReliable(1, new byte[]{1}, 0).orElseThrow();
        Data msg2 = sender.sendReliable(1, new byte[]{2}, 0).orElseThrow();

        // Messages arrive out of order: 2, 0, 1
        ReliabilityLayer.ReceiveResult r2 = receiver.receive(msg2, 10);
        ReliabilityLayer.ReceiveResult r0 = receiver.receive(msg0, 20);
        ReliabilityLayer.ReceiveResult r1 = receiver.receive(msg1, 30);

        // Only r0 delivers immediately (first in-order)
        assertEquals(0, r2.deliverableMessages().size());
        assertEquals(1, r0.deliverableMessages().size());
        assertEquals(2, r1.deliverableMessages().size()); // 1 and 2 delivered together
    }
}

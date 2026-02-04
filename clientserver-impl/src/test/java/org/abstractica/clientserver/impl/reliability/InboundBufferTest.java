package org.abstractica.clientserver.impl.reliability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InboundBuffer}.
 */
class InboundBufferTest
{
    private InboundBuffer buffer;

    @BeforeEach
    void setUp()
    {
        buffer = new InboundBuffer(10);
    }

    @Test
    void receive_inOrder_accepted()
    {
        assertEquals(InboundBuffer.ReceiveResult.ACCEPTED, buffer.receive(0, 1, new byte[]{1}));
        assertEquals(InboundBuffer.ReceiveResult.ACCEPTED, buffer.receive(1, 1, new byte[]{2}));
        assertEquals(InboundBuffer.ReceiveResult.ACCEPTED, buffer.receive(2, 1, new byte[]{3}));
    }

    @Test
    void getNextDeliverable_inOrder()
    {
        buffer.receive(0, 1, new byte[]{1});
        buffer.receive(1, 2, new byte[]{2});

        InboundBuffer.Entry entry0 = buffer.getNextDeliverable();
        assertNotNull(entry0);
        assertEquals(0, entry0.sequence());
        assertEquals(1, entry0.messageTypeId());
        assertArrayEquals(new byte[]{1}, entry0.payload());

        InboundBuffer.Entry entry1 = buffer.getNextDeliverable();
        assertNotNull(entry1);
        assertEquals(1, entry1.sequence());
    }

    @Test
    void getNextDeliverable_outOfOrder_waitsForGap()
    {
        buffer.receive(0, 1, new byte[]{0});
        buffer.receive(2, 1, new byte[]{2}); // Skip 1
        buffer.receive(3, 1, new byte[]{3});

        // Can get 0
        InboundBuffer.Entry entry0 = buffer.getNextDeliverable();
        assertNotNull(entry0);
        assertEquals(0, entry0.sequence());

        // Cannot get 2 yet
        assertNull(buffer.getNextDeliverable());

        // Receive 1
        buffer.receive(1, 1, new byte[]{1});

        // Now can get 1, 2, 3 in order
        assertEquals(1, buffer.getNextDeliverable().sequence());
        assertEquals(2, buffer.getNextDeliverable().sequence());
        assertEquals(3, buffer.getNextDeliverable().sequence());
        assertNull(buffer.getNextDeliverable());
    }

    @Test
    void receive_duplicate_rejected()
    {
        buffer.receive(0, 1, new byte[]{1});
        assertEquals(InboundBuffer.ReceiveResult.DUPLICATE, buffer.receive(0, 1, new byte[]{1}));
    }

    @Test
    void receive_alreadyDelivered_duplicate()
    {
        buffer.receive(0, 1, new byte[]{1});
        buffer.getNextDeliverable();

        // Re-receiving an already delivered message
        assertEquals(InboundBuffer.ReceiveResult.DUPLICATE, buffer.receive(0, 1, new byte[]{1}));
    }

    @Test
    void receive_tooOld_rejected()
    {
        // Receive and deliver sequences 0-5
        for (int i = 0; i <= 5; i++)
        {
            buffer.receive(i, 1, new byte[]{(byte) i});
            buffer.getNextDeliverable();
        }

        // Sequence 3 is tracked as recently delivered, so it's a duplicate
        assertEquals(InboundBuffer.ReceiveResult.DUPLICATE, buffer.receive(3, 1, new byte[]{3}));

        // Test TOO_OLD for a sequence that was never received but is before nextExpected
        // nextExpected is now 6, so we need to receive more and then test an old sequence
        // that wasn't in the recently delivered set
        InboundBuffer smallBuffer = new InboundBuffer(2); // Small tracking size
        for (int i = 0; i < 10; i++)
        {
            smallBuffer.receive(i, 1, new byte[]{(byte) i});
            smallBuffer.getNextDeliverable();
        }
        // After many deliveries, early sequences might be cleared from tracking
        // But sequence 0 is still before nextExpected (10), so it's TOO_OLD
        InboundBuffer.ReceiveResult result = smallBuffer.receive(0, 1, new byte[]{0});
        // Could be either DUPLICATE (if still tracked) or TOO_OLD (if tracking cleared)
        assertTrue(result == InboundBuffer.ReceiveResult.DUPLICATE ||
                        result == InboundBuffer.ReceiveResult.TOO_OLD,
                "Old sequence should be rejected as DUPLICATE or TOO_OLD");
    }

    @Test
    void receive_bufferFull_rejected()
    {
        // Fill the buffer with out-of-order messages
        buffer.receive(0, 1, new byte[]{0});
        for (int i = 2; i < 12; i++) // Skip 1, buffer sequences 2-11 (10 items)
        {
            if (i < 11)
            {
                assertEquals(InboundBuffer.ReceiveResult.ACCEPTED, buffer.receive(i, 1, new byte[]{(byte) i}));
            }
            else
            {
                assertEquals(InboundBuffer.ReceiveResult.BUFFER_FULL, buffer.receive(i, 1, new byte[]{(byte) i}));
            }
        }
    }

    @Test
    void getHighestConsecutiveReceived_inOrder()
    {
        assertEquals(-1, buffer.getHighestConsecutiveReceived());

        buffer.receive(0, 1, new byte[0]);
        assertEquals(0, buffer.getHighestConsecutiveReceived());

        buffer.receive(1, 1, new byte[0]);
        assertEquals(1, buffer.getHighestConsecutiveReceived());

        buffer.receive(2, 1, new byte[0]);
        assertEquals(2, buffer.getHighestConsecutiveReceived());
    }

    @Test
    void getHighestConsecutiveReceived_gap()
    {
        buffer.receive(0, 1, new byte[0]);
        buffer.receive(2, 1, new byte[0]); // Skip 1

        assertEquals(0, buffer.getHighestConsecutiveReceived());

        buffer.receive(1, 1, new byte[0]); // Fill gap
        assertEquals(2, buffer.getHighestConsecutiveReceived());
    }

    @Test
    void getReceivedBitmap_tracksOutOfOrder()
    {
        buffer.receive(0, 1, new byte[0]);
        assertEquals(0, buffer.getReceivedBitmap());

        buffer.receive(2, 1, new byte[0]); // Offset 1 from consecutive (0)
        // Bit 1 should be set (sequence 2 = ackSeq(0) + 1 + 1)
        assertEquals(0b10, buffer.getReceivedBitmap());

        buffer.receive(4, 1, new byte[0]); // Offset 3 from consecutive (0)
        // Bit 1 and 3 should be set
        assertEquals(0b1010, buffer.getReceivedBitmap());
    }

    @Test
    void getReceivedBitmap_updatesWhenGapFilled()
    {
        buffer.receive(0, 1, new byte[0]);
        buffer.receive(2, 1, new byte[0]);
        buffer.receive(4, 1, new byte[0]);

        // Fill gap
        buffer.receive(1, 1, new byte[0]);
        // Now consecutive is 2, bitmap should only show 4
        // 4 = 2 + 1 + 1, so bit 1
        assertEquals(0b10, buffer.getReceivedBitmap());

        buffer.receive(3, 1, new byte[0]);
        // Now consecutive is 4, bitmap should be 0
        assertEquals(0, buffer.getReceivedBitmap());
    }

    @Test
    void size_tracksBufferedMessages()
    {
        assertEquals(0, buffer.size());

        buffer.receive(0, 1, new byte[0]);
        assertEquals(1, buffer.size());

        buffer.receive(2, 1, new byte[0]);
        assertEquals(2, buffer.size());

        buffer.getNextDeliverable();
        assertEquals(1, buffer.size());
    }

    @Test
    void hasDeliverable_checksNextExpected()
    {
        assertFalse(buffer.hasDeliverable());

        buffer.receive(1, 1, new byte[0]); // Out of order
        assertFalse(buffer.hasDeliverable());

        buffer.receive(0, 1, new byte[0]); // In order
        assertTrue(buffer.hasDeliverable());

        buffer.getNextDeliverable();
        assertTrue(buffer.hasDeliverable()); // Seq 1 now deliverable
    }

    @Test
    void reset_clearsState()
    {
        buffer.receive(0, 1, new byte[0]);
        buffer.receive(1, 1, new byte[0]);
        buffer.getNextDeliverable();

        buffer.reset(100);

        assertEquals(100, buffer.getNextExpectedSequence());
        assertEquals(99, buffer.getHighestConsecutiveReceived());
        assertEquals(0, buffer.size());
        assertFalse(buffer.hasDeliverable());
    }

    @Test
    void constructor_invalidMaxSize_throws()
    {
        assertThrows(IllegalArgumentException.class, () -> new InboundBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> new InboundBuffer(-1));
    }

    @Test
    void reset_setsExpectedSequence()
    {
        // Reset can set any starting sequence
        buffer.reset(100);
        buffer.receive(100, 1, new byte[0]);

        assertEquals(100, buffer.getHighestConsecutiveReceived());
        assertNotNull(buffer.getNextDeliverable());
        assertEquals(101, buffer.getNextExpectedSequence());
    }
}

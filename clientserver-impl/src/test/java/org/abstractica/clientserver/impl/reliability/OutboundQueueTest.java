package org.abstractica.clientserver.impl.reliability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link OutboundQueue}.
 */
class OutboundQueueTest
{
    private RttEstimator rttEstimator;
    private OutboundQueue queue;

    @BeforeEach
    void setUp()
    {
        rttEstimator = new RttEstimator(100);
        queue = new OutboundQueue(rttEstimator, 10, 5);
    }

    @Test
    void queue_addsMessage()
    {
        byte[] payload = {1, 2, 3};
        assertTrue(queue.queue(0, 1, payload, 1000));
        assertEquals(1, queue.size());
        assertFalse(queue.isEmpty());
    }

    @Test
    void queue_fullReturnsFlase()
    {
        for (int i = 0; i < 10; i++)
        {
            assertTrue(queue.queue(i, 1, new byte[0], 1000));
        }
        assertTrue(queue.isFull());
        assertFalse(queue.queue(10, 1, new byte[0], 1000));
    }

    @Test
    void acknowledge_removesMessage()
    {
        queue.queue(0, 1, new byte[]{1}, 1000);
        queue.queue(1, 1, new byte[]{2}, 1000);

        OutboundQueue.Entry entry = queue.acknowledge(0);

        assertNotNull(entry);
        assertEquals(0, entry.sequence());
        assertArrayEquals(new byte[]{1}, entry.payload());
        assertEquals(1, queue.size());
    }

    @Test
    void acknowledge_notFound_returnsNull()
    {
        queue.queue(0, 1, new byte[0], 1000);
        assertNull(queue.acknowledge(99));
    }

    @Test
    void acknowledgeCumulative_removesAllUpTo()
    {
        queue.queue(0, 1, new byte[0], 1000);
        queue.queue(1, 1, new byte[0], 1000);
        queue.queue(2, 1, new byte[0], 1000);
        queue.queue(5, 1, new byte[0], 1000);

        int count = queue.acknowledgeCumulative(2);

        assertEquals(3, count);
        assertEquals(1, queue.size()); // Only seq 5 remains
    }

    @Test
    void acknowledgeSelective_handlesBitmap()
    {
        queue.queue(0, 1, new byte[0], 1000);
        queue.queue(1, 1, new byte[0], 1000);
        queue.queue(3, 1, new byte[0], 1000);
        queue.queue(5, 1, new byte[0], 1000);

        // Ack seq 0 cumulatively, plus selective acks for seq 3 and 5
        // seq 3 = baseSeq(0) + 1 + 2, so bit 2
        // seq 5 = baseSeq(0) + 1 + 4, so bit 4
        int bitmap = (1 << 2) | (1 << 4);
        int count = queue.acknowledgeSelective(0, bitmap);

        assertEquals(3, count); // Acked: 0, 3, 5
        assertEquals(1, queue.size()); // Only seq 1 remains
    }

    @Test
    void getMessagesForRetransmit_returnsExpiredMessages()
    {
        rttEstimator.update(100);
        queue.queue(0, 1, new byte[0], 1000);
        queue.queue(1, 1, new byte[0], 1000);

        // RTO for attempt 1 is 150ms, check at 1200 (200ms elapsed)
        OutboundQueue.RetransmitResult result = queue.getMessagesForRetransmit(1200);

        assertEquals(2, result.retransmits().size());
        assertTrue(result.expired().isEmpty());
    }

    @Test
    void getMessagesForRetransmit_notYetExpired()
    {
        rttEstimator.update(100);
        queue.queue(0, 1, new byte[0], 1000);

        // Check at 1050 (50ms elapsed), RTO is 150ms
        OutboundQueue.RetransmitResult result = queue.getMessagesForRetransmit(1050);

        assertTrue(result.retransmits().isEmpty());
        assertTrue(result.expired().isEmpty());
    }

    @Test
    void getMessagesForRetransmit_identifiesMaxAttempts()
    {
        rttEstimator.update(100);
        queue.queue(0, 1, new byte[0], 0);

        // Simulate retransmits until max attempts reached
        // Each iteration: check triggers retransmit, then mark with new time
        // Next check must be after that time + RTO
        long checkTime = 1000;
        for (int i = 0; i < 4; i++)
        {
            OutboundQueue.RetransmitResult result = queue.getMessagesForRetransmit(checkTime);
            assertFalse(result.retransmits().isEmpty(), "Iteration " + i + " should have retransmits");
            queue.markRetransmitted(0, checkTime);
            checkTime += 3000; // Advance well past RTO for next check
        }

        // Next check should show as expired (5 attempts reached)
        OutboundQueue.RetransmitResult result = queue.getMessagesForRetransmit(checkTime);
        assertTrue(result.retransmits().isEmpty());
        assertEquals(1, result.expired().size());
        assertEquals(0, result.expired().get(0).sequence());
    }

    @Test
    void markRetransmitted_updatesEntry()
    {
        queue.queue(0, 1, new byte[0], 1000);

        queue.markRetransmitted(0, 2000);

        // The entry should now have attempt count 2 and sent time 2000
        // We verify indirectly by checking retransmit timing
        rttEstimator.update(100);
        OutboundQueue.RetransmitResult result = queue.getMessagesForRetransmit(2100);
        assertTrue(result.retransmits().isEmpty()); // Not yet expired (RTO at attempt 1 is 300ms)
    }

    @Test
    void remove_removesEntry()
    {
        queue.queue(0, 1, new byte[0], 1000);
        queue.queue(1, 1, new byte[0], 1000);

        OutboundQueue.Entry removed = queue.remove(0);

        assertNotNull(removed);
        assertEquals(0, removed.sequence());
        assertEquals(1, queue.size());
    }

    @Test
    void size_isEmpty_isFull()
    {
        assertTrue(queue.isEmpty());
        assertFalse(queue.isFull());
        assertEquals(0, queue.size());

        for (int i = 0; i < 10; i++)
        {
            queue.queue(i, 1, new byte[0], 1000);
        }

        assertFalse(queue.isEmpty());
        assertTrue(queue.isFull());
        assertEquals(10, queue.size());
    }

    @Test
    void getMaxSize_returnsConfiguredMax()
    {
        assertEquals(10, queue.getMaxSize());
    }

    @Test
    void entry_withRetransmit_incrementsAttempt()
    {
        OutboundQueue.Entry entry = new OutboundQueue.Entry(1, 2, new byte[]{3}, 1000, 1);
        OutboundQueue.Entry retransmitted = entry.withRetransmit(2000);

        assertEquals(1, entry.attemptCount());
        assertEquals(2, retransmitted.attemptCount());
        assertEquals(2000, retransmitted.sentTimeMs());
    }

    @Test
    void constructor_invalidParameters_throws()
    {
        assertThrows(NullPointerException.class,
                () -> new OutboundQueue(null));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboundQueue(rttEstimator, 0, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new OutboundQueue(rttEstimator, 10, 0));
    }
}

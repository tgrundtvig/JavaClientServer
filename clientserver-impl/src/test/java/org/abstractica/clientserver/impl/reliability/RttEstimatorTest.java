package org.abstractica.clientserver.impl.reliability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RttEstimator}.
 */
class RttEstimatorTest
{
    @Test
    void constructor_default_usesDefaultInitialRtt()
    {
        RttEstimator estimator = new RttEstimator();
        assertEquals(RttEstimator.DEFAULT_INITIAL_RTT_MS, estimator.getSmoothedRttMs());
    }

    @Test
    void constructor_customInitialRtt()
    {
        RttEstimator estimator = new RttEstimator(100);
        assertEquals(100, estimator.getSmoothedRttMs());
    }

    @Test
    void constructor_invalidInitialRtt_throws()
    {
        assertThrows(IllegalArgumentException.class, () -> new RttEstimator(0));
        assertThrows(IllegalArgumentException.class, () -> new RttEstimator(-1));
    }

    @Test
    void update_firstSample_setsDirect()
    {
        RttEstimator estimator = new RttEstimator(200);
        estimator.update(100);
        assertEquals(100, estimator.getSmoothedRttMs());
    }

    @Test
    void update_subsequentSamples_appliesSmoothing()
    {
        RttEstimator estimator = new RttEstimator(100);
        estimator.update(100); // Initialize

        // Update with higher sample - should smooth toward it
        estimator.update(200);
        long rtt = estimator.getSmoothedRttMs();
        assertTrue(rtt > 100 && rtt < 200,
                "RTT should be between 100 and 200, was: " + rtt);
    }

    @Test
    void update_zerOrNegative_ignored()
    {
        RttEstimator estimator = new RttEstimator(100);
        estimator.update(100);

        estimator.update(0);
        assertEquals(100, estimator.getSmoothedRttMs());

        estimator.update(-50);
        assertEquals(100, estimator.getSmoothedRttMs());
    }

    @Test
    void calculateRtoMs_attempt0_baseRto()
    {
        RttEstimator estimator = new RttEstimator(100);
        // RTO = RTT * 1.5 = 150, which is above MIN_RTO
        long rto = estimator.calculateRtoMs(0);
        assertEquals(150, rto);
    }

    @Test
    void calculateRtoMs_enforceMinimum()
    {
        RttEstimator estimator = new RttEstimator(10);
        // RTO = 10 * 1.5 = 15, but minimum is 50
        long rto = estimator.calculateRtoMs(0);
        assertEquals(RttEstimator.MIN_RTO_MS, rto);
    }

    @Test
    void calculateRtoMs_exponentialBackoff()
    {
        RttEstimator estimator = new RttEstimator(100);
        // Base RTO = 150

        long rto0 = estimator.calculateRtoMs(0);
        long rto1 = estimator.calculateRtoMs(1);
        long rto2 = estimator.calculateRtoMs(2);
        long rto3 = estimator.calculateRtoMs(3);

        assertEquals(150, rto0);
        assertEquals(300, rto1);  // 150 * 2
        assertEquals(600, rto2);  // 150 * 4
        assertEquals(1200, rto3); // 150 * 8
    }

    @Test
    void calculateRtoMs_cappedAtMax()
    {
        RttEstimator estimator = new RttEstimator(100);
        // High attempt count should cap at MAX_RTO
        long rto = estimator.calculateRtoMs(10);
        assertEquals(RttEstimator.MAX_RTO_MS, rto);
    }

    @Test
    void calculateRtoMs_negativeAttempt_throws()
    {
        RttEstimator estimator = new RttEstimator();
        assertThrows(IllegalArgumentException.class, () -> estimator.calculateRtoMs(-1));
    }

    @Test
    void reset_clearsState()
    {
        RttEstimator estimator = new RttEstimator(100);
        estimator.update(50);
        assertEquals(50, estimator.getSmoothedRttMs());

        estimator.reset(200);
        assertEquals(200, estimator.getSmoothedRttMs());

        // After reset, next update should set directly again
        estimator.update(300);
        assertEquals(300, estimator.getSmoothedRttMs());
    }

    @Test
    void reset_invalidRtt_throws()
    {
        RttEstimator estimator = new RttEstimator();
        assertThrows(IllegalArgumentException.class, () -> estimator.reset(0));
        assertThrows(IllegalArgumentException.class, () -> estimator.reset(-1));
    }

    @Test
    void smoothing_convergesOverTime()
    {
        RttEstimator estimator = new RttEstimator(100);
        estimator.update(100);

        // Repeated updates with same value should converge
        for (int i = 0; i < 50; i++)
        {
            estimator.update(200);
        }

        // Should be very close to 200 after many updates
        // With alpha=0.125, after 50 iterations starting from 100, we should be > 198
        long rtt = estimator.getSmoothedRttMs();
        assertTrue(rtt >= 190 && rtt <= 200,
                "RTT should have converged near 200, was: " + rtt);
    }
}

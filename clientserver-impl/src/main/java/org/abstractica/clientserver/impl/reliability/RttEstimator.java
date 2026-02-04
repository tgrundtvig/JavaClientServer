package org.abstractica.clientserver.impl.reliability;

/**
 * Estimates round-trip time (RTT) and calculates retransmission timeouts.
 *
 * <p>Uses exponential moving average for RTT estimation and provides
 * exponential backoff for repeated retransmissions.</p>
 */
public class RttEstimator
{
    /**
     * Minimum retransmission timeout in milliseconds.
     */
    public static final long MIN_RTO_MS = 50;

    /**
     * Maximum retransmission timeout in milliseconds.
     */
    public static final long MAX_RTO_MS = 2000;

    /**
     * Default initial RTT estimate in milliseconds.
     */
    public static final long DEFAULT_INITIAL_RTT_MS = 200;

    /**
     * Smoothing factor for RTT updates (alpha).
     * New RTT = alpha * sample + (1 - alpha) * old RTT
     */
    private static final double SMOOTHING_FACTOR = 0.125;

    /**
     * RTO multiplier applied to smoothed RTT.
     */
    private static final double RTO_MULTIPLIER = 1.5;

    private long smoothedRttMs;
    private boolean initialized;

    /**
     * Creates an RTT estimator with default initial RTT.
     */
    public RttEstimator()
    {
        this(DEFAULT_INITIAL_RTT_MS);
    }

    /**
     * Creates an RTT estimator with specified initial RTT.
     *
     * @param initialRttMs initial RTT estimate in milliseconds
     */
    public RttEstimator(long initialRttMs)
    {
        if (initialRttMs <= 0)
        {
            throw new IllegalArgumentException("Initial RTT must be positive: " + initialRttMs);
        }
        this.smoothedRttMs = initialRttMs;
        this.initialized = false;
    }

    /**
     * Updates the RTT estimate with a new sample.
     *
     * @param rttSampleMs measured round-trip time in milliseconds
     */
    public void update(long rttSampleMs)
    {
        if (rttSampleMs <= 0)
        {
            return;
        }

        if (!initialized)
        {
            smoothedRttMs = rttSampleMs;
            initialized = true;
        }
        else
        {
            smoothedRttMs = (long) (SMOOTHING_FACTOR * rttSampleMs +
                    (1 - SMOOTHING_FACTOR) * smoothedRttMs);
        }
    }

    /**
     * Returns the current smoothed RTT estimate.
     *
     * @return smoothed RTT in milliseconds
     */
    public long getSmoothedRttMs()
    {
        return smoothedRttMs;
    }

    /**
     * Calculates the retransmission timeout (RTO) for a given attempt number.
     *
     * <p>Base RTO = RTT * 1.5 (minimum 50ms).
     * Each subsequent attempt doubles the timeout (exponential backoff),
     * capped at 2 seconds.</p>
     *
     * @param attempt retransmission attempt number (0 = first transmission)
     * @return retransmission timeout in milliseconds
     */
    public long calculateRtoMs(int attempt)
    {
        if (attempt < 0)
        {
            throw new IllegalArgumentException("Attempt must be non-negative: " + attempt);
        }

        long baseRto = Math.max(MIN_RTO_MS, (long) (smoothedRttMs * RTO_MULTIPLIER));

        if (attempt == 0)
        {
            return Math.min(baseRto, MAX_RTO_MS);
        }

        // Exponential backoff: double for each retry, cap at MAX_RTO_MS
        long backoffRto = baseRto << Math.min(attempt, 31);
        return Math.min(backoffRto, MAX_RTO_MS);
    }

    /**
     * Resets the estimator to initial state.
     *
     * @param initialRttMs new initial RTT estimate
     */
    public void reset(long initialRttMs)
    {
        if (initialRttMs <= 0)
        {
            throw new IllegalArgumentException("Initial RTT must be positive: " + initialRttMs);
        }
        this.smoothedRttMs = initialRttMs;
        this.initialized = false;
    }
}

package org.abstractica.clientserver;

/**
 * Factory for creating Network instances.
 *
 * <p>Provides network implementations for different scenarios:</p>
 * <ul>
 *   <li>{@link #createUdpNetwork()} - real UDP sockets for production</li>
 *   <li>{@link #createSimulatedNetwork()} - simulated network for testing</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * NetworkFactory factory = new DefaultNetworkFactory();
 *
 * // For production
 * Network network = factory.createUdpNetwork();
 *
 * // For testing
 * Network network = factory.createSimulatedNetwork();
 * }</pre>
 */
public interface NetworkFactory
{
    /**
     * Creates a UDP network for real network communication.
     *
     * <p>Use this for production deployments.</p>
     *
     * @return a new UDP network
     */
    Network createUdpNetwork();

    /**
     * Creates a simulated network for testing.
     *
     * <p>The simulated network allows testing without actual network I/O
     * and can simulate various network conditions.</p>
     *
     * @return a new simulated network
     */
    Network createSimulatedNetwork();
}

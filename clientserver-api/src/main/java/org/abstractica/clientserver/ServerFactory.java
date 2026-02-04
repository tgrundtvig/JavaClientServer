package org.abstractica.clientserver;

import java.net.InetAddress;
import java.security.PrivateKey;
import java.time.Duration;

/**
 * Factory for creating Server instances.
 *
 * <p>Use the builder to configure the server before creation:</p>
 * <pre>{@code
 * ServerFactory factory = new DefaultServerFactory();
 * Server server = factory.builder()
 *     .port(7777)
 *     .protocol(protocol)
 *     .privateKey(privateKey)
 *     .sessionTimeout(Duration.ofMinutes(5))
 *     .build();
 * }</pre>
 */
public interface ServerFactory
{
    /**
     * Creates a new server builder.
     *
     * @return a new builder instance
     */
    Builder builder();

    /**
     * Builder for configuring and creating a Server.
     */
    interface Builder
    {
        /**
         * Sets the port to listen on.
         *
         * @param port the port number
         * @return this builder
         */
        Builder port(int port);

        /**
         * Sets the message protocol.
         *
         * @param protocol the protocol definition
         * @return this builder
         */
        Builder protocol(Protocol protocol);

        /**
         * Sets the server's private key for encryption.
         *
         * @param privateKey the server's private key
         * @return this builder
         */
        Builder privateKey(PrivateKey privateKey);

        /**
         * Sets the address to bind to.
         *
         * <p>Optional. Defaults to all interfaces.</p>
         *
         * @param address the bind address
         * @return this builder
         */
        Builder bindAddress(InetAddress address);

        /**
         * Sets the maximum number of concurrent connections.
         *
         * <p>Optional. Defaults to unlimited.</p>
         *
         * @param maxConnections maximum connections
         * @return this builder
         */
        Builder maxConnections(int maxConnections);

        /**
         * Sets how long a session survives without a connection.
         *
         * <p>Optional. Defaults to 2 minutes.</p>
         *
         * @param timeout the session timeout
         * @return this builder
         */
        Builder sessionTimeout(Duration timeout);

        /**
         * Sets the interval between heartbeat packets.
         *
         * <p>Optional. Defaults to 5 seconds.</p>
         *
         * @param interval the heartbeat interval
         * @return this builder
         */
        Builder heartbeatInterval(Duration interval);

        /**
         * Sets the maximum size of the reliable message queue per session.
         *
         * <p>Optional. Has a sensible default.</p>
         *
         * @param size maximum queue size
         * @return this builder
         */
        Builder maxReliableQueueSize(int size);

        /**
         * Sets the maximum message size in bytes.
         *
         * <p>Optional. Has a sensible default.</p>
         *
         * @param size maximum message size
         * @return this builder
         */
        Builder maxMessageSize(int size);

        /**
         * Builds the server.
         *
         * @return the configured server
         * @throws IllegalStateException if required parameters are missing
         */
        Server build();
    }
}

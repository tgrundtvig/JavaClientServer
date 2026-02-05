package org.abstractica.clientserver;

import java.security.PublicKey;

/**
 * Factory for creating Client instances.
 *
 * <p>Use the builder to configure the client before creation:</p>
 * <pre>{@code
 * ClientFactory factory = new DefaultClientFactory();
 * Client client = factory.builder()
 *     .serverAddress("game.example.com", 7777)
 *     .protocol(protocol)
 *     .serverPublicKey(serverPublicKey)
 *     .build();
 * }</pre>
 */
public interface ClientFactory
{
    /**
     * Creates a new client builder.
     *
     * @return a new builder instance
     */
    Builder builder();

    /**
     * Builder for configuring and creating a Client.
     */
    interface Builder
    {
        /**
         * Sets the server address to connect to.
         *
         * @param host the server hostname or IP address
         * @param port the server port
         * @return this builder
         */
        Builder serverAddress(String host, int port);

        /**
         * Sets the message protocol.
         *
         * @param protocol the protocol definition
         * @return this builder
         */
        Builder protocol(Protocol protocol);

        /**
         * Sets the server's public key for verification.
         *
         * <p>The client verifies the server's identity during handshake
         * using this key.</p>
         *
         * @param publicKey the server's public key
         * @return this builder
         */
        Builder serverPublicKey(PublicKey publicKey);

        /**
         * Sets the network to use for creating endpoints.
         *
         * <p>Optional. Defaults to UDP.</p>
         *
         * @param network the network to use
         * @return this builder
         */
        Builder network(Network network);

        /**
         * Builds the client.
         *
         * @return the configured client
         * @throws IllegalStateException if required parameters are missing
         */
        Client build();
    }
}

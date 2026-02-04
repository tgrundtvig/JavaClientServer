package org.abstractica.clientserver;

/**
 * Defines the message protocol between client and server.
 *
 * <p>A protocol consists of two sealed interface hierarchies: one for
 * client-to-server messages and one for server-to-client messages.
 * The library scans these hierarchies at build time to generate
 * serializers and compute a protocol hash for version matching.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Protocol protocol = Protocol.builder()
 *     .clientMessages(ClientMessage.class)
 *     .serverMessages(ServerMessage.class)
 *     .build();
 * }</pre>
 */
public interface Protocol
{
    /**
     * Returns the protocol hash for version matching.
     *
     * <p>The hash is computed from the structure of all message types.
     * Client and server must have matching hashes to communicate.</p>
     *
     * @return protocol hash string
     */
    String getHash();

    /**
     * Creates a new protocol builder.
     *
     * @return a new builder instance
     */
    static Builder builder()
    {
        throw new UnsupportedOperationException("Implementation not available");
    }

    /**
     * Builder for constructing a Protocol.
     */
    interface Builder
    {
        /**
         * Sets the sealed interface for client-to-server messages.
         *
         * @param sealedInterface the sealed interface class
         * @return this builder
         */
        Builder clientMessages(Class<?> sealedInterface);

        /**
         * Sets the sealed interface for server-to-client messages.
         *
         * @param sealedInterface the sealed interface class
         * @return this builder
         */
        Builder serverMessages(Class<?> sealedInterface);

        /**
         * Builds the protocol.
         *
         * <p>Validates that all permitted types are records, all nested
         * types are supported, and no circular references exist.</p>
         *
         * @return the constructed protocol
         * @throws IllegalStateException if required parameters are missing
         * @throws IllegalArgumentException if validation fails
         */
        Protocol build();
    }
}

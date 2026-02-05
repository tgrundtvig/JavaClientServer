/**
 * Client-server library implementation module.
 *
 * <p>Provides the default implementation of the client-server API.</p>
 */
module clientserver.impl
{
    requires clientserver.api;
    requires org.slf4j;

    // Export factory implementations for external use
    exports org.abstractica.clientserver.impl.client;
    exports org.abstractica.clientserver.impl.session;
    exports org.abstractica.clientserver.impl.transport;

    // Export serialization and crypto for protocol building and key generation
    exports org.abstractica.clientserver.impl.serialization;
    exports org.abstractica.clientserver.impl.crypto;
}

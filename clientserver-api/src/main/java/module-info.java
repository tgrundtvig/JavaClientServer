/**
 * Client-server library API module.
 *
 * <p>Provides interfaces for building client-server applications with
 * type-safe messaging over encrypted UDP transport.</p>
 */
module clientserver.api
{
    requires org.slf4j;

    exports org.abstractica.clientserver;
    exports org.abstractica.clientserver.handlers;
}

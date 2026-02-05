/**
 * Demo server module.
 *
 * <p>Demonstrates server-side library usage with a simple multiplayer game.</p>
 */
module demo.server
{
    requires clientserver.api;
    requires clientserver.impl;
    requires demo.protocol;
    requires org.slf4j;
}

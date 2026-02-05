package org.abstractica.clientserver.impl.client;

import org.abstractica.clientserver.Delivery;
import org.abstractica.clientserver.Session;

import java.util.Objects;
import java.util.Optional;

/**
 * Client-side Session implementation.
 *
 * <p>Wraps the DefaultClient to provide the Session interface for handlers.</p>
 */
class ClientSession implements Session
{
    private final DefaultClient client;
    private volatile Object attachment;

    ClientSession(DefaultClient client)
    {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void send(Object message)
    {
        client.send(message);
    }

    @Override
    public void send(Object message, Delivery delivery)
    {
        client.send(message, delivery);
    }

    @Override
    public boolean trySend(Object message)
    {
        return client.trySend(message);
    }

    @Override
    public boolean trySend(Object message, Delivery delivery)
    {
        return client.trySend(message, delivery);
    }

    @Override
    public void close()
    {
        client.disconnect();
    }

    @Override
    public void close(String reason)
    {
        // Client doesn't send reason to server, just disconnects
        client.disconnect();
    }

    @Override
    public String getId()
    {
        // Session ID would be the token, but client might not have it yet
        return "client-session";
    }

    @Override
    public Optional<Object> getAttachment()
    {
        return Optional.ofNullable(attachment);
    }

    @Override
    public void setAttachment(Object attachment)
    {
        this.attachment = attachment;
    }
}

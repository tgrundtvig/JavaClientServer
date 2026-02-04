package org.abstractica.clientserver.impl.session;

import org.abstractica.clientserver.impl.crypto.KeyExchange;
import org.abstractica.clientserver.impl.crypto.PacketEncryptor;

import java.net.SocketAddress;
import java.util.Objects;

/**
 * Tracks state of an in-progress handshake before session establishment.
 *
 * <p>Created after receiving ClientHello and sending ServerHello.
 * Holds the encryption context for the subsequent Connect packet.</p>
 *
 * @param remoteAddress the client's address
 * @param keyExchange   the server's ephemeral key exchange instance
 * @param encryptor     the encryptor derived from the shared secret
 * @param createdAtMs   creation timestamp for timeout checking
 */
public record PendingHandshake(
        SocketAddress remoteAddress,
        KeyExchange keyExchange,
        PacketEncryptor encryptor,
        long createdAtMs
)
{
    public PendingHandshake
    {
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(keyExchange, "keyExchange");
        Objects.requireNonNull(encryptor, "encryptor");
    }
}

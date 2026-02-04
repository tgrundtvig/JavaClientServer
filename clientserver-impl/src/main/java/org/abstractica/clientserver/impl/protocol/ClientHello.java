package org.abstractica.clientserver.impl.protocol;

import java.util.Objects;

/**
 * Client's initial handshake packet containing ephemeral ECDH public key.
 *
 * <p>Wire format:</p>
 * <pre>
 * [type: 1 byte = 0x01]
 * [version: 1 byte]
 * [clientPublicKey: 32 bytes]
 * </pre>
 *
 * @param version         protocol version (currently 1)
 * @param clientPublicKey client's ephemeral X25519 public key (32 bytes)
 */
public record ClientHello(
        int version,
        byte[] clientPublicKey
)
{
    public static final int PUBLIC_KEY_LENGTH = 32;

    public ClientHello
    {
        if (version < 0 || version > 255)
        {
            throw new IllegalArgumentException("Version must be 0-255: " + version);
        }
        Objects.requireNonNull(clientPublicKey, "clientPublicKey");
        if (clientPublicKey.length != PUBLIC_KEY_LENGTH)
        {
            throw new IllegalArgumentException(
                    "Public key must be " + PUBLIC_KEY_LENGTH + " bytes: " + clientPublicKey.length);
        }
    }
}

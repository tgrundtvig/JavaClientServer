package org.abstractica.clientserver.impl.protocol;

import java.util.Objects;

/**
 * Server's handshake response containing ephemeral ECDH public key and signature.
 *
 * <p>Wire format:</p>
 * <pre>
 * [type: 1 byte = 0x02]
 * [version: 1 byte]
 * [serverPublicKey: 32 bytes]
 * [signature: 64 bytes]
 * </pre>
 *
 * @param version         protocol version
 * @param serverPublicKey server's ephemeral X25519 public key (32 bytes)
 * @param signature       Ed25519 signature over serverPublicKey (64 bytes)
 */
public record ServerHello(
        int version,
        byte[] serverPublicKey,
        byte[] signature
)
{
    public static final int PUBLIC_KEY_LENGTH = 32;
    public static final int SIGNATURE_LENGTH = 64;

    public ServerHello
    {
        if (version < 0 || version > 255)
        {
            throw new IllegalArgumentException("Version must be 0-255: " + version);
        }
        Objects.requireNonNull(serverPublicKey, "serverPublicKey");
        if (serverPublicKey.length != PUBLIC_KEY_LENGTH)
        {
            throw new IllegalArgumentException(
                    "Public key must be " + PUBLIC_KEY_LENGTH + " bytes: " + serverPublicKey.length);
        }
        Objects.requireNonNull(signature, "signature");
        if (signature.length != SIGNATURE_LENGTH)
        {
            throw new IllegalArgumentException(
                    "Signature must be " + SIGNATURE_LENGTH + " bytes: " + signature.length);
        }
    }
}

package org.abstractica.clientserver.impl.crypto;

import javax.crypto.KeyAgreement;
import java.security.*;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Objects;

/**
 * X25519 Elliptic Curve Diffie-Hellman key exchange.
 *
 * <p>Generates ephemeral key pairs and computes shared secrets for
 * establishing encrypted communication channels.</p>
 */
public final class KeyExchange
{
    private static final String ALGORITHM = "X25519";
    private static final int PUBLIC_KEY_LENGTH = 32;

    // X25519 public keys have this prefix in X.509 encoding
    private static final byte[] X25519_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00
    };

    private final KeyPair keyPair;

    /**
     * Creates a new key exchange with a freshly generated ephemeral key pair.
     */
    public KeyExchange()
    {
        try
        {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            this.keyPair = generator.generateKeyPair();
        }
        catch (GeneralSecurityException e)
        {
            throw new RuntimeException("Failed to generate X25519 key pair", e);
        }
    }

    /**
     * Returns the raw public key bytes (32 bytes).
     *
     * @return public key bytes
     */
    public byte[] getPublicKey()
    {
        byte[] encoded = keyPair.getPublic().getEncoded();
        // Extract raw key from X.509 encoding (skip prefix)
        return Arrays.copyOfRange(encoded, X25519_PREFIX.length, encoded.length);
    }

    /**
     * Computes the shared secret using the peer's public key.
     *
     * @param peerPublicKey the peer's raw public key (32 bytes)
     * @return the shared secret (32 bytes)
     */
    public byte[] computeSharedSecret(byte[] peerPublicKey)
    {
        Objects.requireNonNull(peerPublicKey, "peerPublicKey");
        if (peerPublicKey.length != PUBLIC_KEY_LENGTH)
        {
            throw new IllegalArgumentException(
                    "Public key must be " + PUBLIC_KEY_LENGTH + " bytes: " + peerPublicKey.length);
        }

        try
        {
            // Reconstruct X.509 encoded key
            byte[] encoded = new byte[X25519_PREFIX.length + PUBLIC_KEY_LENGTH];
            System.arraycopy(X25519_PREFIX, 0, encoded, 0, X25519_PREFIX.length);
            System.arraycopy(peerPublicKey, 0, encoded, X25519_PREFIX.length, PUBLIC_KEY_LENGTH);

            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            PublicKey publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(encoded));

            KeyAgreement keyAgreement = KeyAgreement.getInstance(ALGORITHM);
            keyAgreement.init(keyPair.getPrivate());
            keyAgreement.doPhase(publicKey, true);

            return keyAgreement.generateSecret();
        }
        catch (GeneralSecurityException e)
        {
            throw new RuntimeException("Key agreement failed", e);
        }
    }

    /**
     * Derives encryption key and nonce base from the shared secret.
     *
     * @param sharedSecret the shared secret from key agreement
     * @return derived keys (encryptionKey: 32 bytes, nonceBase: 12 bytes)
     */
    public static DerivedKeys deriveKeys(byte[] sharedSecret)
    {
        Objects.requireNonNull(sharedSecret, "sharedSecret");

        byte[] salt = "clientserver-v1".getBytes();
        byte[] encryptionKey = Hkdf.derive(sharedSecret, salt, "encryption".getBytes(), 32);
        byte[] nonceBase = Hkdf.derive(sharedSecret, salt, "nonce".getBytes(), 12);

        return new DerivedKeys(encryptionKey, nonceBase);
    }

    /**
     * Holds the derived encryption key and nonce base.
     *
     * @param encryptionKey the 32-byte encryption key for ChaCha20-Poly1305
     * @param nonceBase     the 12-byte nonce base XORed with packet counter
     */
    public record DerivedKeys(byte[] encryptionKey, byte[] nonceBase) {}
}

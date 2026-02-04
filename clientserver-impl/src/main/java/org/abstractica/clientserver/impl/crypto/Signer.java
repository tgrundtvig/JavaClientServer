package org.abstractica.clientserver.impl.crypto;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Objects;

/**
 * Ed25519 digital signatures for server authentication.
 *
 * <p>The server signs its ephemeral X25519 public key during handshake.
 * The client verifies the signature using the server's long-term public key.</p>
 */
public final class Signer
{
    private static final String ALGORITHM = "Ed25519";
    private static final int PUBLIC_KEY_LENGTH = 32;
    private static final int PRIVATE_KEY_LENGTH = 32;
    private static final int SIGNATURE_LENGTH = 64;

    // Ed25519 public keys have this prefix in X.509 encoding
    private static final byte[] ED25519_PUBLIC_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    // Ed25519 private keys have this prefix in PKCS#8 encoding
    private static final byte[] ED25519_PRIVATE_PREFIX = {
            0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20
    };

    private Signer() {}

    /**
     * Generates a new Ed25519 key pair.
     *
     * @return key pair with raw public key (32 bytes) and raw private key (32 bytes)
     */
    public static SigningKeyPair generateKeyPair()
    {
        try
        {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            KeyPair keyPair = generator.generateKeyPair();

            byte[] publicEncoded = keyPair.getPublic().getEncoded();
            byte[] privateEncoded = keyPair.getPrivate().getEncoded();

            byte[] publicKey = Arrays.copyOfRange(publicEncoded, ED25519_PUBLIC_PREFIX.length, publicEncoded.length);
            byte[] privateKey = Arrays.copyOfRange(privateEncoded, ED25519_PRIVATE_PREFIX.length, privateEncoded.length);

            return new SigningKeyPair(publicKey, privateKey);
        }
        catch (GeneralSecurityException e)
        {
            throw new RuntimeException("Failed to generate Ed25519 key pair", e);
        }
    }

    /**
     * Signs data using a raw Ed25519 private key.
     *
     * @param data       the data to sign
     * @param privateKey the raw private key (32 bytes)
     * @return signature (64 bytes)
     */
    public static byte[] sign(byte[] data, byte[] privateKey)
    {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(privateKey, "privateKey");
        if (privateKey.length != PRIVATE_KEY_LENGTH)
        {
            throw new IllegalArgumentException(
                    "Private key must be " + PRIVATE_KEY_LENGTH + " bytes: " + privateKey.length);
        }

        try
        {
            // Reconstruct PKCS#8 encoded key
            byte[] encoded = new byte[ED25519_PRIVATE_PREFIX.length + PRIVATE_KEY_LENGTH];
            System.arraycopy(ED25519_PRIVATE_PREFIX, 0, encoded, 0, ED25519_PRIVATE_PREFIX.length);
            System.arraycopy(privateKey, 0, encoded, ED25519_PRIVATE_PREFIX.length, PRIVATE_KEY_LENGTH);

            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            PrivateKey key = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));

            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(key);
            signature.update(data);

            return signature.sign();
        }
        catch (GeneralSecurityException e)
        {
            throw new RuntimeException("Signing failed", e);
        }
    }

    /**
     * Verifies a signature using a raw Ed25519 public key.
     *
     * @param data      the signed data
     * @param signature the signature to verify (64 bytes)
     * @param publicKey the raw public key (32 bytes)
     * @return true if signature is valid
     */
    public static boolean verify(byte[] data, byte[] signature, byte[] publicKey)
    {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(publicKey, "publicKey");

        if (signature.length != SIGNATURE_LENGTH)
        {
            return false;
        }
        if (publicKey.length != PUBLIC_KEY_LENGTH)
        {
            return false;
        }

        try
        {
            // Reconstruct X.509 encoded key
            byte[] encoded = new byte[ED25519_PUBLIC_PREFIX.length + PUBLIC_KEY_LENGTH];
            System.arraycopy(ED25519_PUBLIC_PREFIX, 0, encoded, 0, ED25519_PUBLIC_PREFIX.length);
            System.arraycopy(publicKey, 0, encoded, ED25519_PUBLIC_PREFIX.length, PUBLIC_KEY_LENGTH);

            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);
            PublicKey key = keyFactory.generatePublic(new X509EncodedKeySpec(encoded));

            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(key);
            sig.update(data);

            return sig.verify(signature);
        }
        catch (GeneralSecurityException e)
        {
            return false;
        }
    }

    /**
     * An Ed25519 key pair with raw key bytes.
     *
     * @param publicKey  the raw public key (32 bytes)
     * @param privateKey the raw private key (32 bytes)
     */
    public record SigningKeyPair(byte[] publicKey, byte[] privateKey) {}
}

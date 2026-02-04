package org.abstractica.clientserver.impl.crypto;

import java.security.*;
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
     * @return key pair with standard Java PublicKey and PrivateKey
     */
    public static SigningKeyPair generateKeyPair()
    {
        try
        {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            KeyPair keyPair = generator.generateKeyPair();
            return new SigningKeyPair(keyPair.getPublic(), keyPair.getPrivate());
        }
        catch (GeneralSecurityException e)
        {
            throw new RuntimeException("Failed to generate Ed25519 key pair", e);
        }
    }

    /**
     * Signs data using an Ed25519 private key.
     *
     * @param data       the data to sign
     * @param privateKey the Ed25519 private key
     * @return signature (64 bytes)
     */
    public static byte[] sign(byte[] data, PrivateKey privateKey)
    {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(privateKey, "privateKey");

        try
        {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        }
        catch (GeneralSecurityException e)
        {
            throw new RuntimeException("Signing failed", e);
        }
    }

    /**
     * Verifies a signature using an Ed25519 public key.
     *
     * @param data      the signed data
     * @param signature the signature to verify (64 bytes)
     * @param publicKey the Ed25519 public key
     * @return true if signature is valid
     */
    public static boolean verify(byte[] data, byte[] signature, PublicKey publicKey)
    {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(publicKey, "publicKey");

        if (signature.length != SIGNATURE_LENGTH)
        {
            return false;
        }

        try
        {
            Signature sig = Signature.getInstance(ALGORITHM);
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        }
        catch (GeneralSecurityException e)
        {
            return false;
        }
    }

    /**
     * Extracts raw public key bytes from a PublicKey.
     *
     * <p>Used for wire protocol encoding where raw 32-byte keys are required.</p>
     *
     * @param publicKey the Ed25519 public key
     * @return raw public key (32 bytes)
     */
    public static byte[] getRawPublicKey(PublicKey publicKey)
    {
        Objects.requireNonNull(publicKey, "publicKey");
        byte[] encoded = publicKey.getEncoded();
        return Arrays.copyOfRange(encoded, ED25519_PUBLIC_PREFIX.length, encoded.length);
    }

    /**
     * Extracts raw private key bytes from a PrivateKey.
     *
     * <p>Used for wire protocol encoding where raw 32-byte keys are required.</p>
     *
     * @param privateKey the Ed25519 private key
     * @return raw private key (32 bytes)
     */
    public static byte[] getRawPrivateKey(PrivateKey privateKey)
    {
        Objects.requireNonNull(privateKey, "privateKey");
        byte[] encoded = privateKey.getEncoded();
        return Arrays.copyOfRange(encoded, ED25519_PRIVATE_PREFIX.length, encoded.length);
    }

    /**
     * An Ed25519 key pair with standard Java key types.
     *
     * @param publicKey  the Ed25519 public key
     * @param privateKey the Ed25519 private key
     */
    public record SigningKeyPair(PublicKey publicKey, PrivateKey privateKey) {}
}

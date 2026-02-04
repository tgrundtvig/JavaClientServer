package org.abstractica.clientserver.impl.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * HKDF (HMAC-based Key Derivation Function) as defined in RFC 5869.
 *
 * <p>Uses HMAC-SHA256 as the underlying hash function.</p>
 */
public final class Hkdf
{
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int HASH_LENGTH = 32; // SHA-256 output length

    private Hkdf() {}

    /**
     * Derives a key using HKDF-SHA256.
     *
     * @param inputKeyMaterial the input key material (shared secret)
     * @param salt             optional salt (can be null or empty)
     * @param info             context/application-specific info
     * @param outputLength     desired output length in bytes
     * @return derived key material
     */
    public static byte[] derive(byte[] inputKeyMaterial, byte[] salt, byte[] info, int outputLength)
    {
        Objects.requireNonNull(inputKeyMaterial, "inputKeyMaterial");
        Objects.requireNonNull(info, "info");

        if (outputLength <= 0)
        {
            throw new IllegalArgumentException("Output length must be positive");
        }
        if (outputLength > 255 * HASH_LENGTH)
        {
            throw new IllegalArgumentException("Output length too large");
        }

        try
        {
            // Extract phase
            byte[] prk = extract(salt, inputKeyMaterial);

            // Expand phase
            return expand(prk, info, outputLength);
        }
        catch (GeneralSecurityException e)
        {
            throw new RuntimeException("HKDF failed", e);
        }
    }

    /**
     * HKDF-Extract: extracts a pseudorandom key from the input key material.
     */
    private static byte[] extract(byte[] salt, byte[] inputKeyMaterial) throws GeneralSecurityException
    {
        if (salt == null || salt.length == 0)
        {
            salt = new byte[HASH_LENGTH];
        }

        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(salt, HMAC_ALGORITHM));
        return mac.doFinal(inputKeyMaterial);
    }

    /**
     * HKDF-Expand: expands the pseudorandom key to the desired length.
     */
    private static byte[] expand(byte[] prk, byte[] info, int outputLength) throws GeneralSecurityException
    {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        mac.init(new SecretKeySpec(prk, HMAC_ALGORITHM));

        byte[] output = new byte[outputLength];
        byte[] block = new byte[0];
        int offset = 0;
        int blockNum = 1;

        while (offset < outputLength)
        {
            mac.update(block);
            mac.update(info);
            mac.update((byte) blockNum);
            block = mac.doFinal();

            int toCopy = Math.min(block.length, outputLength - offset);
            System.arraycopy(block, 0, output, offset, toCopy);
            offset += toCopy;
            blockNum++;
        }

        return output;
    }
}

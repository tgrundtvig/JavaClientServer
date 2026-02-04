package org.abstractica.clientserver.impl.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ChaCha20-Poly1305 AEAD encryption for packet data.
 *
 * <p>Provides authenticated encryption with associated data. Each packet
 * uses a unique nonce derived from a counter XORed with a base nonce.</p>
 *
 * <p>Wire format for encrypted packets:</p>
 * <pre>
 * [nonce: 12 bytes][ciphertext + auth tag: variable + 16 bytes]
 * </pre>
 */
public final class PacketEncryptor
{
    private static final String ALGORITHM = "ChaCha20-Poly1305";
    private static final int NONCE_LENGTH = 12;
    private static final int AUTH_TAG_LENGTH = 16;

    private final SecretKeySpec key;
    private final byte[] nonceBase;
    private final AtomicLong sendCounter = new AtomicLong(0);
    private final AtomicLong receiveCounter = new AtomicLong(0);

    /**
     * Creates a packet encryptor with the given key and nonce base.
     *
     * @param encryptionKey the 32-byte encryption key
     * @param nonceBase     the 12-byte nonce base
     */
    public PacketEncryptor(byte[] encryptionKey, byte[] nonceBase)
    {
        Objects.requireNonNull(encryptionKey, "encryptionKey");
        Objects.requireNonNull(nonceBase, "nonceBase");

        if (encryptionKey.length != 32)
        {
            throw new IllegalArgumentException("Encryption key must be 32 bytes");
        }
        if (nonceBase.length != NONCE_LENGTH)
        {
            throw new IllegalArgumentException("Nonce base must be " + NONCE_LENGTH + " bytes");
        }

        this.key = new SecretKeySpec(encryptionKey, "ChaCha20");
        this.nonceBase = nonceBase.clone();
    }

    /**
     * Creates a packet encryptor from derived keys.
     *
     * @param derivedKeys the keys derived from key exchange
     */
    public PacketEncryptor(KeyExchange.DerivedKeys derivedKeys)
    {
        this(derivedKeys.encryptionKey(), derivedKeys.nonceBase());
    }

    /**
     * Encrypts plaintext and returns the full encrypted packet.
     *
     * <p>The returned buffer contains: [nonce: 12][ciphertext + tag: len + 16]</p>
     *
     * @param plaintext the data to encrypt
     * @return encrypted packet ready for transmission
     */
    public ByteBuffer encrypt(ByteBuffer plaintext)
    {
        long counter = sendCounter.getAndIncrement();
        byte[] nonce = computeNonce(counter);

        try
        {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));

            byte[] plaintextBytes = new byte[plaintext.remaining()];
            plaintext.get(plaintextBytes);

            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            // Output: nonce + ciphertext (which includes auth tag)
            ByteBuffer output = ByteBuffer.allocate(NONCE_LENGTH + ciphertext.length);
            output.put(nonce);
            output.put(ciphertext);
            output.flip();

            return output;
        }
        catch (GeneralSecurityException e)
        {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Encrypts plaintext bytes and returns the full encrypted packet.
     *
     * @param plaintext the data to encrypt
     * @return encrypted packet ready for transmission
     */
    public ByteBuffer encrypt(byte[] plaintext)
    {
        return encrypt(ByteBuffer.wrap(plaintext));
    }

    /**
     * Decrypts an encrypted packet.
     *
     * @param encryptedPacket the encrypted packet [nonce: 12][ciphertext + tag]
     * @return decrypted plaintext
     * @throws SecurityException if decryption or authentication fails
     */
    public ByteBuffer decrypt(ByteBuffer encryptedPacket)
    {
        if (encryptedPacket.remaining() < NONCE_LENGTH + AUTH_TAG_LENGTH)
        {
            throw new SecurityException("Packet too short");
        }

        byte[] nonce = new byte[NONCE_LENGTH];
        encryptedPacket.get(nonce);

        byte[] ciphertext = new byte[encryptedPacket.remaining()];
        encryptedPacket.get(ciphertext);

        // Verify nonce matches expected counter (prevent replay)
        long counter = extractCounter(nonce);
        long expected = receiveCounter.get();

        // Allow some tolerance for out-of-order packets
        if (counter < expected - 1000 || counter > expected + 10000)
        {
            throw new SecurityException("Invalid nonce counter: " + counter + ", expected near " + expected);
        }

        try
        {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce));

            byte[] plaintext = cipher.doFinal(ciphertext);

            // Update receive counter to max seen
            receiveCounter.updateAndGet(current -> Math.max(current, counter + 1));

            return ByteBuffer.wrap(plaintext);
        }
        catch (GeneralSecurityException e)
        {
            throw new SecurityException("Decryption failed: " + e.getMessage());
        }
    }

    /**
     * Decrypts an encrypted packet.
     *
     * @param encryptedPacket the encrypted packet bytes
     * @return decrypted plaintext
     * @throws SecurityException if decryption or authentication fails
     */
    public ByteBuffer decrypt(byte[] encryptedPacket)
    {
        return decrypt(ByteBuffer.wrap(encryptedPacket));
    }

    /**
     * Returns the current send counter value.
     *
     * @return send counter
     */
    public long getSendCounter()
    {
        return sendCounter.get();
    }

    /**
     * Returns the current receive counter value.
     *
     * @return receive counter
     */
    public long getReceiveCounter()
    {
        return receiveCounter.get();
    }

    /**
     * Sets the send counter (for reconnection sync).
     *
     * @param counter the counter value
     */
    public void setSendCounter(long counter)
    {
        sendCounter.set(counter);
    }

    /**
     * Sets the receive counter (for reconnection sync).
     *
     * @param counter the counter value
     */
    public void setReceiveCounter(long counter)
    {
        receiveCounter.set(counter);
    }

    /**
     * Computes nonce by XORing the counter with the nonce base.
     */
    private byte[] computeNonce(long counter)
    {
        byte[] nonce = nonceBase.clone();

        // XOR counter into nonce (little-endian in first 8 bytes)
        for (int i = 0; i < 8; i++)
        {
            nonce[i] ^= (byte) (counter >>> (i * 8));
        }

        return nonce;
    }

    /**
     * Extracts the counter from a nonce by XORing with nonce base.
     */
    private long extractCounter(byte[] nonce)
    {
        long counter = 0;
        for (int i = 0; i < 8; i++)
        {
            counter |= ((long) (nonce[i] ^ nonceBase[i]) & 0xFF) << (i * 8);
        }
        return counter;
    }

    /**
     * Returns the overhead added by encryption (nonce + auth tag).
     *
     * @return encryption overhead in bytes
     */
    public static int getOverhead()
    {
        return NONCE_LENGTH + AUTH_TAG_LENGTH;
    }
}

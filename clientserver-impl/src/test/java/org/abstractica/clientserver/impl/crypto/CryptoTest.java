package org.abstractica.clientserver.impl.crypto;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the crypto layer components.
 */
class CryptoTest
{
    // ========== HKDF ==========

    @Test
    void hkdf_derivesConsistentKey()
    {
        byte[] secret = "shared-secret".getBytes();
        byte[] salt = "salt".getBytes();
        byte[] info = "info".getBytes();

        byte[] key1 = Hkdf.derive(secret, salt, info, 32);
        byte[] key2 = Hkdf.derive(secret, salt, info, 32);

        assertArrayEquals(key1, key2);
    }

    @Test
    void hkdf_differentInfoProducesDifferentKeys()
    {
        byte[] secret = "shared-secret".getBytes();
        byte[] salt = "salt".getBytes();

        byte[] key1 = Hkdf.derive(secret, salt, "info1".getBytes(), 32);
        byte[] key2 = Hkdf.derive(secret, salt, "info2".getBytes(), 32);

        assertFalse(Arrays.equals(key1, key2));
    }

    @Test
    void hkdf_nullSaltAllowed()
    {
        byte[] secret = "shared-secret".getBytes();
        byte[] info = "info".getBytes();

        byte[] key = Hkdf.derive(secret, null, info, 32);
        assertEquals(32, key.length);
    }

    @Test
    void hkdf_variableOutputLength()
    {
        byte[] secret = "shared-secret".getBytes();
        byte[] info = "info".getBytes();

        assertEquals(16, Hkdf.derive(secret, null, info, 16).length);
        assertEquals(32, Hkdf.derive(secret, null, info, 32).length);
        assertEquals(64, Hkdf.derive(secret, null, info, 64).length);
    }

    // ========== KeyExchange ==========

    @Test
    void keyExchange_generatesDifferentKeyPairs()
    {
        KeyExchange kx1 = new KeyExchange();
        KeyExchange kx2 = new KeyExchange();

        assertFalse(Arrays.equals(kx1.getPublicKey(), kx2.getPublicKey()));
    }

    @Test
    void keyExchange_publicKeyIs32Bytes()
    {
        KeyExchange kx = new KeyExchange();
        assertEquals(32, kx.getPublicKey().length);
    }

    @Test
    void keyExchange_bothSidesComputeSameSecret()
    {
        KeyExchange client = new KeyExchange();
        KeyExchange server = new KeyExchange();

        byte[] clientSecret = client.computeSharedSecret(server.getPublicKey());
        byte[] serverSecret = server.computeSharedSecret(client.getPublicKey());

        assertArrayEquals(clientSecret, serverSecret);
    }

    @Test
    void keyExchange_sharedSecretIs32Bytes()
    {
        KeyExchange client = new KeyExchange();
        KeyExchange server = new KeyExchange();

        byte[] secret = client.computeSharedSecret(server.getPublicKey());
        assertEquals(32, secret.length);
    }

    @Test
    void keyExchange_deriveKeys()
    {
        KeyExchange client = new KeyExchange();
        KeyExchange server = new KeyExchange();

        byte[] sharedSecret = client.computeSharedSecret(server.getPublicKey());
        KeyExchange.DerivedKeys keys = KeyExchange.deriveKeys(sharedSecret);

        assertEquals(32, keys.encryptionKey().length);
        assertEquals(12, keys.nonceBase().length);
    }

    @Test
    void keyExchange_invalidPublicKeyLength_throws()
    {
        KeyExchange kx = new KeyExchange();

        assertThrows(IllegalArgumentException.class, () ->
                kx.computeSharedSecret(new byte[16]));
    }

    // ========== Signer ==========

    @Test
    void signer_generatesValidKeyPair()
    {
        Signer.SigningKeyPair keyPair = Signer.generateKeyPair();

        assertNotNull(keyPair.publicKey());
        assertNotNull(keyPair.privateKey());
        // Java reports Ed25519 keys as "EdDSA" algorithm
        assertEquals("EdDSA", keyPair.publicKey().getAlgorithm());
        assertEquals("EdDSA", keyPair.privateKey().getAlgorithm());
    }

    @Test
    void signer_getRawPublicKeyReturns32Bytes()
    {
        Signer.SigningKeyPair keyPair = Signer.generateKeyPair();
        byte[] rawPublic = Signer.getRawPublicKey(keyPair.publicKey());
        assertEquals(32, rawPublic.length);
    }

    @Test
    void signer_getRawPrivateKeyReturns32Bytes()
    {
        Signer.SigningKeyPair keyPair = Signer.generateKeyPair();
        byte[] rawPrivate = Signer.getRawPrivateKey(keyPair.privateKey());
        assertEquals(32, rawPrivate.length);
    }

    @Test
    void signer_signatureVerifies()
    {
        Signer.SigningKeyPair keyPair = Signer.generateKeyPair();
        byte[] data = "Hello, World!".getBytes();

        byte[] signature = Signer.sign(data, keyPair.privateKey());

        assertTrue(Signer.verify(data, signature, keyPair.publicKey()));
    }

    @Test
    void signer_signatureIs64Bytes()
    {
        Signer.SigningKeyPair keyPair = Signer.generateKeyPair();
        byte[] data = "test".getBytes();

        byte[] signature = Signer.sign(data, keyPair.privateKey());
        assertEquals(64, signature.length);
    }

    @Test
    void signer_wrongDataFailsVerification()
    {
        Signer.SigningKeyPair keyPair = Signer.generateKeyPair();
        byte[] data = "original".getBytes();
        byte[] tampered = "tampered".getBytes();

        byte[] signature = Signer.sign(data, keyPair.privateKey());

        assertFalse(Signer.verify(tampered, signature, keyPair.publicKey()));
    }

    @Test
    void signer_wrongKeyFailsVerification()
    {
        Signer.SigningKeyPair keyPair1 = Signer.generateKeyPair();
        Signer.SigningKeyPair keyPair2 = Signer.generateKeyPair();
        byte[] data = "test".getBytes();

        byte[] signature = Signer.sign(data, keyPair1.privateKey());

        assertFalse(Signer.verify(data, signature, keyPair2.publicKey()));
    }

    @Test
    void signer_tamperedSignatureFailsVerification()
    {
        Signer.SigningKeyPair keyPair = Signer.generateKeyPair();
        byte[] data = "test".getBytes();

        byte[] signature = Signer.sign(data, keyPair.privateKey());
        signature[0] ^= 0xFF; // Tamper with signature

        assertFalse(Signer.verify(data, signature, keyPair.publicKey()));
    }

    // ========== PacketEncryptor ==========

    @Test
    void packetEncryptor_encryptDecrypt_roundTrip()
    {
        KeyExchange client = new KeyExchange();
        KeyExchange server = new KeyExchange();

        byte[] sharedSecret = client.computeSharedSecret(server.getPublicKey());
        KeyExchange.DerivedKeys keys = KeyExchange.deriveKeys(sharedSecret);

        PacketEncryptor encryptor = new PacketEncryptor(keys);

        byte[] plaintext = "Hello, encrypted world!".getBytes();
        ByteBuffer encrypted = encryptor.encrypt(plaintext);

        // Create new encryptor for decryption (simulates receiver)
        PacketEncryptor decryptor = new PacketEncryptor(keys);
        ByteBuffer decrypted = decryptor.decrypt(encrypted);

        byte[] result = new byte[decrypted.remaining()];
        decrypted.get(result);

        assertArrayEquals(plaintext, result);
    }

    @Test
    void packetEncryptor_addsSomeOverhead()
    {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        PacketEncryptor encryptor = new PacketEncryptor(key, nonce);

        byte[] plaintext = new byte[100];
        ByteBuffer encrypted = encryptor.encrypt(plaintext);

        // Should be: 12 (nonce) + 100 (data) + 16 (auth tag) = 128
        assertEquals(128, encrypted.remaining());
        assertEquals(28, PacketEncryptor.getOverhead());
    }

    @Test
    void packetEncryptor_tamperedCiphertext_failsDecryption()
    {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        PacketEncryptor encryptor = new PacketEncryptor(key, nonce);

        ByteBuffer encrypted = encryptor.encrypt("secret".getBytes());
        byte[] encryptedBytes = new byte[encrypted.remaining()];
        encrypted.get(encryptedBytes);

        // Tamper with ciphertext
        encryptedBytes[encryptedBytes.length - 1] ^= 0xFF;

        PacketEncryptor decryptor = new PacketEncryptor(key, nonce);
        assertThrows(SecurityException.class, () ->
                decryptor.decrypt(encryptedBytes));
    }

    @Test
    void packetEncryptor_counterIncrements()
    {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        PacketEncryptor encryptor = new PacketEncryptor(key, nonce);

        assertEquals(0, encryptor.getSendCounter());

        encryptor.encrypt("msg1".getBytes());
        assertEquals(1, encryptor.getSendCounter());

        encryptor.encrypt("msg2".getBytes());
        assertEquals(2, encryptor.getSendCounter());
    }

    @Test
    void packetEncryptor_differentCountersProduceDifferentCiphertext()
    {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        PacketEncryptor encryptor = new PacketEncryptor(key, nonce);

        byte[] plaintext = "same message".getBytes();
        ByteBuffer encrypted1 = encryptor.encrypt(plaintext);
        ByteBuffer encrypted2 = encryptor.encrypt(plaintext);

        byte[] bytes1 = new byte[encrypted1.remaining()];
        byte[] bytes2 = new byte[encrypted2.remaining()];
        encrypted1.get(bytes1);
        encrypted2.get(bytes2);

        assertFalse(Arrays.equals(bytes1, bytes2));
    }

    @Test
    void packetEncryptor_emptyPlaintext()
    {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        PacketEncryptor encryptor = new PacketEncryptor(key, nonce);
        PacketEncryptor decryptor = new PacketEncryptor(key, nonce);

        byte[] plaintext = new byte[0];
        ByteBuffer encrypted = encryptor.encrypt(plaintext);
        ByteBuffer decrypted = decryptor.decrypt(encrypted);

        assertEquals(0, decrypted.remaining());
    }

    @Test
    void packetEncryptor_largePayload()
    {
        byte[] key = new byte[32];
        byte[] nonce = new byte[12];
        PacketEncryptor encryptor = new PacketEncryptor(key, nonce);
        PacketEncryptor decryptor = new PacketEncryptor(key, nonce);

        byte[] plaintext = new byte[10000];
        Arrays.fill(plaintext, (byte) 0xAB);

        ByteBuffer encrypted = encryptor.encrypt(plaintext);
        ByteBuffer decrypted = decryptor.decrypt(encrypted);

        byte[] result = new byte[decrypted.remaining()];
        decrypted.get(result);

        assertArrayEquals(plaintext, result);
    }

    // ========== Full Handshake Simulation ==========

    @Test
    void fullHandshake_clientServerExchange()
    {
        // Server generates long-term signing key
        Signer.SigningKeyPair serverSigningKey = Signer.generateKeyPair();

        // Client has server's public key (distributed with app)
        java.security.PublicKey serverPublicSigningKey = serverSigningKey.publicKey();

        // 1. Both sides generate ephemeral key pairs
        KeyExchange clientKx = new KeyExchange();
        KeyExchange serverKx = new KeyExchange();

        // 2. Exchange public keys (ClientHello/ServerHello)
        byte[] clientEphemeralPublic = clientKx.getPublicKey();
        byte[] serverEphemeralPublic = serverKx.getPublicKey();

        // 3. Server signs its ephemeral public key
        byte[] serverSignature = Signer.sign(serverEphemeralPublic, serverSigningKey.privateKey());

        // 4. Client verifies server's signature
        assertTrue(Signer.verify(serverEphemeralPublic, serverSignature, serverPublicSigningKey));

        // 5. Both compute shared secret
        byte[] clientSecret = clientKx.computeSharedSecret(serverEphemeralPublic);
        byte[] serverSecret = serverKx.computeSharedSecret(clientEphemeralPublic);
        assertArrayEquals(clientSecret, serverSecret);

        // 6. Derive encryption keys
        KeyExchange.DerivedKeys clientKeys = KeyExchange.deriveKeys(clientSecret);
        KeyExchange.DerivedKeys serverKeys = KeyExchange.deriveKeys(serverSecret);

        // 7. Create encryptors
        PacketEncryptor clientEncryptor = new PacketEncryptor(clientKeys);
        PacketEncryptor serverDecryptor = new PacketEncryptor(serverKeys);

        // 8. Client sends encrypted message
        byte[] message = "Connect request".getBytes();
        ByteBuffer encrypted = clientEncryptor.encrypt(message);

        // 9. Server decrypts
        ByteBuffer decrypted = serverDecryptor.decrypt(encrypted);
        byte[] received = new byte[decrypted.remaining()];
        decrypted.get(received);

        assertArrayEquals(message, received);
    }
}

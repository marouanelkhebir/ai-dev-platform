package com.mel.aidev.settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM encryption of the secrets edited from the settings screen.
 *
 * <p>Tokens typed in a browser end up in a database row, so they are encrypted at rest. This
 * protects a backup, a replica or a {@code pg_dump} handed to a developer — it does not protect
 * against someone who already has both the dump and the key.
 *
 * <p>The key comes from {@code settings.encryption-key} (environment: {@code
 * SETTINGS_ENCRYPTION_KEY}). When that property is not set, {@link SettingsService} generates a key
 * and stores it next to the values, which only raises the bar on a casual dump; production
 * deployments are expected to provide the key from their secret manager.
 */
public final class SettingsEncryptor {

    /** Marks a value produced by this class, so the format can change later without guessing. */
    private static final String PREFIX = "v1:";

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param keyMaterial any non-blank string; hashed to a 256 bit key, so a passphrase and a base64
     *     key are both accepted
     */
    public SettingsEncryptor(String keyMaterial) {
        if (keyMaterial == null || keyMaterial.isBlank()) {
            throw new IllegalArgumentException("Encryption key material must not be blank");
        }
        this.key = new SecretKeySpec(sha256(keyMaterial), "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to encrypt a settings value", e);
        }
    }

    /**
     * @throws IllegalStateException when the value was encrypted with another key
     */
    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            // Written before encryption was in place, or by hand in SQL: returned as-is.
            return stored;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to decrypt a stored secret: it was encrypted with a different key."
                            + " Restore SETTINGS_ENCRYPTION_KEY, or clear the secret from the settings screen.",
                    e);
        }
    }

    /** Fresh 256 bit key, base64 encoded. */
    public static String generateKey() {
        byte[] material = new byte[32];
        new SecureRandom().nextBytes(material);
        return Base64.getEncoder().encodeToString(material);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}

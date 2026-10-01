package com.campusmatch.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM encryption for resume files and sensitive columns. Output = IV(12) || ciphertext+tag. */
@Component
public class Crypto {
    private static volatile Crypto instance;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public Crypto(@Value("${app.enc-key}") String base64Key) {
        byte[] k = Base64.getDecoder().decode(base64Key);
        if (k.length != 32) throw new IllegalStateException("app.enc-key must be 32 bytes, base64-encoded");
        this.key = new SecretKeySpec(k, "AES");
        instance = this;
    }

    public static Crypto get() { return instance; }

    public byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] data) {
        try {
            byte[] iv = Arrays.copyOfRange(data, 0, 12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return c.doFinal(data, 12, data.length - 12);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    public String encryptString(String s) {
        return Base64.getEncoder().encodeToString(encrypt(s.getBytes(StandardCharsets.UTF_8)));
    }

    public String decryptString(String s) {
        return new String(decrypt(Base64.getDecoder().decode(s)), StandardCharsets.UTF_8);
    }
}

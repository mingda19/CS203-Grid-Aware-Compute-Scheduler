package com.gacs.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class to compute deterministic SHA-256 digests for schedule payloads.
 */
public final class ScheduleHashUtil {

    private ScheduleHashUtil() {}

    /**
     * Computes the SHA-256 hexadecimal hash string for an input string.
     *
     * @param input the input string to hash
     * @return 64-character lowercase hexadecimal hash
     */
    public static String computeSha256(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Input string cannot be null for hash computation");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedHash.length);
            for (byte b : encodedHash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available in runtime environment", e);
        }
    }
}

package com.gacs.backend.security;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Policy for newly created passwords only; existing passwords still work at login. */
public final class PasswordPolicy {
    private PasswordPolicy() {}
    private static final Set<String> COMMON = Set.of(
            "123456789012345", "1234567890123456", "12345678901234567890",
            "passwordpassword", "password123456789", "qwertyuiopasdfgh", "qwertyuiopasdfghjkl",
            "letmeinletmeinletmein", "gacsgacsgacsgacs", "correct horse battery staple");

    public static void validate(String password) {
        if (password == null || password.isBlank() || password.codePointCount(0, password.length()) < 15)
            throw new IllegalArgumentException("Use at least 15 characters.");
        if (password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("Password is too long. Use at most 72 bytes (some characters use more than one).");
        if (COMMON.contains(password.strip().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("This password is too common. Choose a unique passphrase.");
    }
}

package com.gacs.backend;
import com.gacs.backend.security.PasswordPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PasswordPolicyTest {
    @Test void allowsPassphrasesWithoutCompositionRules() {
        assertDoesNotThrow(() -> PasswordPolicy.validate("river lantern orbit meadow"));
        assertDoesNotThrow(() -> PasswordPolicy.validate("x".repeat(72)));
    }
    @Test void rejectsShortCommonAndOversizedPasswords() {
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("P@ssw0rd!"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("PASSWORDPASSWORD"));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("x".repeat(73)));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("\u00e9".repeat(37)));
        assertThrows(IllegalArgumentException.class, () -> PasswordPolicy.validate("\ud83d\ude00".repeat(8)));
    }
}

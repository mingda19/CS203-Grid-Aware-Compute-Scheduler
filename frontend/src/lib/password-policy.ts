export const PASSWORD_HINT = "Use at least 15 characters."
const common = new Set([
  "123456789012345", "1234567890123456", "12345678901234567890",
  "passwordpassword", "password123456789", "qwertyuiopasdfgh", "qwertyuiopasdfghjkl",
  "letmeinletmeinletmein", "gacsgacsgacsgacs", "correct horse battery staple",
])

export function passwordError(password: string): string | null {
  if (!password.trim() || Array.from(password).length < 15) return PASSWORD_HINT
  if (new TextEncoder().encode(password).length > 72)
    return "Password is too long. Use at most 72 bytes (some characters use more than one)."
  if (common.has(password.trim().toLowerCase()))
    return "This password is too common. Choose a unique passphrase."
  return null
}

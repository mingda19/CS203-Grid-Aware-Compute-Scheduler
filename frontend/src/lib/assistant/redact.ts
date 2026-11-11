/**
 * Strips confidential data from chat text before it can reach the model.
 *
 * Runs in the browser (so the user sees what was removed) and again in the route handler (so a
 * modified client cannot skip it). Pure string work only, so it adds no noticeable latency.
 */

/** What the input box allows. */
export const MAX_MESSAGE_CHARS = 1000
/** Placeholders are longer than what they replace, so text after redaction may be a little longer. */
export const MAX_CLEAN_CHARS = 1500

export type RedactionType =
  | "PRIVATE_KEY"
  | "JWT"
  | "API_KEY"
  | "CREDENTIALS"
  | "SECRET"
  | "EMAIL"
  | "CARD"
  | "NRIC"
  | "IP"
  | "PHONE"

export const REDACTION_LABELS: Record<RedactionType, string> = {
  PRIVATE_KEY: "private key",
  JWT: "access token",
  API_KEY: "API key",
  CREDENTIALS: "login in a link",
  SECRET: "password or secret",
  EMAIL: "email",
  CARD: "card number",
  NRIC: "NRIC/FIN",
  IP: "IP address",
  PHONE: "phone number",
}

export interface RedactionResult {
  text: string
  /** Each type that was removed, once, in the order the rules ran. */
  types: RedactionType[]
}

const placeholder = (type: RedactionType) => `[REDACTED_${type}]`

/** Luhn checksum, so ordinary long numbers are not mistaken for card numbers. */
export function passesLuhn(digits: string): boolean {
  let sum = 0
  let double = false
  for (let i = digits.length - 1; i >= 0; i--) {
    let d = digits.charCodeAt(i) - 48
    if (double) {
      d *= 2
      if (d > 9) d -= 9
    }
    sum += d
    double = !double
  }
  return sum % 10 === 0
}

/** A value already replaced by an earlier rule must not be matched again. */
const NOT_PLACEHOLDER = String.raw`(?!\[REDACTED_)`
const SECRET_LABEL = String.raw`\b(password|passwd|pwd|passcode|passphrase|secret|api[_ -]?key|access[_ -]?key|auth[_ -]?token|token|client[_ -]?secret|otp|pin)\b`

interface Rule {
  type: RedactionType
  pattern: RegExp
  replace: (match: string, ...groups: string[]) => string | null
}

// Order matters: the most specific shapes run first so a JWT is not half-eaten by the hex rule.
const RULES: Rule[] = [
  {
    type: "PRIVATE_KEY",
    pattern: /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)/g,
    replace: () => placeholder("PRIVATE_KEY"),
  },
  {
    type: "JWT",
    pattern: /\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/g,
    replace: () => placeholder("JWT"),
  },
  {
    type: "API_KEY",
    pattern:
      /\b(?:sk-(?:ant-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{30,}|xox[abprs]-[A-Za-z0-9-]{10,})|\bBearer\s+[A-Za-z0-9._~+/-]{16,}=*/g,
    replace: () => placeholder("API_KEY"),
  },
  {
    // postgres://user:pass@host, https://user:token@github.com
    type: "CREDENTIALS",
    pattern: /\b([a-z][a-z0-9+.-]*:\/\/)[^\s:@/]+:[^\s@/]+@/gi,
    replace: (_m, scheme) => `${scheme}${placeholder("CREDENTIALS")}@`,
  },
  {
    // "password: x", "token=x" - the label stays so the question still makes sense.
    type: "SECRET",
    pattern: new RegExp(`${SECRET_LABEL}(\\s*[:=]\\s*)${NOT_PLACEHOLDER}("[^"]*"|'[^']*'|\\S+)`, "gi"),
    replace: (_m, label, sep) => `${label}${sep}${placeholder("SECRET")}`,
  },
  {
    // "my password is hunter2!" - only when the value looks like a secret (has a digit or symbol),
    // so "my password is rejected" keeps its meaning.
    type: "SECRET",
    pattern: new RegExp(`${SECRET_LABEL}(\\s+is\\s+)${NOT_PLACEHOLDER}((?=\\S*[\\d!@#$%^&*_+=~])\\S{6,})`, "gi"),
    replace: (_m, label, sep) => `${label}${sep}${placeholder("SECRET")}`,
  },
  {
    type: "EMAIL",
    pattern: /\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g,
    replace: () => placeholder("EMAIL"),
  },
  {
    type: "CARD",
    pattern: /\b\d(?:[ -]?\d){12,18}\b/g,
    replace: (match) => {
      const digits = match.replace(/\D/g, "")
      return digits.length >= 13 && digits.length <= 19 && passesLuhn(digits) ? placeholder("CARD") : null
    },
  },
  {
    // Singapore NRIC / FIN: S1234567D
    type: "NRIC",
    pattern: /\b[STFGM]\d{7}[A-Z]\b/gi,
    replace: () => placeholder("NRIC"),
  },
  {
    type: "IP",
    pattern: /\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\b/g,
    replace: () => placeholder("IP"),
  },
  {
    // International (+65 9123 4567, +1 (512) 555-0100), Singapore (9123 4567) and US (512-555-0100).
    type: "PHONE",
    pattern:
      /\+\d{1,3}[\s.-]?(?:\(?\d{1,4}\)?[\s.-]?){1,4}\d{2,4}\b|\b[3689]\d{3}[\s-]?\d{4}\b|\(?\b\d{3}\)?[\s.-]\d{3}[\s.-]\d{4}\b/g,
    replace: () => placeholder("PHONE"),
  },
  {
    // Long hex or base64 strings are almost always keys, hashes of secrets or tokens.
    type: "SECRET",
    pattern: /\b[A-Fa-f0-9]{32,}\b|(?<![A-Za-z0-9+/_-])(?=[A-Za-z0-9+/_-]*\d)(?=[A-Za-z0-9+/_-]*[A-Za-z])[A-Za-z0-9+/_-]{40,}={0,2}/g,
    replace: () => placeholder("SECRET"),
  },
]

export function redact(input: string): RedactionResult {
  let text = input
  const types: RedactionType[] = []
  for (const rule of RULES) {
    text = text.replace(rule.pattern, (match: string, ...rest: unknown[]) => {
      const groups = rest.filter((value): value is string => typeof value === "string")
      const replacement = rule.replace(match, ...groups)
      if (replacement === null) return match
      if (!types.includes(rule.type)) types.push(rule.type)
      return replacement
    })
  }
  return { text, types }
}

/**
 * Drops what carries no meaning for the model and could hide a secret from the rules: invisible
 * characters, control codes and long runs of whitespace.
 */
export function sanitize(input: string): string {
  return input
    .normalize("NFKC")
    .replace(/[\u200B-\u200D\u2060\uFEFF]/g, "")
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, "")
    .replace(/\r\n?/g, "\n")
    .replace(/[ \t]{2,}/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim()
}

/**
 * What the browser and the server both run on a message: clean, redact, then cap the length.
 * Redacting before the cap means a secret is never cut in half and left partly visible.
 */
export function cleanUserMessage(input: string, maxChars = MAX_CLEAN_CHARS): RedactionResult {
  const { text, types } = redact(sanitize(input))
  return { text: text.slice(0, maxChars), types }
}

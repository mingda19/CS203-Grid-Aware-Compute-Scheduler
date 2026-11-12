"use client"

import * as React from "react"
import Image from "next/image"
import { useRouter } from "next/navigation"
import { AlertCircle, CheckCircle2, Eye, EyeOff, Loader2 } from "lucide-react"

import { Alert } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { authApi, getStoredUser, setStoredUser, removeStoredUser } from "@/lib/api"

import { PASSWORD_HINT, passwordError } from "@/lib/password-policy"
import { Intro } from "./intro"
import { GacsLogo } from "./gacs-logo"

const INTRO_SEEN_KEY = "gacs_intro_seen"

type AuthMode = "login" | "register" | "verify-otp" | "forgot-password" | "reset-password"

const OTP_LENGTH = 6
const INVALID = "aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive"

/** A visible label above its control; `action` sits at the right of the label row. */
function Field({ id, label, action, children }: { id: string; label: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="space-y-2">
      <div className="flex min-h-5 items-center justify-between gap-2">
        <Label htmlFor={id}>{label}</Label>
        {action}
      </div>
      {children}
    </div>
  )
}

/** Password input with its own show/hide toggle. */
function PasswordField({ id, autoComplete, value, onChange, minLength, maxLength, invalid, describedBy }: {
  id: string
  autoComplete: string
  value: string
  onChange: (next: string) => void
  minLength?: number
  maxLength?: number
  invalid?: boolean
  describedBy?: string
}) {
  const [visible, setVisible] = React.useState(false)
  return (
    <div className="relative">
      <Input
        id={id}
        type={visible ? "text" : "password"}
        autoComplete={autoComplete}
        minLength={minLength}
        maxLength={maxLength}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        required
        className={`pr-10 ${INVALID}`}
      />
      <Button
        type="button"
        variant="ghost"
        size="icon"
        onClick={() => setVisible((prev) => !prev)}
        aria-label={visible ? "Hide password" : "Show password"}
        title={visible ? "Hide password" : "Show password"}
        className="absolute right-0 top-0"
      >
        {visible ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
      </Button>
    </div>
  )
}

/** Six single-digit boxes. `value` is always a contiguous digit string (no gaps). */
function OtpInput({ value, onChange, onComplete, disabled }: {
  value: string
  onChange: (next: string) => void
  onComplete: (code: string) => void
  disabled?: boolean
}) {
  const refs = React.useRef<Array<HTMLInputElement | null>>([])
  const focusBox = (i: number) => refs.current[Math.max(0, Math.min(i, OTP_LENGTH - 1))]?.focus()

  // Focus the first box on mount, and again whenever the code is cleared (e.g. after a failed attempt).
  React.useEffect(() => {
    if (value === "" && !disabled) focusBox(0)
  }, [value, disabled])

  const commit = (next: string, focusIndex: number) => {
    const clean = next.replace(/\D/g, "").slice(0, OTP_LENGTH)
    onChange(clean)
    focusBox(focusIndex)
    if (clean.length === OTP_LENGTH) onComplete(clean)
  }

  return (
    <div role="group" aria-label="6-digit verification code" className="grid grid-cols-6 gap-2">
      {Array.from({ length: OTP_LENGTH }, (_, i) => (
        <Input
          key={i}
          ref={(el) => { refs.current[i] = el }}
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          aria-label={`Digit ${i + 1}`}
          value={value[i] ?? ""}
          disabled={disabled}
          onFocus={(e) => {
            // Never let focus skip past the first empty box.
            if (i > value.length) focusBox(value.length)
            else e.target.select()
          }}
          onChange={(e) => {
            const typed = e.target.value.replace(/\D/g, "")
            if (!typed) return
            // Several characters at once = autofill / paste into one box: replace the whole code.
            if (typed.length > 1) return commit(typed, typed.length)
            const next = value.slice(0, i) + typed + value.slice(i + 1)
            commit(next, i + 1)
          }}
          onKeyDown={(e) => {
            if (e.key === "Backspace") {
              e.preventDefault()
              if (value[i]) commit(value.slice(0, i) + value.slice(i + 1), i)
              else if (i > 0) commit(value.slice(0, i - 1) + value.slice(i), i - 1)
            } else if (e.key === "ArrowLeft") { e.preventDefault(); focusBox(i - 1) }
            else if (e.key === "ArrowRight") { e.preventDefault(); focusBox(Math.min(i + 1, value.length)) }
          }}
          onPaste={(e) => {
            e.preventDefault()
            const pasted = e.clipboardData.getData("text").replace(/\D/g, "")
            if (pasted) commit(pasted, pasted.length)
          }}
          className="h-12 min-w-0 px-0 text-center text-lg font-medium tabular-nums"
        />
      ))}
    </div>
  )
}

export default function LoginPage() {
  const router = useRouter()

  // State
  const [mode, setMode] = React.useState<AuthMode>("login")
  const [fullName, setFullName] = React.useState("")
  const [email, setEmail] = React.useState("")
  const [password, setPassword] = React.useState("")
  const [otpCode, setOtpCode] = React.useState("")
  const [recoveryCode, setRecoveryCode] = React.useState("")
  const [confirmPassword, setConfirmPassword] = React.useState("")
  const [rememberMe, setRememberMe] = React.useState(true)

  const [isLoading, setIsLoading] = React.useState(false)
  const [errorMessage, setErrorMessage] = React.useState<string | null>(null)
  const [successMessage, setSuccessMessage] = React.useState<string | null>(null)
  const [resendCooldown, setResendCooldown] = React.useState(0)
  const [showIntro, setShowIntro] = React.useState(false)

  // Layout effect so first-time visitors get the intro before the form paints.
  React.useLayoutEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (!localStorage.getItem(INTRO_SEEN_KEY)) setShowIntro(true)
  }, [])

  const closeIntro = () => {
    localStorage.setItem(INTRO_SEEN_KEY, "1")
    setShowIntro(false)
  }

  // Verify a cached session before leaving the sign-in page.
  React.useEffect(() => {
    if (!getStoredUser()) return
    let active = true
    authApi.getMe().then(response => {
      if (!active) return
      if (response.success && response.data) router.replace("/")
      else removeStoredUser()
    }).catch(() => { if (active) removeStoredUser() })
    return () => { active = false }
  }, [router])

  // Resend countdown timer
  React.useEffect(() => {
    if (resendCooldown <= 0) return
    const timer = setInterval(() => {
      setResendCooldown((prev) => prev - 1)
    }, 1000)
    return () => clearInterval(timer)
  }, [resendCooldown])

  // Handle Standard Login
  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsLoading(true)
    setErrorMessage(null)
    setSuccessMessage(null)

    try {
      const response = await authApi.login({ email, password, rememberMe })

      if (response.requiresOtp) {
        setSuccessMessage("Account verification required. A new code has been sent to your email.")
        setMode("verify-otp")
        setResendCooldown(60)
        return
      }

      if (!response.success || !response.user) throw new Error(response.message || "Sign-in failed. Please try again.")
      setStoredUser(response.user, response.refreshExpiresIn)
      router.replace("/")
    } catch (err: unknown) {
      setErrorMessage((err instanceof Error ? err.message : "") || "Invalid email or password.")
    } finally {
      setIsLoading(false)
    }
  }

  // Handle Registration
  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault()
    const issue = passwordError(password)
    if (issue) { setErrorMessage(issue); setSuccessMessage(null); return }
    if (password !== confirmPassword) { setErrorMessage("Passwords do not match."); setSuccessMessage(null); return }
    setIsLoading(true)
    setErrorMessage(null)
    setSuccessMessage(null)

    try {
      const response = await authApi.register({
        email,
        password,
        fullName: fullName || email.split("@")[0],
      })

      if (!response.success) throw new Error(response.message || "Registration failed")
      setSuccessMessage(response.message || "Verification code sent to your email. Enter it below.")
      setMode("verify-otp")
      setResendCooldown(60)
    } catch (err: unknown) {
      setErrorMessage((err instanceof Error ? err.message : "") || "Registration failed. Please check your details.")
    } finally {
      setIsLoading(false)
    }
  }

  // Handle OTP Verification
  const verifyOtp = async (code: string) => {
    if (isLoading) return
    setIsLoading(true)
    setErrorMessage(null)
    setSuccessMessage(null)

    try {
      const response = await authApi.verifyOtp({ email, otp: code })
      if (!response.success) throw new Error(response.message || "Verification failed")


      setSuccessMessage("Account verified successfully! Please sign in with your password.")
      setOtpCode("")
      setMode("login")
      setPassword("")
      setConfirmPassword("")
    } catch (err: unknown) {
      setOtpCode("") // clear the boxes so the user can retype
      setErrorMessage((err instanceof Error ? err.message : "") || "Invalid or expired code.")
    } finally {
      setIsLoading(false)
    }
  }

  // Handle Resend OTP
  const handleResendOtp = async () => {
    if (resendCooldown > 0 || isLoading) return
    setIsLoading(true)
    setErrorMessage(null)

    try {
      const response = await authApi.resendOtp({ email })
      if (!response.success) throw new Error(response.message || "Could not resend code.")
      setSuccessMessage("A new code has been sent to your email.")
      setResendCooldown(60)
    } catch (err: unknown) {
      setErrorMessage((err instanceof Error ? err.message : "") || "Failed to resend code. Please try again.")
    } finally {
      setIsLoading(false)
    }
  }

  const requestRecovery = async () => {
    setIsLoading(true)
    setErrorMessage(null)
    setSuccessMessage(null)
    try {
      const response = await authApi.forgotPassword({ email })
      if (!response.success) throw new Error(response.message || "Could not request recovery code.")
      setMode("reset-password")
      setRecoveryCode("")
      setResendCooldown(60)
      setSuccessMessage("If this email has a verified account, you’ll receive a recovery code. Check your spam folder too.")
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : "Please try again.")
    } finally { setIsLoading(false) }
  }

  const handleResetPassword = async (event: React.FormEvent) => {
    event.preventDefault()
    setErrorMessage(null)
    setSuccessMessage(null)
    const issue = passwordError(password)
    if (issue) { setErrorMessage(issue); return }
    if (password !== confirmPassword) {
      setErrorMessage("Passwords do not match.")
      return
    }
    setIsLoading(true)
    try {
      const response = await authApi.resetPassword({ email, token: recoveryCode.trim(), password })
      if (!response.success) throw new Error(response.message || "Could not reset password.")
      setPassword("")
      setConfirmPassword("")
      setRecoveryCode("")
      setMode("login")
      setSuccessMessage("Password updated. Sign in with your new password.")
    } catch (err) {
      setErrorMessage(err instanceof Error ? err.message : "Please try again.")
    } finally { setIsLoading(false) }
  }

  // Register / reset: flag a mismatch as soon as the user has started typing the confirmation.
  const passwordsMismatch = confirmPassword.length > 0 && password !== confirmPassword
  const mismatchNote = passwordsMismatch ? (
    <p id="confirm-password-error" role="alert" className="text-xs text-destructive-foreground">
      Passwords do not match.
    </p>
  ) : null

  const goToLogin = () => {
    setOtpCode("")
    setPassword("")
    setRecoveryCode("")
    setConfirmPassword("")
    setMode("login")
    setErrorMessage(null)
    setSuccessMessage(null)
  }

  const openMode = (next: AuthMode) => {
    setMode(next)
    setErrorMessage(null)
    setSuccessMessage(null)
    setPassword("")
    setConfirmPassword("")
    setRecoveryCode("")
  }

  /** Full-width submit button; shows a spinner and `pending` text while a request runs. */
  const submit = (label: string, pending: string, disabled = false) => (
    <Button type="submit" className="w-full" disabled={isLoading || disabled}>
      {isLoading ? (<><Loader2 className="mr-2 h-4 w-4 animate-spin" />{pending}</>) : label}
    </Button>
  )

  const backToSignIn = (
    <Button type="button" variant="link" className="h-auto p-0 text-sm" disabled={isLoading} onClick={goToLogin}>
      Back to sign in
    </Button>
  )

  const titles: Record<AuthMode, [string, string]> = {
    login: ["Sign in", ""],
    register: ["Create account", "Use your work email. We’ll send a code to confirm it."],
    "verify-otp": ["Check your email", `We sent a 6-digit code to ${email}.`],
    "forgot-password": ["Reset your password", "Enter your account email to request a recovery code."],
    "reset-password": ["Set a new password", `Paste the recovery code emailed to ${email}. It expires in 10 minutes.`],
  }
  const [title, description] = titles[mode]

  return (
    <div className="grid min-h-screen bg-background lg:grid-cols-[minmax(0,1fr)_32rem]">
      {/* #f1f8f8 is the image's own sky and #0e1313 its ground band (BAND in references/extract_login_layers.py): the picture sits in the vertical middle, the ground fill continues below it. */}
      <div className="relative hidden overflow-hidden border-r border-border bg-[#f1f8f8] lg:block" aria-hidden="true">
        <div className="absolute inset-x-0 bottom-0 h-1/2 bg-[#0e1313]" />
        {/* One box with the image's shape, 2% wider than the panel so the cut-off cable end at its top-right corner falls outside it, so both layers stay aligned at every width. Its background turns dark where the picture is solid ground, so no sky shows at the subpixel bottom edge. */}
        <div className="absolute inset-x-[-1%] top-1/2 aspect-[1024/765] -translate-y-1/2 overflow-hidden bg-[linear-gradient(#f1f8f8_80%,#0e1313_80%)]">
          {/* eslint-disable-next-line @next/next/no-img-element -- SVG, nothing for next/image to optimise */}
          <img src="/login/design3-sun.svg" alt="" className="absolute" style={{ left: "52.051%", top: "55.948%", width: "25.547%" }} />
          <Image src="/login/design3-front.png" alt="" fill sizes="(min-width: 1024px) 66vw, 0px" />
        </div>
      </div>
      <div className="flex items-center justify-center p-4">
      <div className="w-full max-w-sm space-y-6">
        {/* Brand lockup, same layout as the sidebar header */}
        <div className="flex items-center justify-center gap-3">
          <GacsLogo className="h-10 w-10 shrink-0" />
          <div className="flex min-w-0 flex-col">
            <span className="text-xl font-semibold leading-none">GACS</span>
            <span className="mt-1 text-xs font-medium uppercase tracking-wider text-muted-foreground">Grid-Aware Compute Scheduler</span>
          </div>
        </div>

        <Card>
          <CardHeader className="space-y-1">
            <CardTitle className="text-lg font-semibold">{title}</CardTitle>
            {description && <CardDescription className="break-words">{description}</CardDescription>}
          </CardHeader>

          <CardContent className="space-y-4">
            {errorMessage && (
              <Alert variant="destructive" aria-live="assertive">
                <AlertCircle className="h-4 w-4" />
                <div className="text-sm">{errorMessage}</div>
              </Alert>
            )}
            {successMessage && (
              <Alert variant="success" role="status" aria-live="polite">
                <CheckCircle2 className="h-4 w-4" />
                <div className="text-sm">{successMessage}</div>
              </Alert>
            )}

            {mode === "login" && (
              <form onSubmit={handleLogin} className="space-y-4">
                <Field id="login-email" label="Email">
                  <Input id="login-email" type="email" placeholder="name@company.com" autoComplete="username"
                    value={email} onChange={(e) => setEmail(e.target.value)} required />
                </Field>

                <Field
                  id="login-password"
                  label="Password"
                  action={
                    <Button type="button" variant="link" className="h-auto p-0 text-xs" onClick={() => openMode("forgot-password")}>
                      Forgot password?
                    </Button>
                  }
                >
                  <PasswordField id="login-password" autoComplete="current-password" value={password} onChange={setPassword} />
                </Field>

                <div className="flex items-center gap-2">
                  <Checkbox id="remember-me" checked={rememberMe} onCheckedChange={(checked) => setRememberMe(Boolean(checked))} />
                  <Label htmlFor="remember-me" className="text-sm font-normal">Keep me signed in</Label>
                </div>

                {submit("Sign in", "Signing in…")}

                <p className="text-center text-sm text-muted-foreground">
                  No account?{" "}
                  <Button type="button" variant="link" className="h-auto p-0 text-sm" onClick={() => openMode("register")}>
                    Create one
                  </Button>
                </p>
              </form>
            )}

            {mode === "forgot-password" && (
              <form onSubmit={(event) => { event.preventDefault(); void requestRecovery() }} className="space-y-4">
                <Field id="recovery-email" label="Email">
                  <Input id="recovery-email" type="email" placeholder="name@company.com" autoComplete="email" required autoFocus
                    value={email} onChange={(event) => setEmail(event.target.value)} disabled={isLoading} />
                </Field>
                {submit("Send recovery code", "Sending…")}
                <div className="text-center">{backToSignIn}</div>
              </form>
            )}

            {mode === "reset-password" && (
              <form onSubmit={handleResetPassword} className="space-y-4">
                <Field id="recovery-code" label="Recovery code">
                  <Input id="recovery-code" autoComplete="one-time-code" autoCapitalize="none" spellCheck={false}
                    required autoFocus minLength={43} maxLength={43} pattern="[A-Za-z0-9_-]{43}"
                    title="Paste the complete 43-character code from your email"
                    value={recoveryCode} onChange={(event) => setRecoveryCode(event.target.value)} />
                </Field>

                <Field id="new-password" label="New password">
                  <PasswordField id="new-password" autoComplete="new-password" minLength={15} maxLength={72}
                    describedBy="password-hint" value={password} onChange={setPassword} />
                  <p id="password-hint" className="text-xs text-muted-foreground">{PASSWORD_HINT}</p>
                </Field>

                <Field id="confirm-password" label="Confirm new password">
                  <PasswordField id="confirm-password" autoComplete="new-password" minLength={15} maxLength={72}
                    invalid={passwordsMismatch} describedBy={passwordsMismatch ? "confirm-password-error" : undefined}
                    value={confirmPassword} onChange={setConfirmPassword} />
                  {mismatchNote}
                </Field>

                {submit("Update password", "Updating…", passwordsMismatch)}

                <div className="flex items-center justify-between gap-2">
                  <Button type="button" variant="link" className="h-auto p-0 text-sm" onClick={() => void requestRecovery()}
                    disabled={isLoading || resendCooldown > 0}>
                    {resendCooldown > 0 ? `Resend in ${resendCooldown}s` : "Send a new code"}
                  </Button>
                  {backToSignIn}
                </div>
              </form>
            )}

            {mode === "register" && (
              <form onSubmit={handleRegister} className="space-y-4">
                <Field id="reg-fullname" label="Full name">
                  <Input id="reg-fullname" autoComplete="name" placeholder="Alex Tan"
                    value={fullName} onChange={(e) => setFullName(e.target.value)} required />
                </Field>

                <Field id="reg-email" label="Email">
                  <Input id="reg-email" type="email" placeholder="name@company.com" autoComplete="email"
                    value={email} onChange={(e) => setEmail(e.target.value)} required />
                </Field>

                <Field id="reg-password" label="Password">
                  <PasswordField id="reg-password" autoComplete="new-password" minLength={15} maxLength={72}
                    describedBy="register-password-hint" value={password} onChange={setPassword} />
                  <p id="register-password-hint" className="text-xs text-muted-foreground">{PASSWORD_HINT}</p>
                </Field>

                <Field id="reg-confirm-password" label="Confirm password">
                  <PasswordField id="reg-confirm-password" autoComplete="new-password" minLength={15} maxLength={72}
                    invalid={passwordsMismatch} describedBy={passwordsMismatch ? "confirm-password-error" : undefined}
                    value={confirmPassword} onChange={setConfirmPassword} />
                  {mismatchNote}
                </Field>

                {submit("Create account", "Creating account…", passwordsMismatch)}
                <div className="text-center">{backToSignIn}</div>
              </form>
            )}

            {mode === "verify-otp" && (
              <div className="space-y-4">
                <OtpInput value={otpCode} onChange={setOtpCode} onComplete={(code) => void verifyOtp(code)} disabled={isLoading} />
                {isLoading ? (
                  <p role="status" className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
                    <Loader2 className="h-4 w-4 animate-spin" />
                    Verifying…
                  </p>
                ) : (
                  <p className="text-center text-xs text-muted-foreground">
                    The code is valid for 10 minutes. Check your spam folder if it hasn’t arrived.
                  </p>
                )}

                <div className="flex items-center justify-between gap-2">
                  <Button type="button" variant="link" className="h-auto p-0 text-sm" onClick={handleResendOtp}
                    disabled={resendCooldown > 0 || isLoading}>
                    {resendCooldown > 0 ? `Resend in ${resendCooldown}s` : "Resend code"}
                  </Button>
                  <Button type="button" variant="link" className="h-auto p-0 text-sm" disabled={isLoading} onClick={goToLogin}>
                    Use a different email
                  </Button>
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        <div className="text-center">
          <Button type="button" variant="link" className="h-auto p-0 text-xs text-muted-foreground" onClick={() => setShowIntro(true)}>
            Watch intro
          </Button>
        </div>
      </div>
      </div>

      {showIntro && <Intro onDone={closeIntro} />}
    </div>
  )
}

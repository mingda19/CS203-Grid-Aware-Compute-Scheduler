"use client"

import * as React from "react"
import { useRouter } from "next/navigation"
import {
  Lock,
  Mail,
  User as UserIcon,
  ArrowRight,
  CheckCircle,
  AlertCircle,
  KeyRound,
  RotateCcw,
  ArrowLeft,
  Eye,
  EyeOff,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Checkbox } from "@/components/ui/checkbox"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { authApi, getStoredUser, setStoredUser, removeStoredUser } from "@/lib/api"

import { PASSWORD_HINT, passwordError } from "@/lib/password-policy"
import { Intro } from "./intro"
import { LoginBackdrop } from "./backdrop"
import { GacsLogo } from "./gacs-logo"

const INTRO_SEEN_KEY = "gacs_intro_seen"

type AuthMode = "login" | "register" | "verify-otp" | "forgot-password" | "reset-password"

const OTP_LENGTH = 6
const TICK_MS = 500 // how long the success tick shows before moving on
const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/** Self-drawing check mark; styles live in globals.css. */
function AnimatedTick({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={3} strokeLinecap="round" strokeLinejoin="round"
      aria-hidden="true" className={`animate-tick-pop ${className ?? ""}`}>
      <path d="M5 12.5l4.5 4.5L19 7.5" pathLength={1} className="animate-tick-draw" />
    </svg>
  )
}

/** Password input with a leading lock icon and its own show/hide toggle. */
function PasswordField({ id, placeholder, value, onChange, autoComplete, minLength, maxLength, invalid, describedBy }: {
  id: string
  placeholder: string
  value: string
  onChange: (next: string) => void
  autoComplete: string
  minLength?: number
  maxLength?: number
  invalid?: boolean
  describedBy?: string
}) {
  const [visible, setVisible] = React.useState(false)
  return (
    <div className="relative">
      <Lock className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-muted-foreground pointer-events-none" />
      <Input
        id={id}
        type={visible ? "text" : "password"}
        aria-label={placeholder}
        placeholder={placeholder}
        autoComplete={autoComplete}
        minLength={minLength}
        maxLength={maxLength}
        aria-invalid={invalid}
        aria-describedby={describedBy}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        required
        className={`pl-12 pr-12 h-12 text-base ${invalid ? "border-red-500/60 focus-visible:ring-red-500" : ""}`}
      />
      <button
        type="button"
        onClick={() => setVisible((prev) => !prev)}
        aria-label={visible ? "Hide password" : "Show password"}
        className="absolute right-4 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground cursor-pointer"
      >
        {visible ? <EyeOff className="h-5 w-5" /> : <Eye className="h-5 w-5" />}
      </button>
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
          className="h-14 min-w-0 px-0 text-center font-mono text-2xl font-semibold tabular-nums"
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
  const [showTick, setShowTick] = React.useState(false)
  const cardRef = React.useRef<HTMLDivElement>(null)

  // Shake the card whenever a new error appears (skipped for reduced-motion users).
  React.useEffect(() => {
    if (!errorMessage || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return
    cardRef.current?.animate?.(
      [
        { transform: "translateX(0)" },
        { transform: "translateX(-10px)" },
        { transform: "translateX(9px)" },
        { transform: "translateX(-6px)" },
        { transform: "translateX(4px)" },
        { transform: "translateX(0)" },
      ],
      { duration: 420, easing: "ease-in-out" },
    )
  }, [errorMessage])

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
        setSuccessMessage("Account verification required. A new OTP has been sent to your email.")
        setMode("verify-otp")
        setResendCooldown(60)
        return
      }

      if (!response.success || !response.user) throw new Error(response.message || "Sign-in failed. Please try again.")
      setStoredUser(response.user, response.refreshExpiresIn)
      setShowTick(true) // stays on until the redirect unmounts the page
      await sleep(TICK_MS)
      router.replace("/")
    } catch (err: unknown) {
      setErrorMessage((err instanceof Error ? err.message : "") || "Invalid work email or password.")
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
      setShowTick(true)
      await sleep(TICK_MS)
      setShowTick(false)
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

      setShowTick(true)
      await sleep(TICK_MS)
      setShowTick(false)

      setSuccessMessage("Account verified successfully! Please sign in with your password.")
      setOtpCode("")
      setMode("login")
      setPassword("")
      setConfirmPassword("")
    } catch (err: unknown) {
      setOtpCode("") // clear the boxes so the user can retype
      setErrorMessage((err instanceof Error ? err.message : "") || "Invalid or expired OTP code.")
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
      setErrorMessage((err instanceof Error ? err.message : "") || "Failed to resend OTP. Please try again.")
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
    <p id="confirm-password-error" role="alert" className="text-xs text-red-600 dark:text-red-400">
      Passwords do not match.
    </p>
  ) : null

  // Logo + heading shared by every auth screen.
  const brandTitle = (text: string) => (
    <span className="flex items-center gap-2.5">
      <GacsLogo className="h-11 w-11 shrink-0 text-emerald-500" />
      <span className="text-2xl font-extrabold tracking-tight">{text}</span>
    </span>
  )

  const goToLogin = () => {
    setOtpCode("")
    setPassword("")
    setRecoveryCode("")
    setConfirmPassword("")
    setMode("login")
    setErrorMessage(null)
    setSuccessMessage(null)
  }

  const submitButtonClass =
    "w-full h-14 font-bold text-base shadow-lg shadow-emerald-600/30 transition-all flex items-center justify-center gap-2 cursor-pointer"
  const spinner = <span className="h-5 w-5 border-2 border-white/40 border-t-white rounded-full animate-spin" />

  return (
    <div className="relative min-h-screen flex items-center justify-center p-4 bg-background selection:bg-emerald-500 selection:text-white overflow-hidden">
      <LoginBackdrop />

      <div className="relative z-10 w-full max-w-md space-y-5">
        {/* Environment badge, styled like the pill on the intro end card */}
        <div className="text-center">
          <div className="inline-flex items-center gap-2.5 rounded-full border border-emerald-500/40 bg-emerald-500/5 px-4 py-1.5 text-[11px] font-mono font-semibold tracking-[0.2em] text-emerald-600 dark:text-emerald-400 shadow-[0_0_20px_rgba(16,185,129,0.15)]">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-500 animate-pulse" />
            <span>GRID-AWARE COMPUTE SCHEDULER</span>
          </div>
        </div>

        {/* Auth Card. Concentric corners: inner radius = outer radius (--card-r) - padding (--card-p). */}
        <Card
          ref={cardRef}
          style={{ "--card-r": "2rem", "--card-p": "1.5rem", "--inner-r": "calc(var(--card-r) - var(--card-p))" } as React.CSSProperties}
          className="rounded-[var(--card-r)] border border-border/80 bg-card/90 backdrop-blur-xl shadow-2xl [&_input]:rounded-[var(--inner-r)] [&_button[type=submit]]:rounded-[var(--inner-r)]"
        >
          <CardHeader className="space-y-2 pb-4">
            <div className="flex items-center justify-between pt-1">
              <CardTitle className="text-base font-bold text-foreground">
                {mode === "login" && brandTitle("Sign in to GACS")}
                {mode === "register" && brandTitle("Create an Account")}
                {mode === "verify-otp" && brandTitle("Verify Email with OTP")}
                {mode === "forgot-password" && brandTitle("Forgot password?")}
                {mode === "reset-password" && brandTitle("Set a new password")}
              </CardTitle>
              {mode !== "login" && (
                <button
                  type="button"
                  disabled={isLoading}
                  onClick={goToLogin}
                  className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1 cursor-pointer"
                >
                  <ArrowLeft className="h-3 w-3" /> Back
                </button>
              )}
            </div>
            {(mode === "forgot-password" || mode === "reset-password") && (
              <CardDescription className="text-sm">
                {mode === "forgot-password" && "Enter your account email to request a recovery code."}
                {mode === "reset-password" && `Paste the recovery code emailed to ${email}. It expires in 10 minutes.`}
              </CardDescription>
            )}
          </CardHeader>

          <CardContent className="space-y-4 pt-1">
            {/* Accessible Feedback Banners */}
            {errorMessage && (
              <div
                role="alert"
                aria-live="assertive"
                className="flex items-start gap-2 p-3 text-xs rounded-[var(--inner-r)] border border-red-500/30 bg-red-500/10 text-red-600 dark:text-red-400"
              >
                <AlertCircle className="h-4 w-4 shrink-0 mt-0.5" />
                <span className="leading-tight">{errorMessage}</span>
              </div>
            )}

            {successMessage && (
              <div
                role="status"
                aria-live="polite"
                className="flex items-start gap-2 p-3 text-xs rounded-[var(--inner-r)] border border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
              >
                <CheckCircle className="h-4 w-4 shrink-0 mt-0.5" />
                <span className="leading-tight">{successMessage}</span>
              </div>
            )}

            {/* MODE: STANDARD SIGN-IN FORM */}
            {mode === "login" && (
              <form onSubmit={handleLogin} className="space-y-4">
                <div className="relative">
                  <Mail className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-muted-foreground pointer-events-none" />
                  <Input
                    id="login-email"
                    type="email"
                    aria-label="Work email"
                    placeholder="Work email"
                    autoComplete="username"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    required
                    className="pl-12 h-12 text-base"
                  />
                </div>

                <div className="pt-1">
                  <PasswordField
                    id="login-password"
                    placeholder="Password"
                    autoComplete="current-password"
                    value={password}
                    onChange={setPassword}
                  />
                </div>

                <div className="flex items-center justify-between py-0.5">
                  <div className="flex items-center space-x-2">
                    <Checkbox
                      id="remember-me"
                      checked={rememberMe}
                      onCheckedChange={(checked) => setRememberMe(Boolean(checked))}
                    />
                    <Label
                      htmlFor="remember-me"
                      className="text-sm font-medium cursor-pointer text-muted-foreground hover:text-foreground select-none"
                    >
                      Remember this workstation
                    </Label>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setMode("forgot-password")
                      setErrorMessage(null)
                      setSuccessMessage(null)
                      setPassword("")
                      setConfirmPassword("")
                      setRecoveryCode("")
                    }}
                    className="text-sm text-emerald-600 dark:text-emerald-400 hover:underline cursor-pointer"
                  >
                    Forgot password?
                  </button>
                </div>

                <Button type="submit" className={`${submitButtonClass} mt-1`} disabled={isLoading || showTick}>
                  {showTick ? (
                    <>
                      <AnimatedTick className="h-7 w-7" />
                      <span className="sr-only">Signed in</span>
                    </>
                  ) : isLoading ? (
                    <span className="flex items-center gap-2">
                      {spinner}
                      Verifying session...
                    </span>
                  ) : (
                    <>
                      <span>Log in</span>
                      <ArrowRight className="h-5 w-5" />
                    </>
                  )}
                </Button>

                <div className="text-center pt-1.5">
                  <p className="text-sm text-muted-foreground">
                    Need new credentials?{" "}
                    <button
                      type="button"
                      onClick={() => {
                        setMode("register")
                        setErrorMessage(null)
                        setSuccessMessage(null)
                      }}
                      className="text-emerald-600 dark:text-emerald-400 font-semibold hover:underline cursor-pointer"
                    >
                      Register with OTP
                    </button>
                  </p>
                </div>
              </form>
            )}

            {/* MODE: FORGOT PASSWORD */}
            {mode === "forgot-password" && (
              <form onSubmit={(event) => { event.preventDefault(); void requestRecovery() }} className="space-y-5">
                <div className="relative">
                  <Mail className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-muted-foreground pointer-events-none" />
                  <Input id="recovery-email" type="email" aria-label="Account email" placeholder="Account email"
                    autoComplete="email" required autoFocus
                    value={email} onChange={(event) => setEmail(event.target.value)} disabled={isLoading}
                    className="pl-12 h-12 text-base" />
                </div>
                <Button type="submit" className={submitButtonClass} disabled={isLoading}>
                  {isLoading ? (
                    <span className="flex items-center gap-2">
                      {spinner}
                      Sending...
                    </span>
                  ) : (
                    <>
                      <span>Send recovery code</span>
                      <ArrowRight className="h-5 w-5" />
                    </>
                  )}
                </Button>
              </form>
            )}

            {/* MODE: RESET PASSWORD */}
            {mode === "reset-password" && (
              <form onSubmit={handleResetPassword} className="space-y-5">
                <div className="relative">
                  <KeyRound className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-muted-foreground pointer-events-none" />
                  <Input id="recovery-code" aria-label="Recovery code" placeholder="Recovery code"
                    autoComplete="one-time-code" autoCapitalize="none" spellCheck={false}
                    required autoFocus minLength={43} maxLength={43} pattern="[A-Za-z0-9_-]{43}"
                    title="Paste the complete 43-character code from your email"
                    value={recoveryCode} onChange={(event) => setRecoveryCode(event.target.value)}
                    className="pl-12 h-12 text-base" />
                </div>

                <div className="space-y-1.5">
                  <PasswordField
                    id="new-password"
                    placeholder="New password"
                    autoComplete="new-password"
                    minLength={15}
                    maxLength={72}
                    describedBy="password-hint"
                    value={password}
                    onChange={setPassword}
                  />
                  <p id="password-hint" className="text-xs text-muted-foreground">{PASSWORD_HINT}</p>
                </div>

                <div className="space-y-1.5">
                  <PasswordField
                    id="confirm-password"
                    placeholder="Confirm new password"
                    autoComplete="new-password"
                    minLength={15}
                    maxLength={72}
                    invalid={passwordsMismatch}
                    describedBy={passwordsMismatch ? "confirm-password-error" : undefined}
                    value={confirmPassword}
                    onChange={setConfirmPassword}
                  />
                  {mismatchNote}
                </div>

                <Button type="submit" className={submitButtonClass} disabled={isLoading || passwordsMismatch}>
                  {isLoading ? (
                    <span className="flex items-center gap-2">
                      {spinner}
                      Updating...
                    </span>
                  ) : (
                    <>
                      <span>Update password</span>
                      <ArrowRight className="h-5 w-5" />
                    </>
                  )}
                </Button>

                <div className="text-center">
                  <button type="button" onClick={() => void requestRecovery()} disabled={isLoading || resendCooldown > 0}
                    className="text-sm text-muted-foreground hover:text-foreground disabled:opacity-50 cursor-pointer">
                    {resendCooldown > 0 ? `Resend in ${resendCooldown}s` : "Send a new recovery code"}
                  </button>
                </div>
              </form>
            )}

            {/* MODE: REGISTER FORM */}
            {mode === "register" && (
              <form onSubmit={handleRegister} className="space-y-5">
                <div className="relative">
                  <UserIcon className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-muted-foreground pointer-events-none" />
                  <Input
                    id="reg-fullname"
                    type="text"
                    aria-label="Full name"
                    placeholder="Full name"
                    autoComplete="name"
                    value={fullName}
                    onChange={(e) => setFullName(e.target.value)}
                    required
                    className="pl-12 h-12 text-base"
                  />
                </div>

                <div className="relative">
                  <Mail className="absolute left-4 top-1/2 -translate-y-1/2 h-5 w-5 text-muted-foreground pointer-events-none" />
                  <Input
                    id="reg-email"
                    type="email"
                    aria-label="Work email"
                    placeholder="Work email"
                    autoComplete="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    required
                    className="pl-12 h-12 text-base"
                  />
                </div>

                <div className="space-y-1.5">
                  <PasswordField
                    id="reg-password"
                    placeholder="Set password"
                    autoComplete="new-password"
                    minLength={15}
                    maxLength={72}
                    describedBy="register-password-hint"
                    value={password}
                    onChange={setPassword}
                  />
                  <p id="register-password-hint" className="text-xs text-muted-foreground">{PASSWORD_HINT}</p>
                </div>

                <div className="space-y-1.5">
                  <PasswordField
                    id="reg-confirm-password"
                    placeholder="Confirm password"
                    autoComplete="new-password"
                    minLength={15}
                    maxLength={72}
                    invalid={passwordsMismatch}
                    describedBy={passwordsMismatch ? "confirm-password-error" : undefined}
                    value={confirmPassword}
                    onChange={setConfirmPassword}
                  />
                  {mismatchNote}
                </div>

                <Button type="submit" className={`${submitButtonClass} mt-3`} disabled={isLoading || showTick || passwordsMismatch}>
                  {showTick ? (
                    <>
                      <AnimatedTick className="h-7 w-7" />
                      <span className="sr-only">Code sent</span>
                    </>
                  ) : isLoading ? (
                    <span className="flex items-center gap-2">
                      {spinner}
                      Sending OTP...
                    </span>
                  ) : (
                    <>
                      <span>Send 6-Digit OTP</span>
                      <ArrowRight className="h-5 w-5" />
                    </>
                  )}
                </Button>
              </form>
            )}

            {/* MODE: OTP VERIFICATION */}
            {mode === "verify-otp" && (
              <div className="space-y-5">
                <p className="text-center text-sm text-balance text-muted-foreground">
                  A 6-digit OTP has been sent to{" "}
                  <span className="font-semibold text-foreground break-all">{email}</span>
                </p>
                <OtpInput
                  value={otpCode}
                  onChange={setOtpCode}
                  onComplete={(code) => void verifyOtp(code)}
                  disabled={isLoading}
                />
                {showTick ? (
                  <p role="status" className="flex items-center justify-center gap-2 text-sm font-semibold text-emerald-600 dark:text-emerald-400">
                    <AnimatedTick className="h-5 w-5" />
                    Verified
                  </p>
                ) : isLoading ? (
                  <p role="status" className="flex items-center justify-center gap-2 text-sm text-muted-foreground">
                    <span className="h-4 w-4 border-2 border-muted-foreground/40 border-t-muted-foreground rounded-full animate-spin" />
                    Verifying...
                  </p>
                ) : (
                  <p className="text-[11px] text-muted-foreground text-center">
                    Your code is valid for 10 minutes. Check your spam folder if it hasn’t arrived.
                  </p>
                )}

                <div className="flex items-center justify-between text-xs pt-1">
                  <button
                    type="button"
                    onClick={handleResendOtp}
                    disabled={resendCooldown > 0 || isLoading}
                    className="text-emerald-600 dark:text-emerald-400 font-semibold hover:underline flex items-center gap-1 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
                  >
                    <RotateCcw className="h-3 w-3" />
                    {resendCooldown > 0 ? `Resend in ${resendCooldown}s` : "Resend code"}
                  </button>

                  <button
                    type="button"
                    disabled={isLoading}
                    onClick={() => {
                      setOtpCode("")
                      setConfirmPassword("")
                      setMode("login")
                      setErrorMessage(null)
                      setSuccessMessage(null)
                    }}
                    className="text-muted-foreground hover:text-foreground cursor-pointer"
                  >
                    Change email
                  </button>
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        {/* Replay the intro video */}
        <div className="flex justify-center">
          <button
            type="button"
            onClick={() => setShowIntro(true)}
            aria-label="Replay intro"
            title="Replay intro"
            className="inline-flex h-9 w-9 items-center justify-center rounded-full border border-border/60 bg-card/60 text-muted-foreground backdrop-blur transition-colors hover:border-emerald-500/50 hover:text-foreground cursor-pointer"
          >
            <RotateCcw className="h-4 w-4" />
          </button>
        </div>
      </div>

      {showIntro && <Intro onDone={closeIntro} />}
    </div>
  )
}

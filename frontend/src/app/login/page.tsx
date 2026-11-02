"use client"

import * as React from "react"
import { useRouter } from "next/navigation"
import {
  Zap,
  ShieldCheck,
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
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { authApi, getStoredUser, setStoredUser, removeStoredUser } from "@/lib/api"

type AuthMode = "login" | "register" | "verify-otp"
export default function LoginPage() {
  const router = useRouter()

  // State
  const [mode, setMode] = React.useState<AuthMode>("login")
  const [fullName, setFullName] = React.useState("")
  const [email, setEmail] = React.useState("")
  const [password, setPassword] = React.useState("")
  const [showPassword, setShowPassword] = React.useState(false)
  const [otpCode, setOtpCode] = React.useState("")
  const [rememberMe, setRememberMe] = React.useState(true)

  const [isLoading, setIsLoading] = React.useState(false)
  const [errorMessage, setErrorMessage] = React.useState<string | null>(null)
  const [successMessage, setSuccessMessage] = React.useState<string | null>(null)
  const [resendCooldown, setResendCooldown] = React.useState(0)

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
  const handleVerifyOtp = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsLoading(true)
    setErrorMessage(null)
    setSuccessMessage(null)

    try {
      const response = await authApi.verifyOtp({ email, otp: otpCode.trim() })
      if (!response.success) throw new Error(response.message || "Verification failed")

      setSuccessMessage("Account verified successfully! Please sign in with your password.")
      setMode("login")
      setPassword("")
    } catch (err: unknown) {
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
      setSuccessMessage(response.message || "A fresh OTP code has been dispatched to your email.")
      setResendCooldown(60)
    } catch (err: unknown) {
      setErrorMessage((err instanceof Error ? err.message : "") || "Failed to resend OTP. Please try again.")
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="relative min-h-screen flex items-center justify-center p-4 bg-background selection:bg-emerald-500 selection:text-white overflow-hidden">
      {/* Background Graphic & Ambient Glow */}
      <div className="absolute inset-0 z-0">
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_80%_80%_at_50%_-20%,rgba(16,185,129,0.15),rgba(255,255,255,0))]" />
        <div className="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[520px] h-[520px] bg-emerald-500/10 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute bottom-10 right-10 w-[420px] h-[420px] bg-teal-500/10 rounded-full blur-3xl pointer-events-none" />
      </div>

      <div className="relative z-10 w-full max-w-md space-y-5">
        {/* Environment Badge & Brand Header */}
        <div className="text-center space-y-2">
          <div className="inline-flex items-center gap-2 rounded-full border border-emerald-500/30 bg-emerald-500/10 px-3 py-1 text-[11px] font-mono font-semibold text-emerald-600 dark:text-emerald-400 shadow-xs">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-500 animate-pulse" />
            <span>GRID-AWARE COMPUTE SCHEDULER</span>
          </div>

          <div className="flex items-center justify-center gap-2 pt-1">
            <div className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-tr from-emerald-600 to-teal-400 text-white shadow-lg shadow-emerald-500/25">
              <Zap className="h-5 w-5 fill-current" />
            </div>
            <h1 className="text-2xl font-extrabold tracking-tight text-foreground">
              Sign in to GACS
            </h1>
          </div>
          <p className="text-xs text-muted-foreground">
            Grid Aware Compute Scheduler · Wholesale Dispatch Operations
          </p>
        </div>

        {/* Auth Card */}
        <Card className="border border-border/80 bg-card/90 backdrop-blur-xl shadow-2xl">
          <CardHeader className="space-y-2 pb-3">
            <div className="flex items-center justify-between pt-1">
              <CardTitle className="text-base font-bold text-foreground">
                {mode === "login" && "Enter Account Credentials"}
                {mode === "register" && "Create an Account"}
                {mode === "verify-otp" && "Verify Email with OTP"}
              </CardTitle>
              {mode !== "login" && (
                <button
                  type="button"
                  onClick={() => {
                    setMode("login")
                    setErrorMessage(null)
                    setSuccessMessage(null)
                  }}
                  className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1 cursor-pointer"
                >
                  <ArrowLeft className="h-3 w-3" /> Back
                </button>
              )}
            </div>
            <CardDescription className="text-xs">
              {mode === "login" && "Sign in to manage compute jobs, price forecasts, and schedule approvals."}
              {mode === "register" &&
                "Enter your work credentials to register. A 6-digit OTP will be dispatched."}
              {mode === "verify-otp" && `Enter the 6-digit code sent to ${email}.`}
            </CardDescription>
          </CardHeader>

          <CardContent className="space-y-4 pt-1">
            {/* Accessible Feedback Banners */}
            {errorMessage && (
              <div
                role="alert"
                aria-live="assertive"
                className="flex items-start gap-2 p-3 text-xs rounded-lg border border-red-500/30 bg-red-500/10 text-red-600 dark:text-red-400"
              >
                <AlertCircle className="h-4 w-4 shrink-0 mt-0.5" />
                <span className="leading-tight">{errorMessage}</span>
              </div>
            )}

            {successMessage && (
              <div
                role="status"
                aria-live="polite"
                className="flex items-start gap-2 p-3 text-xs rounded-lg border border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
              >
                <CheckCircle className="h-4 w-4 shrink-0 mt-0.5" />
                <span className="leading-tight">{successMessage}</span>
              </div>
            )}

            {/* TAB 1: STANDARD SIGN-IN FORM */}
            {mode === "login" && (
              <form onSubmit={handleLogin} className="space-y-3.5">
                <div className="space-y-1.5">
                  <Label htmlFor="login-email" className="text-xs font-semibold">
                    Work Email
                  </Label>
                  <div className="relative">
                    <Mail className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground pointer-events-none" />
                    <Input
                      id="login-email"
                      type="email"
                      placeholder="operator@datacenter.io"
                      autoComplete="username"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      required
                      className="pl-9 h-10 text-sm"
                    />
                  </div>
                </div>

                <div className="space-y-1.5">
                  <div className="flex items-center justify-between">
                    <Label htmlFor="login-password" className="text-xs font-semibold">
                      Password
                    </Label>
                    <button
                      type="button"
                      onClick={() => {
                        setSuccessMessage("For password reset assistance, contact your administrator.")
                      }}
                      className="text-xs text-emerald-600 dark:text-emerald-400 hover:underline cursor-pointer"
                    >
                      Forgot password?
                    </button>
                  </div>
                  <div className="relative">
                    <Lock className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground pointer-events-none" />
                    <Input
                      id="login-password"
                      type={showPassword ? "text" : "password"}
                      placeholder="••••••••"
                      autoComplete="current-password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      required
                      className="pl-9 pr-9 h-10 text-sm"
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword((prev) => !prev)}
                      aria-label={showPassword ? "Hide password" : "Show password"}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground cursor-pointer"
                    >
                      {showPassword ? (
                        <EyeOff className="h-4 w-4" />
                      ) : (
                        <Eye className="h-4 w-4" />
                      )}
                    </button>
                  </div>
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
                      className="text-xs font-medium cursor-pointer text-muted-foreground hover:text-foreground select-none"
                    >
                      Remember this workstation
                    </Label>
                  </div>
                </div>

                <Button
                  type="submit"
                  className="w-full h-10 bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-sm shadow-md shadow-emerald-600/20 transition-all flex items-center justify-center gap-2 cursor-pointer"
                  disabled={isLoading}
                >
                  {isLoading ? (
                    <span className="flex items-center gap-2">
                      <span className="h-4 w-4 border-2 border-white/40 border-t-white rounded-full animate-spin" />
                      Verifying session...
                    </span>
                  ) : (
                    <>
                      <span>Enter Operations Cockpit</span>
                      <ArrowRight className="h-4 w-4" />
                    </>
                  )}
                </Button>

                <div className="text-center pt-1.5">
                  <p className="text-xs text-muted-foreground">
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

            {/* MODE: REGISTER FORM */}
            {mode === "register" && (
              <form onSubmit={handleRegister} className="space-y-3">
                <div className="space-y-1.5">
                  <Label htmlFor="reg-fullname" className="text-xs font-semibold">
                    Full Name
                  </Label>
                  <div className="relative">
                    <UserIcon className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      id="reg-fullname"
                      type="text"
                      placeholder="Full name"
                      value={fullName}
                      onChange={(e) => setFullName(e.target.value)}
                      required
                      className="pl-9 h-10 text-sm"
                    />
                  </div>
                </div>

                <div className="space-y-1.5">
                  <Label htmlFor="reg-email" className="text-xs font-semibold">
                    Work Email
                  </Label>
                  <div className="relative">
                    <Mail className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      id="reg-email"
                      type="email"
                      placeholder="operator@datacenter.io"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      required
                      className="pl-9 h-10 text-sm"
                    />
                  </div>
                </div>

                <div className="space-y-1.5">
                  <Label htmlFor="reg-password" className="text-xs font-semibold">
                    Set Password
                  </Label>
                  <div className="relative">
                    <Lock className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      id="reg-password"
                      type="password"
                      placeholder="At least 6 characters"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      required
                      minLength={6}
                      className="pl-9 h-10 text-sm"
                    />
                  </div>
                </div>

                <Button
                  type="submit"
                  className="w-full h-10 mt-2 bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-sm shadow-md shadow-emerald-600/20 transition-all flex items-center justify-center gap-2 cursor-pointer"
                  disabled={isLoading}
                >
                  {isLoading ? (
                    <span className="flex items-center gap-2">
                      <span className="h-4 w-4 border-2 border-white/40 border-t-white rounded-full animate-spin" />
                      Sending OTP...
                    </span>
                  ) : (
                    <>
                      <span>Send 6-Digit OTP</span>
                      <ArrowRight className="h-4 w-4" />
                    </>
                  )}
                </Button>
              </form>
            )}

            {/* MODE: OTP VERIFICATION */}
            {mode === "verify-otp" && (
              <form onSubmit={handleVerifyOtp} className="space-y-3.5">
                <div className="space-y-2">
                  <Label htmlFor="otp-input" className="text-xs font-semibold">
                    6-Digit Verification Code
                  </Label>
                  <div className="relative">
                    <KeyRound className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      id="otp-input"
                      type="text"
                      maxLength={6}
                      placeholder="123456"
                      value={otpCode}
                      onChange={(e) => setOtpCode(e.target.value.replace(/\D/g, ""))}
                      required
                      className="pl-9 h-11 text-center font-mono text-xl tracking-widest"
                      autoFocus
                    />
                  </div>
                  <p className="text-[11px] text-muted-foreground">
                    A code was sent to <strong className="text-foreground">{email}</strong>. Valid for 10 minutes.
                  </p>
                </div>

                <Button
                  type="submit"
                  className="w-full h-10 bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-sm shadow-md shadow-emerald-600/20 transition-all flex items-center justify-center gap-2 cursor-pointer"
                  disabled={isLoading || otpCode.length !== 6}
                >
                  {isLoading ? (
                    <span className="flex items-center gap-2">
                      <span className="h-4 w-4 border-2 border-white/40 border-t-white rounded-full animate-spin" />
                      Verifying...
                    </span>
                  ) : (
                    <>
                      <span>Verify & Enter Dashboard</span>
                      <ArrowRight className="h-4 w-4" />
                    </>
                  )}
                </Button>

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
                    onClick={() => {
                      setMode("login")
                      setErrorMessage(null)
                      setSuccessMessage(null)
                    }}
                    className="text-muted-foreground hover:text-foreground cursor-pointer"
                  >
                    Change email
                  </button>
                </div>
              </form>
            )}
          </CardContent>

          <CardFooter className="flex items-center justify-between border-t border-border/50 py-3 text-[11px] text-muted-foreground">
            <div className="flex items-center gap-1.5">
              <ShieldCheck className="h-3.5 w-3.5 text-emerald-500" />
              <span>Account authentication</span>
            </div>

          </CardFooter>
        </Card>
      </div>
    </div>
  )
}

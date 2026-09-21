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
  Sparkles,
  Server,
  Cpu,
  BarChart3,
  CheckCircle,
  AlertCircle,
  KeyRound,
  RotateCcw,
  ArrowLeft,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { authApi, setStoredUser } from "@/lib/api"

type AuthMode = "login" | "register" | "verify-otp"

export default function LoginPage() {
  const router = useRouter()

  // State
  const [mode, setMode] = React.useState<AuthMode>("login")
  const [fullName, setFullName] = React.useState("")
  const [email, setEmail] = React.useState("elena.vance@datacenter.io")
  const [password, setPassword] = React.useState("P@ssw0rd2026!")
  const [role, setRole] = React.useState("Data Center Operations Manager")
  const [otpCode, setOtpCode] = React.useState("")

  const [isLoading, setIsLoading] = React.useState(false)
  const [errorMessage, setErrorMessage] = React.useState<string | null>(null)
  const [successMessage, setSuccessMessage] = React.useState<string | null>(null)
  const [resendCooldown, setResendCooldown] = React.useState(0)

  // Resend countdown timer
  React.useEffect(() => {
    if (resendCooldown <= 0) return
    const timer = setInterval(() => {
      setResendCooldown((prev) => prev - 1)
    }, 1000)
    return () => clearInterval(timer)
  }, [resendCooldown])

  // Select demo persona
  const selectPersona = (
    personaEmail: string,
    personaRole: string,
    name: string
  ) => {
    setEmail(personaEmail)
    setPassword("P@ssw0rd2026!")
    setRole(personaRole)
    setFullName(name)
    setErrorMessage(null)
  }

  // Handle Login
  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    setIsLoading(true)
    setErrorMessage(null)
    setSuccessMessage(null)

    try {
      const response = await authApi.login({ email, password })

      if (response.requiresOtp) {
        setSuccessMessage("Account verification required. A new OTP has been sent to your email.")
        setMode("verify-otp")
        setResendCooldown(60)
        return
      }

      if (response.user) {
        setStoredUser(response.user)
      }
      setSuccessMessage("Login successful! Redirecting to dashboard...")
      setTimeout(() => {
        router.push("/")
      }, 500)
    } catch (err: any) {
      setErrorMessage(err.message || "Invalid email or password.")
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
        role,
      })

      setSuccessMessage(response.message || "OTP code sent to your email! Please enter it below.")
      setMode("verify-otp")
      setResendCooldown(60)
    } catch (err: any) {
      setErrorMessage(err.message || "Registration failed. Please check your details.")
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

      if (response.user) {
        setStoredUser(response.user)
      }
      setSuccessMessage("Account verified successfully! Redirecting...")
      setTimeout(() => {
        router.push("/")
      }, 600)
    } catch (err: any) {
      setErrorMessage(err.message || "Invalid or expired OTP code.")
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
    } catch (err: any) {
      setErrorMessage(err.message || "Failed to resend OTP. Please try again.")
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="relative min-h-screen flex items-center justify-center p-4 bg-background selection:bg-emerald-500 selection:text-white overflow-hidden">
      {/* Background Graphic & Ambient Glow */}
      <div className="absolute inset-0 z-0">
        <img
          src="https://images.unsplash.com/photo-1558494949-ef010cbdcc31?w=1600&auto=format&fit=crop&q=80"
          alt="Data Center Infrastructure"
          className="w-full h-full object-cover opacity-15 dark:opacity-10 filter blur-[1px]"
        />
        <div className="absolute inset-0 bg-gradient-to-tr from-background via-background/90 to-background/70" />
        <div className="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[500px] h-[500px] bg-emerald-500/10 rounded-full blur-3xl pointer-events-none" />
        <div className="absolute bottom-10 right-10 w-[400px] h-[400px] bg-teal-500/10 rounded-full blur-3xl pointer-events-none" />
      </div>

      <div className="relative z-10 w-full max-w-md space-y-6">
        {/* Brand Header */}
        <div className="text-center space-y-2">
          <div className="inline-flex h-14 w-14 items-center justify-center rounded-2xl bg-gradient-to-tr from-emerald-600 to-teal-400 text-white shadow-xl shadow-emerald-500/25">
            <Zap className="h-8 w-8 fill-current" />
          </div>
          <h1 className="text-3xl font-extrabold tracking-tight text-foreground">
            GACS Platform
          </h1>
          <p className="text-sm text-muted-foreground">
            Grid Aware Compute Scheduler • ERCOT Dispatch Control
          </p>
        </div>

        {/* Auth Card */}
        <Card className="border border-border/80 bg-card/90 backdrop-blur-xl shadow-2xl">
          <CardHeader className="space-y-1 pb-4">
            <div className="flex items-center justify-between">
              <CardTitle className="text-xl font-bold text-foreground">
                {mode === "login" && "Sign in to your account"}
                {mode === "register" && "Create an account"}
                {mode === "verify-otp" && "Verify Email with OTP"}
              </CardTitle>
              {mode === "verify-otp" && (
                <button
                  type="button"
                  onClick={() => {
                    setMode("login")
                    setErrorMessage(null)
                    setSuccessMessage(null)
                  }}
                  className="text-xs text-muted-foreground hover:text-foreground flex items-center gap-1"
                >
                  <ArrowLeft className="h-3 w-3" /> Back
                </button>
              )}
            </div>
            <CardDescription className="text-xs">
              {mode === "login" && "Access real-time ERCOT price curves, dispatch schedules, and optimization approvals."}
              {mode === "register" && "Enter your details to register. A 6-digit OTP will be sent to your Gmail for verification."}
              {mode === "verify-otp" && `Please enter the 6-digit verification code sent to ${email}.`}
            </CardDescription>
          </CardHeader>

          <CardContent className="space-y-4">
            {/* Feedback Banners */}
            {errorMessage && (
              <div className="flex items-center gap-2 p-3 text-xs rounded-lg border border-red-500/30 bg-red-500/10 text-red-600 dark:text-red-400">
                <AlertCircle className="h-4 w-4 shrink-0" />
                <span>{errorMessage}</span>
              </div>
            )}

            {successMessage && (
              <div className="flex items-center gap-2 p-3 text-xs rounded-lg border border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400">
                <CheckCircle className="h-4 w-4 shrink-0" />
                <span>{successMessage}</span>
              </div>
            )}

            {/* Mode 1: LOGIN FORM */}
            {mode === "login" && (
              <form onSubmit={handleLogin} className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="login-email" className="text-xs font-semibold">
                    Work Email
                  </Label>
                  <div className="relative">
                    <Mail className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      id="login-email"
                      type="email"
                      placeholder="name@organization.com"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      required
                      className="pl-9 h-10 text-sm"
                    />
                  </div>
                </div>

                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <Label htmlFor="login-password" className="text-xs font-semibold">
                      Password
                    </Label>
                    <a
                      href="#forgot"
                      onClick={(e) => {
                        e.preventDefault()
                        alert("Password reset instructions sent to registered system administrator.")
                      }}
                      className="text-xs text-emerald-600 dark:text-emerald-400 hover:underline"
                    >
                      Forgot password?
                    </a>
                  </div>
                  <div className="relative">
                    <Lock className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                    <Input
                      id="login-password"
                      type="password"
                      placeholder="••••••••"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      required
                      className="pl-9 h-10 text-sm"
                    />
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
                      Authenticating...
                    </span>
                  ) : (
                    <>
                      <span>Enter GACS Dashboard</span>
                      <ArrowRight className="h-4 w-4" />
                    </>
                  )}
                </Button>

                {/* Switch to Register */}
                <div className="text-center pt-1">
                  <p className="text-xs text-muted-foreground">
                    Don't have an account?{" "}
                    <button
                      type="button"
                      onClick={() => {
                        setMode("register")
                        setErrorMessage(null)
                        setSuccessMessage(null)
                      }}
                      className="text-emerald-600 dark:text-emerald-400 font-semibold hover:underline cursor-pointer"
                    >
                      Sign up with OTP
                    </button>
                  </p>
                </div>
              </form>
            )}

            {/* Mode 2: REGISTER FORM */}
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
                      placeholder="e.g. Elena Vance"
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
                      placeholder="name@organization.com"
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      required
                      className="pl-9 h-10 text-sm"
                    />
                  </div>
                </div>

                <div className="space-y-1.5">
                  <Label htmlFor="reg-password" className="text-xs font-semibold">
                    Password
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

                <div className="space-y-1.5">
                  <Label htmlFor="reg-role" className="text-xs font-semibold">
                    Organization Role
                  </Label>
                  <select
                    id="reg-role"
                    value={role}
                    onChange={(e) => setRole(e.target.value)}
                    className="w-full h-10 px-3 text-sm rounded-md border border-input bg-background text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500"
                  >
                    <option value="Data Center Operations Manager">Data Center Operations Manager</option>
                    <option value="HPC Platform Administrator">HPC Platform Administrator</option>
                    <option value="Energy & Finance Analyst">Energy & Finance Analyst</option>
                  </select>
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
                      <span>Send Verification Code</span>
                      <ArrowRight className="h-4 w-4" />
                    </>
                  )}
                </Button>

                {/* Switch to Login */}
                <div className="text-center pt-1">
                  <p className="text-xs text-muted-foreground">
                    Already have an account?{" "}
                    <button
                      type="button"
                      onClick={() => {
                        setMode("login")
                        setErrorMessage(null)
                        setSuccessMessage(null)
                      }}
                      className="text-emerald-600 dark:text-emerald-400 font-semibold hover:underline cursor-pointer"
                    >
                      Sign in
                    </button>
                  </p>
                </div>
              </form>
            )}

            {/* Mode 3: OTP VERIFICATION FORM */}
            {mode === "verify-otp" && (
              <form onSubmit={handleVerifyOtp} className="space-y-4">
                <div className="space-y-2">
                  <Label htmlFor="otp-input" className="text-xs font-semibold">
                    6-Digit OTP Code
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

                {/* Resend OTP */}
                <div className="flex items-center justify-between text-xs pt-1">
                  <button
                    type="button"
                    onClick={handleResendOtp}
                    disabled={resendCooldown > 0 || isLoading}
                    className="text-emerald-600 dark:text-emerald-400 font-semibold hover:underline flex items-center gap-1 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
                  >
                    <RotateCcw className="h-3 w-3" />
                    {resendCooldown > 0 ? `Resend code in ${resendCooldown}s` : "Resend code"}
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

            {/* Quick Demo Personas (Only in Login Mode) */}
            {mode === "login" && (
              <>
                <div className="relative my-4">
                  <div className="absolute inset-0 flex items-center">
                    <div className="w-full border-t border-border" />
                  </div>
                  <div className="relative flex justify-center text-xs uppercase">
                    <span className="bg-card px-2 text-muted-foreground font-medium text-[11px]">
                      Or Select Demo Persona
                    </span>
                  </div>
                </div>

                <div className="space-y-1.5">
                  <div className="grid grid-cols-1 gap-2">
                    <button
                      type="button"
                      onClick={() =>
                        selectPersona("elena.vance@datacenter.io", "Data Center Operations Manager", "Elena Vance")
                      }
                      className={`flex items-center justify-between p-2.5 rounded-lg border text-left text-xs transition-all cursor-pointer ${
                        email === "elena.vance@datacenter.io"
                          ? "border-emerald-500 bg-emerald-500/10 text-foreground font-semibold"
                          : "border-border hover:bg-accent text-muted-foreground"
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        <Server className="h-4 w-4 text-emerald-500" />
                        <div>
                          <div className="font-semibold text-foreground">Elena Vance (Operations Manager)</div>
                          <div className="text-[10px] text-muted-foreground">Approve schedules & power-caps</div>
                        </div>
                      </div>
                      {email === "elena.vance@datacenter.io" && (
                        <CheckCircle className="h-4 w-4 text-emerald-500" />
                      )}
                    </button>

                    <button
                      type="button"
                      onClick={() =>
                        selectPersona("marcus.chen@hpc-grid.org", "HPC Platform Administrator", "Marcus Chen")
                      }
                      className={`flex items-center justify-between p-2.5 rounded-lg border text-left text-xs transition-all cursor-pointer ${
                        email === "marcus.chen@hpc-grid.org"
                          ? "border-emerald-500 bg-emerald-500/10 text-foreground font-semibold"
                          : "border-border hover:bg-accent text-muted-foreground"
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        <Cpu className="h-4 w-4 text-teal-500" />
                        <div>
                          <div className="font-semibold text-foreground">Marcus Chen (HPC Administrator)</div>
                          <div className="text-[10px] text-muted-foreground">Manage ML training runs & SLAs</div>
                        </div>
                      </div>
                      {email === "marcus.chen@hpc-grid.org" && (
                        <CheckCircle className="h-4 w-4 text-emerald-500" />
                      )}
                    </button>

                    <button
                      type="button"
                      onClick={() =>
                        selectPersona("sarah.jenkins@energyrisk.com", "Energy & Finance Analyst", "Sarah Jenkins")
                      }
                      className={`flex items-center justify-between p-2.5 rounded-lg border text-left text-xs transition-all cursor-pointer ${
                        email === "sarah.jenkins@energyrisk.com"
                          ? "border-emerald-500 bg-emerald-500/10 text-foreground font-semibold"
                          : "border-border hover:bg-accent text-muted-foreground"
                      }`}
                    >
                      <div className="flex items-center gap-2">
                        <BarChart3 className="h-4 w-4 text-blue-500" />
                        <div>
                          <div className="font-semibold text-foreground">Sarah Jenkins (Energy Analyst)</div>
                          <div className="text-[10px] text-muted-foreground">Backtesting & realized savings audit</div>
                        </div>
                      </div>
                      {email === "sarah.jenkins@energyrisk.com" && (
                        <CheckCircle className="h-4 w-4 text-emerald-500" />
                      )}
                    </button>
                  </div>
                </div>
              </>
            )}
          </CardContent>

          <CardFooter className="flex items-center justify-center border-t border-border/50 py-3 text-[11px] text-muted-foreground gap-2">
            <ShieldCheck className="h-3.5 w-3.5 text-emerald-500" />
            <span>Connected to Spring Boot • NERC-CIP & SOC2 Compliant</span>
          </CardFooter>
        </Card>
      </div>
    </div>
  )
}

"use client"

import * as React from "react"
import { useRouter } from "next/navigation"
import {
  Zap,
  ShieldCheck,
  Lock,
  Mail,
  ArrowRight,
  Sparkles,
  Server,
  Cpu,
  BarChart3,
  CheckCircle,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"

export default function LoginPage() {
  const router = useRouter()
  const [email, setEmail] = React.useState("elena.vance@datacenter.io")
  const [password, setPassword] = React.useState("••••••••••••")
  const [role, setRole] = React.useState("Data Center Operations Manager")
  const [isLoading, setIsLoading] = React.useState(false)

  const handleLogin = (e: React.FormEvent) => {
    e.preventDefault()
    setIsLoading(true)
    setTimeout(() => {
      router.push("/")
    }, 600)
  }

  const selectPersona = (
    personaEmail: string,
    personaRole: string
  ) => {
    setEmail(personaEmail)
    setPassword("P@ssw0rd2026!")
    setRole(personaRole)
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

        {/* Login Card */}
        <Card className="border border-border/80 bg-card/90 backdrop-blur-xl shadow-2xl">
          <CardHeader className="space-y-1 pb-4">
            <CardTitle className="text-xl font-bold text-foreground">
              Sign in to your account
            </CardTitle>
            <CardDescription className="text-xs">
              Access real-time ERCOT price curves, dispatch schedules, and optimization approvals.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleLogin} className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="email" className="text-xs font-semibold">
                  Work Email
                </Label>
                <div className="relative">
                  <Mail className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                  <Input
                    id="email"
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
                  <Label htmlFor="password" className="text-xs font-semibold">
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
                    id="password"
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
                className="w-full h-10 bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-sm shadow-md shadow-emerald-600/20 transition-all flex items-center justify-center gap-2"
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
            </form>

            <div className="relative my-5">
              <div className="absolute inset-0 flex items-center">
                <div className="w-full border-t border-border" />
              </div>
              <div className="relative flex justify-center text-xs uppercase">
                <span className="bg-card px-2 text-muted-foreground font-medium">
                  Or One-Click Demo Login
                </span>
              </div>
            </div>

            {/* Quick Demo Personas (PRD Alignment) */}
            <div className="space-y-2">
              <p className="text-[11px] text-muted-foreground text-center">
                Select a persona to test role-specific optimization workflows:
              </p>
              <div className="grid grid-cols-1 gap-2">
                <button
                  type="button"
                  onClick={() =>
                    selectPersona("elena.vance@datacenter.io", "Data Center Operations Manager")
                  }
                  className={`flex items-center justify-between p-2.5 rounded-lg border text-left text-xs transition-all ${
                    role === "Data Center Operations Manager"
                      ? "border-emerald-500 bg-emerald-500/10 text-foreground font-semibold"
                      : "border-border hover:bg-accent text-muted-foreground"
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <Server className="h-4 w-4 text-emerald-500" />
                    <div>
                      <div className="font-semibold text-foreground">Operations Manager</div>
                      <div className="text-[10px] text-muted-foreground">Approve schedules & power-caps</div>
                    </div>
                  </div>
                  {role === "Data Center Operations Manager" && (
                    <CheckCircle className="h-4 w-4 text-emerald-500" />
                  )}
                </button>

                <button
                  type="button"
                  onClick={() =>
                    selectPersona("marcus.chen@hpc-grid.org", "HPC Platform Administrator")
                  }
                  className={`flex items-center justify-between p-2.5 rounded-lg border text-left text-xs transition-all ${
                    role === "HPC Platform Administrator"
                      ? "border-emerald-500 bg-emerald-500/10 text-foreground font-semibold"
                      : "border-border hover:bg-accent text-muted-foreground"
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <Cpu className="h-4 w-4 text-teal-500" />
                    <div>
                      <div className="font-semibold text-foreground">HPC Administrator</div>
                      <div className="text-[10px] text-muted-foreground">Manage ML training runs & SLAs</div>
                    </div>
                  </div>
                  {role === "HPC Platform Administrator" && (
                    <CheckCircle className="h-4 w-4 text-emerald-500" />
                  )}
                </button>

                <button
                  type="button"
                  onClick={() =>
                    selectPersona("sarah.jenkins@energyrisk.com", "Energy & Finance Analyst")
                  }
                  className={`flex items-center justify-between p-2.5 rounded-lg border text-left text-xs transition-all ${
                    role === "Energy & Finance Analyst"
                      ? "border-emerald-500 bg-emerald-500/10 text-foreground font-semibold"
                      : "border-border hover:bg-accent text-muted-foreground"
                  }`}
                >
                  <div className="flex items-center gap-2">
                    <BarChart3 className="h-4 w-4 text-blue-500" />
                    <div>
                      <div className="font-semibold text-foreground">Energy & Finance Analyst</div>
                      <div className="text-[10px] text-muted-foreground">Backtesting & realized savings audit</div>
                    </div>
                  </div>
                  {role === "Energy & Finance Analyst" && (
                    <CheckCircle className="h-4 w-4 text-emerald-500" />
                  )}
                </button>
              </div>
            </div>
          </CardContent>
          <CardFooter className="flex items-center justify-center border-t border-border/50 py-3 text-[11px] text-muted-foreground gap-2">
            <ShieldCheck className="h-3.5 w-3.5 text-emerald-500" />
            <span>NERC-CIP & SOC2 Type II Certified Session</span>
          </CardFooter>
        </Card>
      </div>
    </div>
  )
}

"use client"

import * as React from "react"
import { useRouter } from "next/navigation"
import { AlertCircle, CheckCircle2, LogOut } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Segmented } from "@/components/ui/segmented"
import { Skeleton } from "@/components/ui/skeleton"
import { authApi, removeStoredUser, signOut, USER_UPDATED_EVENT, type User } from "@/lib/api"
import { PASSWORD_HINT, passwordError } from "@/lib/password-policy"
import { readTheme, saveTheme, subscribeTheme, type ThemeChoice } from "@/lib/theme"

type Feedback = { ok: boolean; text: string } | null

const THEME_OPTIONS = [
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
  { value: "system", label: "System" },
] as const

const errorText = (err: unknown) => (err instanceof Error ? err.message : "Please try again.")

function FeedbackLine({ feedback }: { feedback: Feedback }) {
  if (!feedback) return null
  const Icon = feedback.ok ? CheckCircle2 : AlertCircle
  return (
    <p
      role={feedback.ok ? "status" : "alert"}
      className={`flex min-w-0 items-start gap-2 text-sm leading-5 ${feedback.ok ? "text-success-foreground" : "text-destructive-foreground"}`}
    >
      <Icon className="mt-0.5 h-4 w-4 shrink-0" />
      <span className="min-w-0 break-words">{feedback.text}</span>
    </p>
  )
}

function SettingsCard({ title, description, children }: { title: string; description: string; children: React.ReactNode }) {
  return (
    <Card className="min-w-0">
      <CardHeader className="space-y-1 p-4 pb-0 sm:p-5 sm:pb-0">
        <CardTitle className="text-sm font-semibold leading-5">{title}</CardTitle>
        <CardDescription className="text-xs leading-4">{description}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4 p-4 sm:p-5">{children}</CardContent>
    </Card>
  )
}

function Field({ id, label, hint, children }: { id: string; label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="space-y-2 text-sm">
      <label htmlFor={id} className="block font-medium">{label}</label>
      {children}
      {hint && <p className="text-xs leading-4 text-muted-foreground">{hint}</p>}
    </div>
  )
}

export default function SettingsPage() {
  const router = useRouter()
  const [user, setUser] = React.useState<User | null>(null)
  const [loadError, setLoadError] = React.useState("")
  const [reload, setReload] = React.useState(0)

  const [name, setName] = React.useState("")
  const [savingName, setSavingName] = React.useState(false)
  const [profileFeedback, setProfileFeedback] = React.useState<Feedback>(null)

  const [codeSent, setCodeSent] = React.useState(false)
  const [code, setCode] = React.useState("")
  const [password, setPassword] = React.useState("")
  const [confirmPassword, setConfirmPassword] = React.useState("")
  const [passwordBusy, setPasswordBusy] = React.useState(false)
  const [passwordFeedback, setPasswordFeedback] = React.useState<Feedback>(null)

  const [tourFeedback, setTourFeedback] = React.useState<Feedback>(null)
  const theme = React.useSyncExternalStore(subscribeTheme, readTheme, () => "dark" as const)

  React.useEffect(() => {
    let active = true
    authApi.getMe()
      .then((r) => {
        if (!active) return
        if (!r.success || !r.data) throw new Error(r.message || "Could not load your profile.")
        setUser(r.data)
        setName(r.data.fullName ?? "")
      })
      .catch((err) => { if (active) setLoadError(errorText(err)) })
    return () => { active = false }
  }, [reload])

  const saveName = async (event: React.FormEvent) => {
    event.preventDefault()
    setSavingName(true)
    setProfileFeedback(null)
    try {
      const r = await authApi.updateMe({ fullName: name.trim() })
      if (!r.success || !r.data) throw new Error(r.message || "Could not save your name.")
      setUser(r.data)
      setName(r.data.fullName ?? "")
      window.dispatchEvent(new CustomEvent(USER_UPDATED_EVENT, { detail: r.data }))
      setProfileFeedback({ ok: true, text: "Name saved." })
    } catch (err) {
      setProfileFeedback({ ok: false, text: errorText(err) })
    } finally {
      setSavingName(false)
    }
  }

  const sendCode = async () => {
    if (!user) return
    setPasswordBusy(true)
    setPasswordFeedback(null)
    try {
      const r = await authApi.forgotPassword({ email: user.email })
      if (!r.success) throw new Error(r.message || "Could not send a code.")
      setCodeSent(true)
      setCode("")
      setPasswordFeedback({ ok: true, text: r.message })
    } catch (err) {
      setPasswordFeedback({ ok: false, text: errorText(err) })
    } finally {
      setPasswordBusy(false)
    }
  }

  const updatePassword = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!user) return
    const problem = !code.trim() ? "Enter the recovery code from the email."
      : passwordError(password) ?? (password !== confirmPassword ? "The passwords do not match." : null)
    if (problem) {
      setPasswordFeedback({ ok: false, text: problem })
      return
    }
    setPasswordBusy(true)
    setPasswordFeedback(null)
    try {
      const r = await authApi.resetPassword({ email: user.email, token: code.trim(), password })
      if (!r.success) throw new Error(r.message || "Could not update the password.")
      // The server ends every session on a password change, so sign in again.
      removeStoredUser()
      router.push("/login")
    } catch (err) {
      setPasswordFeedback({ ok: false, text: errorText(err) })
      setPasswordBusy(false)
    }
  }

  const restartTour = () => {
    if (!user) return
    try {
      window.localStorage.removeItem(`gacs-onboarding-complete:${user.email.toLowerCase()}`)
      setTourFeedback({ ok: true, text: "The tour opens the next time you open GACS." })
    } catch {
      setTourFeedback({ ok: false, text: "Could not reset the tour in this browser." })
    }
  }

  return (
    <div className="max-w-2xl space-y-6">
      <h1 className="sr-only">Settings</h1>

      {loadError ? (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertDescription className="flex min-w-0 flex-wrap items-center justify-between gap-3">
            <span className="min-w-0 break-words">{loadError}</span>
            <Button variant="outline" size="sm" className="shrink-0" onClick={() => { setLoadError(""); setReload((n) => n + 1) }}>Retry</Button>
          </AlertDescription>
        </Alert>
      ) : !user ? (
        <div role="status" aria-label="Loading settings" className="space-y-6">
          <Skeleton className="h-56 w-full rounded-lg" />
          <Skeleton className="h-32 w-full rounded-lg" />
        </div>
      ) : (
        <>
          <SettingsCard title="Profile" description="Your name as other users see it.">
            <form onSubmit={saveName} className="space-y-4">
              <Field id="settings-name" label="Full name">
                <Input id="settings-name" autoComplete="name" maxLength={100} value={name} onChange={(e) => setName(e.target.value)} />
              </Field>
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm leading-5">
                <dt className="text-muted-foreground">Email</dt>
                <dd className="min-w-0 break-words">{user.email}</dd>
                <dt className="text-muted-foreground">Role</dt>
                <dd>{user.role === "ROLE_ADMIN" ? "Administrator" : "User"}</dd>
              </dl>
              <FeedbackLine feedback={profileFeedback} />
              <Button type="submit" disabled={savingName || !name.trim() || name.trim() === (user.fullName ?? "")}>
                {savingName ? "Saving…" : "Save"}
              </Button>
            </form>
          </SettingsCard>

          <SettingsCard title="Password" description="We email you a code to set a new password.">
            {codeSent ? (
              <form onSubmit={updatePassword} className="space-y-4">
                <Field id="settings-code" label="Recovery code">
                  <Input id="settings-code" autoComplete="one-time-code" value={code} onChange={(e) => setCode(e.target.value)} />
                </Field>
                <Field id="settings-password" label="New password" hint={PASSWORD_HINT}>
                  <Input id="settings-password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
                </Field>
                <Field id="settings-confirm" label="Confirm password">
                  <Input id="settings-confirm" type="password" autoComplete="new-password" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} />
                </Field>
                <FeedbackLine feedback={passwordFeedback} />
                <div className="flex flex-wrap gap-2">
                  <Button type="submit" variant="outline" disabled={passwordBusy}>Update password</Button>
                  <Button type="button" variant="ghost" disabled={passwordBusy} onClick={() => void sendCode()}>Send a new code</Button>
                </div>
              </form>
            ) : (
              <>
                <FeedbackLine feedback={passwordFeedback} />
                <Button variant="outline" disabled={passwordBusy} onClick={() => void sendCode()}>Send code</Button>
              </>
            )}
          </SettingsCard>
        </>
      )}

      <SettingsCard title="Appearance" description="Choose how GACS looks on this device.">
        <Segmented<ThemeChoice> label="Theme" value={theme} onChange={saveTheme} options={THEME_OPTIONS} />
      </SettingsCard>

      {user && (
        <SettingsCard title="Quick tour" description="Show the tour again the next time you open GACS.">
          <FeedbackLine feedback={tourFeedback} />
          <Button variant="outline" onClick={restartTour}>Restart tour</Button>
        </SettingsCard>
      )}

      <Button variant="outline" onClick={() => void signOut(router)}>
        <LogOut className="h-4 w-4" />
        Sign out
      </Button>
    </div>
  )
}

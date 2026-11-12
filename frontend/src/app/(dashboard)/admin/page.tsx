"use client"

import * as React from "react"
import { useRouter } from "next/navigation"
import Link from "next/link"
import { ShieldCheck, ShieldAlert, Users, UserCheck, Search, RefreshCw, AlertCircle, CheckCircle2, Shield, Clock } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Skeleton } from "@/components/ui/skeleton"
import { Segmented } from "@/components/ui/segmented"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { StatCard } from "@/components/home/StatCard"
import { adminApi, authApi, removeStoredUser, signOut, type User } from "@/lib/api"
import { FacilityConfigCard } from "@/components/admin/FacilityConfigCard"
import { ApprovalSettingsCard } from "@/components/admin/ApprovalSettingsCard"

export default function AdminPage() {
  const router = useRouter()
  const [currentUser, setCurrentUser] = React.useState<User | null>(null)
  const [isAuthorized, setIsAuthorized] = React.useState<boolean | null>(null)

  const [users, setUsers] = React.useState<User[]>([])
  const [isLoading, setIsLoading] = React.useState(true)
  const [searchQuery, setSearchQuery] = React.useState("")
  const [roleFilter, setRoleFilter] = React.useState<"ALL" | "ROLE_ADMIN" | "ROLE_USER">("ALL")
  const [actionLoadingId, setActionLoadingId] = React.useState<number | null>(null)
  const [feedback, setFeedback] = React.useState<{ type: "success" | "error"; message: string } | null>(null)
  const [loadError, setLoadError] = React.useState("")

  // Resolve administrator access from the authenticated API response.
  React.useEffect(() => {
    let active = true
    authApi.getMe().then(response => {
      if (!active) return
      if (!response.success || !response.data) throw new Error("Session unavailable")
      setCurrentUser(response.data)
      const authorized = response.data.role === "ROLE_ADMIN"
      setIsAuthorized(authorized)
      if (!authorized) setIsLoading(false)
    }).catch(() => {
      if (!active) return
      removeStoredUser()
      router.replace("/login")
    })
    return () => { active = false }
  }, [router])

  const [usersReload, setUsersReload] = React.useState(0)
  React.useEffect(() => {
    if (!isAuthorized) return
    let active = true
    adminApi.getAllUsers().then(response => {
      if (!active) return
      if (!response.success || !response.data) throw new Error(response.message || "Could not load registered users")
      setUsers(response.data)
    }).catch(error => {
      if (active) setLoadError(error instanceof Error ? error.message : "Could not load registered users")
    }).finally(() => { if (active) setIsLoading(false) })
    return () => { active = false }
  }, [isAuthorized, usersReload])

  const reloadUsers = () => {
    setIsLoading(true)
    setFeedback(null)
    setLoadError("")
    setUsersReload(key => key + 1)
  }

  // Handle Role Change
  const handleToggleRole = async (targetUser: User) => {
    if (targetUser.email.toLowerCase() === currentUser?.email?.toLowerCase() && targetUser.role === "ROLE_ADMIN") {
      setFeedback({
        type: "error",
        message: "You cannot demote your own administrator account.",
      })
      return
    }

    const newRole: "ROLE_USER" | "ROLE_ADMIN" =
      targetUser.role === "ROLE_ADMIN" ? "ROLE_USER" : "ROLE_ADMIN"

    const confirmText =
      newRole === "ROLE_ADMIN"
        ? `Grant administrator privileges to ${targetUser.fullName || targetUser.email}?`
        : `Demote ${targetUser.fullName || targetUser.email} to standard user?`

    if (!window.confirm(confirmText)) return

    setActionLoadingId(targetUser.id)
    setFeedback(null)

    try {
      const response = await adminApi.updateUserRole(targetUser.id, newRole)
      if (response.data) {
        setUsers((prev) =>
          prev.map((u) => (u.id === targetUser.id ? response.data! : u))
        )
        setFeedback({
          type: "success",
          message: `${targetUser.fullName || targetUser.email} is now ${newRole === "ROLE_ADMIN" ? "an administrator" : "a standard user"}.`,
        })
      }
    } catch (err: unknown) {
      setFeedback({
        type: "error",
        message: (err instanceof Error ? err.message : "") || "Could not update user role.",
      })
    } finally {
      setActionLoadingId(null)
    }
  }

  const handleLogout = () => signOut(router)

  // Filtered Users
  const filteredUsers = users.filter((u) => {
    const matchesSearch =
      u.email.toLowerCase().includes(searchQuery.toLowerCase()) ||
      (u.fullName && u.fullName.toLowerCase().includes(searchQuery.toLowerCase()))

    const matchesRole =
      roleFilter === "ALL"
        ? true
        : roleFilter === "ROLE_ADMIN"
        ? u.role === "ROLE_ADMIN"
        : u.role !== "ROLE_ADMIN"

    return matchesSearch && matchesRole
  })

  // Metrics
  const totalUsers = users.length
  const totalAdmins = users.filter((u) => u.role === "ROLE_ADMIN").length
  const totalOperators = users.filter((u) => u.role !== "ROLE_ADMIN").length
  const totalVerified = users.filter((u) => Boolean(u.isVerified ?? u.verified)).length

  // Loading / Auth Verification screen
  if (isAuthorized === null) {
    return (
      <div className="space-y-6" role="status" aria-label="Checking access">
        <h1 className="sr-only">Admin</h1>
        <div className="grid min-w-0 grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-28 w-full" />)}
        </div>
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  // Unauthorized screen
  if (isAuthorized === false) {
    return (
      <div className="flex min-h-[50vh] items-center justify-center">
        <h1 className="sr-only">Admin</h1>
        <Card className="w-full max-w-md">
          <CardContent className="space-y-4 p-4 sm:p-5">
            <div className="space-y-1">
              <h2 className="flex items-center gap-2 text-lg font-semibold leading-7">
                <ShieldAlert className="size-5 shrink-0 text-destructive-foreground" aria-hidden="true" />
                Administrators only
              </h2>
              <p className="break-words text-sm leading-5 text-muted-foreground">
                Your account (<span className="font-medium text-foreground">{currentUser?.email}</span>) does not have administrator access.
              </p>
            </div>
            <div className="flex flex-col gap-2 sm:flex-row-reverse">
              <Button asChild>
                <Link href="/home">Back to Home</Link>
              </Button>
              <Button variant="outline" onClick={handleLogout}>
                Sign out
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div id="admin-root" className="space-y-6">
      {/* No visible title. The heading stays for screen readers. */}
      <h1 className="sr-only">Admin</h1>

      {loadError && (
        <Alert variant="destructive" className="flex items-center justify-between gap-3">
          <AlertDescription className="min-w-0 break-words">{loadError}</AlertDescription>
          <Button variant="outline" size="sm" className="shrink-0" onClick={reloadUsers}>Retry</Button>
        </Alert>
      )}

      {feedback && (
        <Alert
          variant={feedback.type === "success" ? "success" : "destructive"}
          role={feedback.type === "success" ? "status" : "alert"}
          className="flex items-center justify-between gap-3"
        >
          <div className="flex min-w-0 items-center gap-2">
            {feedback.type === "success" ? (
              <CheckCircle2 className="size-4 shrink-0" aria-hidden="true" />
            ) : (
              <AlertCircle className="size-4 shrink-0" aria-hidden="true" />
            )}
            <AlertDescription className="min-w-0 break-words">{feedback.message}</AlertDescription>
          </div>
          <div className="flex shrink-0 items-center gap-2">
            {feedback.type === "error" && feedback.message.includes("session expired") && (
              <Button asChild variant="outline" size="sm">
                <Link href="/login">Sign in</Link>
              </Button>
            )}
            <Button variant="ghost" size="sm" onClick={() => setFeedback(null)}>
              Dismiss
            </Button>
          </div>
        </Alert>
      )}

      <FacilityConfigCard />

      <div className="grid min-w-0 grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label={<><Users className="size-3.5 shrink-0" aria-hidden="true" />Users</>} value={String(totalUsers)} supporting="Registered accounts" loading={isLoading} error={!!loadError} />
        <StatCard label={<><ShieldCheck className="size-3.5 shrink-0" aria-hidden="true" />Administrators</>} value={String(totalAdmins)} supporting="Can change roles and settings" loading={isLoading} error={!!loadError} />
        <StatCard label={<><Shield className="size-3.5 shrink-0" aria-hidden="true" />Standard users</>} value={String(totalOperators)} supporting="Can submit jobs" loading={isLoading} error={!!loadError} />
        <StatCard
          label={<><UserCheck className="size-3.5 shrink-0" aria-hidden="true" />Verified accounts</>}
          value={String(totalVerified)}
          unit={`of ${totalUsers}`}
          supporting={totalVerified === totalUsers ? "All accounts verified" : `${totalUsers - totalVerified} waiting for a code`}
          loading={isLoading}
          error={!!loadError}
        />
      </div>

      <ApprovalSettingsCard />

      {/* User Management Section */}
      <Card className="min-w-0">
          <CardHeader className="flex flex-col gap-3 space-y-0 border-b p-4 sm:p-5 lg:flex-row lg:items-center lg:justify-between">
            <div className="min-w-0 flex-1 space-y-1">
              <CardTitle className="text-sm font-semibold leading-5">Users and roles</CardTitle>
              <CardDescription className="text-xs leading-4">
                Only an administrator can give or remove administrator access.
              </CardDescription>
            </div>

            <div className="flex min-w-0 flex-wrap items-center gap-2 lg:shrink-0">
              <div className="relative w-full sm:w-60">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
                <Input
                  placeholder="Search name or email"
                  aria-label="Search name or email"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="h-9 pl-8"
                />
              </div>
              <div className="flex items-center gap-1">
                <Segmented
                  label="Roles to show"
                  value={roleFilter}
                  onChange={setRoleFilter}
                  options={[
                    { value: "ALL", label: `All (${users.length})` },
                    { value: "ROLE_ADMIN", label: `Admins (${totalAdmins})` },
                    { value: "ROLE_USER", label: `Users (${totalOperators})` },
                  ]}
                />
                <Button variant="ghost" size="icon" className="size-9" aria-label="Refresh users" title="Refresh users" disabled={isLoading} onClick={reloadUsers}>
                  <RefreshCw className={`size-4 ${isLoading ? "animate-spin" : ""}`} />
                </Button>
              </div>
            </div>
          </CardHeader>

          <CardContent className="p-0">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="bg-muted/40 text-muted-foreground border-b border-border text-xs">
                  <tr>
                    <th className="py-3 px-4 font-medium">User Details</th>
                    <th className="py-3 px-4 font-medium">Status</th>
                    <th className="py-3 px-4 font-medium">Role Privilege</th>
                    <th className="py-3 px-4 font-medium text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border/60">
                  {isLoading ? (
                    <tr>
                      <td colSpan={4} className="py-10 text-center text-muted-foreground">
                        <div className="flex items-center justify-center gap-2">
                          <RefreshCw className="h-4 w-4 animate-spin text-emerald-500" />
                          <span>Loading platform users...</span>
                        </div>
                      </td>
                    </tr>
                  ) : filteredUsers.length === 0 ? (
                    <tr>
                      <td colSpan={4} className="py-10 text-center text-muted-foreground">
                        No users matching the current filter.
                      </td>
                    </tr>
                  ) : (
                    filteredUsers.map((u) => {
                      const isSelf =
                        u.email.toLowerCase() === currentUser?.email?.toLowerCase()
                      const isAdmin = u.role === "ROLE_ADMIN"

                      return (
                        <tr
                          key={u.id}
                          className={`hover:bg-muted/30 transition-colors ${
                            isSelf ? "bg-emerald-500/[0.03]" : ""
                          }`}
                        >
                          <td className="py-3 px-4">
                            <div className="flex items-center gap-3">
                              <div
                                className={`h-8 w-8 rounded-full flex items-center justify-center font-semibold text-xs ${
                                  isAdmin
                                    ? "bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30"
                                    : "bg-muted text-muted-foreground border border-border"
                                }`}
                              >
                                {(u.fullName || u.email)[0].toUpperCase()}
                              </div>
                              <div>
                                <div className="font-medium text-foreground flex items-center gap-1.5">
                                  {u.fullName || u.email.split("@")[0]}
                                  {isSelf && (
                                    <span className="text-xs bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 px-1.5 py-0.2 rounded font-medium">
                                      You
                                    </span>
                                  )}
                                </div>
                                <div className="text-xs leading-4 text-muted-foreground">
                                  {u.email}
                                </div>
                              </div>
                            </div>
                          </td>

                          <td className="py-3 px-4">
                            {Boolean(u.isVerified ?? u.verified) ? (
                              <span className="inline-flex items-center gap-1 text-xs font-medium text-emerald-600 dark:text-emerald-400">
                                <CheckCircle2 className="h-3.5 w-3.5" />
                                Verified
                              </span>
                            ) : (
                              <span className="inline-flex items-center gap-1 text-xs font-medium text-amber-500">
                                <Clock className="h-3.5 w-3.5" />
                                Pending OTP
                              </span>
                            )}
                          </td>

                          <td className="py-3 px-4">
                            {isAdmin ? (
                              <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-semibold bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30">
                                <ShieldCheck className="h-3.5 w-3.5" />
                                ROLE_ADMIN
                              </span>
                            ) : (
                              <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-semibold bg-muted text-muted-foreground border border-border">
                                <Shield className="h-3.5 w-3.5" />
                                ROLE_USER
                              </span>
                            )}
                          </td>

                          <td className="py-3 px-4 text-right">
                            {isAdmin ? (
                              <Button
                                variant="outline"
                                size="sm"
                                disabled={isSelf || actionLoadingId === u.id}
                                onClick={() => handleToggleRole(u)}
                                className="h-7 text-xs border-amber-500/30 text-amber-600 dark:text-amber-400 hover:bg-amber-500/10 cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
                                title={
                                  isSelf
                                    ? "Cannot demote your own account"
                                    : "Demote to standard user"
                                }
                              >
                                {actionLoadingId === u.id ? (
                                  <RefreshCw className="h-3 w-3 animate-spin" />
                                ) : (
                                  "Demote to User"
                                )}
                              </Button>
                            ) : (
                              <Button
                                variant="outline"
                                size="sm"
                                disabled={actionLoadingId === u.id}
                                onClick={() => handleToggleRole(u)}
                                className="h-7 text-xs border-emerald-500/30 text-emerald-600 dark:text-emerald-400 hover:bg-emerald-500/10 cursor-pointer"
                                title="Grant Administrator privileges"
                              >
                                {actionLoadingId === u.id ? (
                                  <RefreshCw className="h-3 w-3 animate-spin" />
                                ) : (
                                  "Make Admin"
                                )}
                              </Button>
                            )}
                          </td>
                        </tr>
                      )
                    })
                  )}
                </tbody>
              </table>
            </div>
          </CardContent>
        </Card>
    </div>
  )
}

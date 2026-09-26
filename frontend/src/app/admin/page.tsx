"use client"

import * as React from "react"
import { useRouter } from "next/navigation"
import Link from "next/link"
import {
  ShieldCheck,
  ShieldAlert,
  Users,
  UserCheck,
  UserX,
  Search,
  ArrowLeft,
  RefreshCw,
  AlertCircle,
  CheckCircle2,
  Lock,
  ArrowUpRight,
  Shield,
  Clock,
  LogOut,
  Zap,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { adminApi, authApi, getStoredUser, removeStoredUser, type User } from "@/lib/api"

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

  // Auth Guard
  React.useEffect(() => {
    const user = getStoredUser()
    if (!user) {
      router.push("/login")
      return
    }
    setCurrentUser(user)
    if (user.role === "ROLE_ADMIN") {
      setIsAuthorized(true)
    } else {
      setIsAuthorized(false)
      setIsLoading(false)
    }
  }, [router])

  // Fetch Users
  const fetchUsers = React.useCallback(async () => {
    setIsLoading(true)
    setFeedback(null)
    try {
      const response = await adminApi.getAllUsers()
      if (response.data) {
        setUsers(response.data)
      }
    } catch (err: any) {
      const isForbidden = err?.message?.includes("403") || err?.message?.includes("Forbidden")
      setFeedback({
        type: "error",
        message: isForbidden
          ? "Your session expired when the backend restarted. Please log in again with an administrator account."
          : (err.message || "Failed to load registered users."),
      })
    } finally {
      setIsLoading(false)
    }
  }, [])

  React.useEffect(() => {
    if (isAuthorized) {
      fetchUsers()
    }
  }, [isAuthorized, fetchUsers])

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
          message: `Successfully updated ${targetUser.fullName || targetUser.email}'s role to ${newRole}.`,
        })
      }
    } catch (err: any) {
      setFeedback({
        type: "error",
        message: err.message || "Could not update user role.",
      })
    } finally {
      setActionLoadingId(null)
    }
  }

  const handleLogout = async () => {
    try {
      await authApi.logout()
    } catch {
      // Ignore
    } finally {
      removeStoredUser()
      router.push("/login")
    }
  }

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
      <div className="min-h-screen bg-background flex items-center justify-center p-4">
        <div className="flex flex-col items-center gap-3">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-emerald-500 border-t-transparent" />
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground animate-pulse">
            Verifying Admin Authorization...
          </p>
        </div>
      </div>
    )
  }

  // Unauthorized screen
  if (isAuthorized === false) {
    return (
      <div className="min-h-screen bg-background flex items-center justify-center p-4">
        <Card className="max-w-md w-full border-red-500/30 bg-card/90 shadow-2xl">
          <CardHeader className="text-center space-y-2">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-red-500/10 border border-red-500/20 text-red-500">
              <ShieldAlert className="h-7 w-7" />
            </div>
            <CardTitle className="text-xl font-bold">Access Restricted</CardTitle>
            <CardDescription className="text-xs">
              This area is restricted to system administrators. Your account (
              <strong className="text-foreground">{currentUser?.email}</strong>) does not have
              the required <code className="text-emerald-500 font-mono">ROLE_ADMIN</code> privilege.
            </CardDescription>
          </CardHeader>
          <CardContent className="pt-2 flex flex-col gap-2">
            <Button asChild className="w-full bg-primary hover:bg-primary/90 text-primary-foreground">
              <Link href="/" className="flex items-center justify-center gap-2">
                <ArrowLeft className="h-4 w-4" />
                Return to Operations Dashboard
              </Link>
            </Button>
            <Button
              variant="outline"
              onClick={handleLogout}
              className="w-full text-xs text-muted-foreground hover:text-foreground"
            >
              Sign out and switch account
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background text-foreground flex flex-col">
      {/* Top Navbar */}
      <header className="sticky top-0 z-30 border-b border-border bg-card/80 backdrop-blur-md px-4 lg:px-8 py-3.5 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <Link
            href="/"
            className="flex h-9 w-9 items-center justify-center rounded-lg border border-border bg-muted/40 hover:bg-muted text-muted-foreground hover:text-foreground transition-colors"
            title="Back to Dashboard"
          >
            <ArrowLeft className="h-4 w-4" />
          </Link>
          <div className="flex items-center gap-2.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-tr from-emerald-600 to-teal-400 text-white shadow-sm">
              <Zap className="h-4 w-4 fill-current" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="font-bold text-sm leading-none">Admin Console</h1>
                <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-semibold text-emerald-600 dark:text-emerald-400 border border-emerald-500/30">
                  RBAC Management
                </span>
              </div>
              <p className="text-[11px] text-muted-foreground">Grid-Aware Compute Scheduler</p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className="hidden sm:flex items-center gap-2 px-3 py-1.5 rounded-lg border border-border bg-muted/30 text-xs">
            <span className="h-2 w-2 rounded-full bg-emerald-500" />
            <span className="font-mono text-[11px] text-muted-foreground">Admin:</span>
            <span className="font-semibold">{currentUser?.email}</span>
          </div>

          <Button
            variant="outline"
            size="sm"
            onClick={fetchUsers}
            disabled={isLoading}
            className="h-8 gap-1.5 text-xs cursor-pointer"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${isLoading ? "animate-spin" : ""}`} />
            <span className="hidden sm:inline">Refresh</span>
          </Button>

          <Button
            variant="ghost"
            size="icon"
            onClick={handleLogout}
            className="h-8 w-8 text-muted-foreground hover:text-red-500 cursor-pointer"
            title="Log Out"
          >
            <LogOut className="h-4 w-4" />
          </Button>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 px-4 lg:px-8 py-6 max-w-7xl w-full mx-auto space-y-6">
        {/* Banner Alert Feedback */}
        {feedback && (
          <div
            className={`flex items-center justify-between p-3 rounded-lg border text-xs ${
              feedback.type === "success"
                ? "border-emerald-500/30 bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
                : "border-red-500/30 bg-red-500/10 text-red-600 dark:text-red-400"
            }`}
          >
            <div className="flex items-center gap-2">
              {feedback.type === "success" ? (
                <CheckCircle2 className="h-4 w-4 shrink-0" />
              ) : (
                <AlertCircle className="h-4 w-4 shrink-0" />
              )}
              <span>{feedback.message}</span>
            </div>
            <div className="flex items-center gap-2">
              {feedback.type === "error" && feedback.message.includes("session expired") && (
                <Link
                  href="/login"
                  className="px-2.5 py-1 rounded bg-red-600 hover:bg-red-700 text-white font-semibold text-xs"
                >
                  Log In
                </Link>
              )}
              <button
                onClick={() => setFeedback(null)}
                className="text-xs underline hover:opacity-80 cursor-pointer"
              >
                Dismiss
              </button>
            </div>
          </div>
        )}

        {/* Overview Stats */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <Card className="border border-border/80 bg-card/60 backdrop-blur-xs">
            <CardContent className="p-4 flex items-center justify-between">
              <div>
                <p className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
                  Total Users
                </p>
                <p className="text-2xl font-bold mt-1 font-mono">{totalUsers}</p>
              </div>
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-blue-500/10 border border-blue-500/20 text-blue-500">
                <Users className="h-5 w-5" />
              </div>
            </CardContent>
          </Card>

          <Card className="border border-border/80 bg-card/60 backdrop-blur-xs">
            <CardContent className="p-4 flex items-center justify-between">
              <div>
                <p className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
                  Administrators
                </p>
                <p className="text-2xl font-bold mt-1 font-mono text-emerald-600 dark:text-emerald-400">
                  {totalAdmins}
                </p>
              </div>
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-500">
                <ShieldCheck className="h-5 w-5" />
              </div>
            </CardContent>
          </Card>

          <Card className="border border-border/80 bg-card/60 backdrop-blur-xs">
            <CardContent className="p-4 flex items-center justify-between">
              <div>
                <p className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
                  Standard Operators
                </p>
                <p className="text-2xl font-bold mt-1 font-mono">{totalOperators}</p>
              </div>
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-purple-500/10 border border-purple-500/20 text-purple-500">
                <Shield className="h-5 w-5" />
              </div>
            </CardContent>
          </Card>

          <Card className="border border-border/80 bg-card/60 backdrop-blur-xs">
            <CardContent className="p-4 flex items-center justify-between">
              <div>
                <p className="text-[11px] font-medium text-muted-foreground uppercase tracking-wider">
                  Verified Accounts
                </p>
                <p className="text-2xl font-bold mt-1 font-mono">
                  {totalVerified} / {totalUsers}
                </p>
              </div>
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-amber-500/10 border border-amber-500/20 text-amber-500">
                <UserCheck className="h-5 w-5" />
              </div>
            </CardContent>
          </Card>
        </div>

        {/* User Management Section */}
        <Card className="border border-border shadow-sm">
          <CardHeader className="pb-3 border-b border-border/60">
            <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
              <div>
                <CardTitle className="text-base font-bold flex items-center gap-2">
                  <ShieldCheck className="h-5 w-5 text-emerald-500" />
                  User Authorization & Roles
                </CardTitle>
                <CardDescription className="text-xs mt-1">
                  Manage platform privilege levels. Normal accounts default to standard user; only administrators can grant or revoke administrative access.
                </CardDescription>
              </div>

              {/* Search & Filter Toolbar */}
              <div className="flex items-center gap-2 flex-wrap">
                <div className="relative w-full sm:w-60">
                  <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
                  <Input
                    placeholder="Search name or email..."
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                    className="pl-8 h-8 text-xs"
                  />
                </div>

                <div className="flex items-center rounded-lg border border-border bg-muted/40 p-0.5 text-xs">
                  <button
                    onClick={() => setRoleFilter("ALL")}
                    className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors cursor-pointer ${
                      roleFilter === "ALL"
                        ? "bg-background text-foreground shadow-xs font-semibold"
                        : "text-muted-foreground hover:text-foreground"
                    }`}
                  >
                    All ({users.length})
                  </button>
                  <button
                    onClick={() => setRoleFilter("ROLE_ADMIN")}
                    className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors cursor-pointer ${
                      roleFilter === "ROLE_ADMIN"
                        ? "bg-background text-foreground shadow-xs font-semibold"
                        : "text-muted-foreground hover:text-foreground"
                    }`}
                  >
                    Admins ({totalAdmins})
                  </button>
                  <button
                    onClick={() => setRoleFilter("ROLE_USER")}
                    className={`px-2.5 py-1 rounded-md text-xs font-medium transition-colors cursor-pointer ${
                      roleFilter === "ROLE_USER"
                        ? "bg-background text-foreground shadow-xs font-semibold"
                        : "text-muted-foreground hover:text-foreground"
                    }`}
                  >
                    Users ({totalOperators})
                  </button>
                </div>
              </div>
            </div>
          </CardHeader>

          <CardContent className="p-0">
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead className="bg-muted/40 text-muted-foreground border-b border-border uppercase tracking-wider text-[10px]">
                  <tr>
                    <th className="py-3 px-4 font-semibold">User Details</th>
                    <th className="py-3 px-4 font-semibold">Status</th>
                    <th className="py-3 px-4 font-semibold">Role Privilege</th>
                    <th className="py-3 px-4 font-semibold text-right">Actions</th>
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
                                className={`h-8 w-8 rounded-full flex items-center justify-center font-bold text-xs ${
                                  isAdmin
                                    ? "bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30"
                                    : "bg-muted text-muted-foreground border border-border"
                                }`}
                              >
                                {(u.fullName || u.email)[0].toUpperCase()}
                              </div>
                              <div>
                                <div className="font-semibold text-foreground flex items-center gap-1.5">
                                  {u.fullName || u.email.split("@")[0]}
                                  {isSelf && (
                                    <span className="text-[10px] bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 px-1.5 py-0.2 rounded font-mono font-medium">
                                      You
                                    </span>
                                  )}
                                </div>
                                <div className="text-[11px] text-muted-foreground font-mono">
                                  {u.email}
                                </div>
                              </div>
                            </div>
                          </td>

                          <td className="py-3 px-4">
                            {Boolean(u.isVerified ?? u.verified) ? (
                              <span className="inline-flex items-center gap-1 text-[11px] font-medium text-emerald-600 dark:text-emerald-400">
                                <CheckCircle2 className="h-3.5 w-3.5" />
                                Verified
                              </span>
                            ) : (
                              <span className="inline-flex items-center gap-1 text-[11px] font-medium text-amber-500">
                                <Clock className="h-3.5 w-3.5" />
                                Pending OTP
                              </span>
                            )}
                          </td>

                          <td className="py-3 px-4">
                            {isAdmin ? (
                              <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-[11px] font-semibold bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30">
                                <ShieldCheck className="h-3.5 w-3.5" />
                                ROLE_ADMIN
                              </span>
                            ) : (
                              <span className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-[11px] font-semibold bg-muted text-muted-foreground border border-border">
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
      </main>
    </div>
  )
}

"use client"

import * as React from "react"
import { MARKET_TIMEZONE, MARKET_ZONE_LABEL, marketZoneDescriptor, type MarketZoneDescriptor } from "@/lib/date-utils"
import { Zap, House, CalendarClock, BriefcaseBusiness, TrendingDown, CheckCircle2, FileText, Bell, Menu, Sun, Moon, ShieldCheck, ChevronRight, Sparkles, LogOut, HelpCircle, Clock } from "lucide-react"
import Link from "next/link"
import { usePathname, useRouter } from "next/navigation"


import { Button } from "@/components/ui/button"

import { Sheet, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { useIsMobile } from "@/components/ui/use-mobile"
import { type DataStatusType } from "@/components/ui/data-status-badge"
import { DataStatusPopover } from "@/components/ui/efferd-dashboard-2-utils/data-status-popover"
import {
  authApi,
  freshnessApi,
  removeStoredUser,
  type DataFreshnessResponse,
  type FreshnessNotification,
  type User,
} from "@/lib/api"
import { freshnessSummary } from "@/lib/data-freshness"
import { mergeFreshnessNotifications } from "@/lib/notification-utils"

interface AppShellProps {
  children: React.ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const isMobile = useIsMobile()
  const router = useRouter()
  const [marketZone, setMarketZone] = React.useState<MarketZoneDescriptor>(
    () => ({ abbrev: MARKET_ZONE_LABEL, fullName: "Market local time", utcOffsetLabel: "" }),
  )
  // Set after mount on purpose: the zone depends on the browser's clock, so computing it during the first
  // render would differ from the server's HTML and cause a hydration mismatch.
  // eslint-disable-next-line react-hooks/set-state-in-effect
  React.useEffect(() => { setMarketZone(marketZoneDescriptor()) }, [])
  const [theme, setTheme] = React.useState<"light" | "dark">("dark")
  const [mobileOpen, setMobileOpen] = React.useState(false)
  const [currentUser, setCurrentUser] = React.useState<User | null>(null)
  const [isAuthChecking, setIsAuthChecking] = React.useState(true)
  const [tourOpen, setTourOpen] = React.useState(false)
  const [tourStep, setTourStep] = React.useState(0)
  const [notifications, setNotifications] = React.useState<FreshnessNotification[]>([])
  const [notificationsOpen, setNotificationsOpen] = React.useState(false)
  const [notificationError, setNotificationError] = React.useState<string | null>(null)
  // Null until the first check finishes, so the badge shows a skeleton instead of a wrong state.
  const [systemDataStatus, setSystemDataStatus] = React.useState<DataStatusType | null>(null)
  const [freshness, setFreshness] = React.useState<DataFreshnessResponse | null>(null)

  const pathname = usePathname()

  const tourSteps = [
    {
      title: "Home",
      description: "Home shows the plans that need your approval, the price now, and the jobs that run next.",
      target: "home-root",
      href: "/home",
    },
    {
      title: "Review suggested changes",
      description: "Pending approvals show schedule recommendations that need your review. Read the expected impact before approving or rejecting a change.",
      target: "approvals-root",
      href: "/approvals",
    },
    {
      title: "See your scheduled work",
      description: "The workload scheduler lists compute jobs, their current status, and when they are planned to run. A schedule can move flexible work to a lower-cost time.",
      target: "workloads-root",
      href: "/workloads",
    },
    {
      title: "Understand the forecast",
      description: "Price Forecast compares recorded settlement prices with model predictions. Peaks indicate times when electricity may cost more.",
      target: "forecasts-root",
      href: "/forecasts",
    },
    {
      title: "You’re ready to explore",
      description: "Use the menu on the left to move between areas. You can reopen this guide anytime with the help button at the top of the page.",
      target: null,
      href: null,
    },
  ]

  React.useEffect(() => {
    let active = true
    authApi.getMe().then(response => {
      if (!active) return
      if (!response.success || !response.data) throw new Error("Session unavailable")
      const user = response.data
      setCurrentUser(user)
      setIsAuthChecking(false)
      const onboardingKey = `gacs-onboarding-complete:${user.email.toLowerCase()}`
      if (window.localStorage.getItem(onboardingKey) !== "true") {
        setTourStep(-1)
        setTourOpen(true)
      }
    }).catch(() => {
      if (!active) return
      removeStoredUser()
      router.replace("/login")
    })
    return () => { active = false }
  }, [router])

  React.useEffect(() => {
    if (!currentUser) return

    let cancelled = false
    const pollFreshness = async () => {
      try {
        const response = await freshnessApi.getStatus()
        if (cancelled) return
        if (!response.success || !response.data) throw new Error("Data status unavailable")
        setNotifications((previous) => mergeFreshnessNotifications(previous, response.data?.notifications ?? []))
        setFreshness(response.data)
        setSystemDataStatus(freshnessSummary(response.data).status)
      } catch {
        if (cancelled) return
        setFreshness(null)
        setSystemDataStatus("unavailable")
      }
    }

    void pollFreshness()
    const interval = window.setInterval(pollFreshness, 5 * 60 * 1000)
    return () => {
      cancelled = true
      window.clearInterval(interval)
    }
  }, [currentUser])

  const unreadNotifications = notifications.filter((notification) => !notification.read)

  const markNotificationRead = async (notification: FreshnessNotification) => {
    setNotificationError(null)
    try {
      const response = await freshnessApi.markNotificationRead(notification.id)
      if (!response.success) throw new Error("Unable to save notification status")
      setNotifications(previous => previous.map(item => item.id === notification.id ? { ...item, read: true } : item))
    } catch {
      setNotificationError("Could not mark the notification as read. Please try again.")
    }
  }

  const handleLogout = async () => {
    try {
      await authApi.logout()
    } catch {
      // Ignore network errors on logout
    } finally {
      removeStoredUser()
      router.push("/login")
    }
  }

  // Apply dark mode class to document element
  React.useEffect(() => {
    const root = document.documentElement
    if (theme === "dark") {
      root.classList.add("dark")
    } else {
      root.classList.remove("dark")
    }
  }, [theme])

  const toggleTheme = () => {
    setTheme((prev) => (prev === "dark" ? "light" : "dark"))
  }

  const closeTour = (completed: boolean) => {
    setTourOpen(false)
    if (completed && currentUser?.email) {
      window.localStorage.setItem(`gacs-onboarding-complete:${currentUser.email.toLowerCase()}`, "true")
    }
  }

  const goToTourStep = (nextStep: number) => {
    setTourStep(nextStep)
    const step = tourSteps[nextStep]
    if (step?.href && pathname !== step.href) {
      router.push(step.href)
    }
    const target = step?.target
    if (target) {
      window.setTimeout(() => document.getElementById(target)?.scrollIntoView({ behavior: "smooth", block: "center" }), 120)
    }
  }

  interface NavItem {
    label: string
    icon: React.ComponentType<{ className?: string }>
    href: string
    active?: boolean
    badge?: string
    highlight?: boolean
  }

  const navItems: NavItem[] = [
    // The order follows the real flow: create jobs, approve, schedule (masterplan D17).
    { label: "Home", icon: House, href: "/home", active: pathname === "/home" || pathname === "/" },
    { label: "My jobs", icon: BriefcaseBusiness, href: "/jobs", active: pathname?.startsWith("/jobs") },
    { label: "Pending Approvals", icon: CheckCircle2, href: "/approvals", active: pathname?.startsWith("/approvals") },
    { label: "Schedule", icon: CalendarClock, href: "/workloads", active: pathname?.startsWith("/workloads") },
    { label: "Price Forecast", icon: TrendingDown, href: "/forecasts", active: pathname?.startsWith("/forecasts") },
    { label: "Historical Audit Logs", icon: FileText, href: "/audit", active: pathname?.startsWith("/audit") },
  ]

  const sidebarContent = (
    <div className="flex h-full flex-col bg-card border-r border-border text-card-foreground">
      {/* Brand Header */}
      <div className="flex items-center gap-3 px-5 py-5 border-b border-border">
        <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary text-primary-foreground">
          <Zap className="h-6 w-6 fill-current" />
        </div>
        <div className="flex flex-col">
          <div className="flex items-center gap-2">
            <span className="font-semibold tracking-tight text-lg leading-none">GACS</span>
            <span className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-semibold text-primary border border-primary/30">
              v1.0
            </span>
          </div>
          <span className="text-xs text-muted-foreground mt-0.5">Grid-Aware Compute</span>
        </div>
      </div>

      {/* Grid Region Badge */}
      <div className="px-4 py-3 border-b border-border/50 bg-muted/40">
        <div className="flex items-center justify-between text-xs">
          <span className="text-muted-foreground">Market Hub</span>
          <span className="font-semibold text-primary flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full bg-primary inline-block" />
            ERCOT North
          </span>
        </div>
        <div className="mt-1 flex items-center justify-between text-xs text-muted-foreground">
          <span>Prices</span>
          <span className="font-semibold text-foreground">USD / MWh</span>
        </div>
      </div>

      {/* Navigation Links */}
      <div className="flex-1 overflow-y-auto px-3 py-4 space-y-1">
        <div className="px-2 pb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          Operations
        </div>
        {navItems.map((item) => {
          const Icon = item.icon
          return (
            <Link
              key={item.label}
              href={item.href}
              onClick={() => isMobile && setMobileOpen(false)}
              className={`flex items-center justify-between gap-3 px-3 py-2.5 rounded-lg text-sm transition-all group ${
                item.active
                  ? "bg-primary text-primary-foreground font-semibold shadow-sm"
                  : "text-muted-foreground hover:bg-accent hover:text-accent-foreground"
              }`}
            >
              <div className="flex items-center gap-3">
                <Icon className={`h-4 w-4 ${item.active ? "text-primary-foreground" : "text-muted-foreground group-hover:text-foreground"}`} />
                <span>{item.label}</span>
              </div>
              {item.badge && (
                <span
                  className={`text-xs font-semibold px-2 py-0.5 rounded-full whitespace-nowrap shrink-0 ${
                    item.highlight
                      ? "bg-primary text-primary-foreground"
                      : item.active
                      ? "bg-primary-foreground/20 text-primary-foreground"
                      : "bg-muted text-muted-foreground"
                  }`}
                >
                  {item.badge}
                </span>
              )}
            </Link>
          )
        })}

        {currentUser?.role === "ROLE_ADMIN" && (
          <div className="pt-3">
            <div className="px-2 pb-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Administration
            </div>
            <Link
              href="/admin"
              onClick={() => isMobile && setMobileOpen(false)}
              className="flex items-center justify-between gap-3 px-3 py-2.5 rounded-lg text-sm font-semibold transition-all group border border-primary/30 bg-primary/10 text-primary hover:bg-primary/20"
            >
              <div className="flex items-center gap-3">
                <ShieldCheck className="h-4 w-4 text-primary" />
                <span>Admin Console</span>
              </div>
              <span className="text-xs font-semibold px-2 py-0.5 rounded-full bg-primary/10 text-primary border border-primary/30">
                RBAC
              </span>
            </Link>
          </div>
        )}
      </div>

      {/* User Footer Profile */}
      <div className="p-4 border-t border-border mt-auto bg-muted/20">
        <div className="flex items-center gap-3">
          <div className="h-9 w-9 rounded-full bg-primary/10 text-primary border border-primary/30 flex items-center justify-center font-semibold text-xs uppercase">
            {(currentUser?.fullName || currentUser?.email || "U")[0]}
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-xs font-semibold text-foreground truncate">
              {currentUser?.fullName || currentUser?.email?.split("@")[0] || "Account"}
            </p>
            <p className="text-xs text-muted-foreground truncate">
              {currentUser?.role === "ROLE_ADMIN"
                ? "System Administrator"
                : "Operations User"}
            </p>
          </div>
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-8 w-8 text-muted-foreground hover:text-destructive-foreground cursor-pointer"
                  onClick={handleLogout}
                >
                  <LogOut className="h-4 w-4" />
                </Button>
              </TooltipTrigger>
              <TooltipContent side="top">Log Out</TooltipContent>
            </Tooltip>
          </TooltipProvider>
        </div>
      </div>
    </div>
  )

  if (isAuthChecking) {
    return (
      <div className="flex h-screen w-screen items-center justify-center bg-background text-foreground">
        <div className="flex flex-col items-center gap-3">
          <div className="h-8 w-8 animate-spin rounded-full border-4 border-primary border-t-transparent" />
          <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            Verifying Authentication...
          </p>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen bg-background">
      {/* Desktop Sidebar */}
      <aside className="hidden md:flex md:w-64 md:flex-col fixed inset-y-0 z-30">
        {sidebarContent}
      </aside>

      {/* Mobile Drawer */}
      <Sheet open={mobileOpen} onOpenChange={setMobileOpen}>
        <SheetContent side="left" className="p-0 w-72">
          <SheetHeader className="sr-only">
            <SheetTitle>Navigation Menu</SheetTitle>
          </SheetHeader>
          {sidebarContent}
        </SheetContent>
      </Sheet>

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col md:pl-64 min-w-0">
        {/* Top App Header */}
        <header className="sticky top-0 z-20 flex h-16 items-center justify-between gap-2 sm:gap-4 border-b border-border bg-card/80 backdrop-blur px-4 sm:px-6">
          <div className="flex items-center gap-3">
            <Button
              variant="outline"
              size="icon"
              className="md:hidden h-9 w-9 shrink-0"
              onClick={() => setMobileOpen(true)}
            >
              <Menu className="h-5 w-5" />
              <span className="sr-only">Open menu</span>
            </Button>


          </div>

          <div className="flex shrink-0 items-center gap-1 sm:gap-3">
            {/* Market (ERCOT) local timezone indicator */}
            <TooltipProvider>
              <Tooltip>
                <TooltipTrigger asChild>
                  <span className="inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap px-2.5 py-1 rounded-full text-xs leading-4 bg-muted text-foreground border border-border cursor-help select-none">
                    <Clock className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
                    <span>{marketZone.abbrev}</span>
                    {marketZone.utcOffsetLabel && <span className="hidden sm:inline">(UTC{marketZone.utcOffsetLabel})</span>}
                  </span>
                </TooltipTrigger>
                <TooltipContent side="bottom" collisionPadding={8} className="text-xs leading-4 max-w-xs">
                  <p className="font-semibold text-foreground">{marketZone.fullName} ({marketZone.abbrev})</p>
                  <p className="text-muted-foreground mt-0.5">
                    All workload dispatch windows, forecast horizons, and calendar slots are shown in ERCOT market local time ({MARKET_TIMEZONE}).
                  </p>
                </TooltipContent>
              </Tooltip>
            </TooltipProvider>

            {/* System data status: a badge that opens the list of all datasets */}
            <DataStatusPopover status={systemDataStatus} response={freshness} />

            {/* Quick Theme Switcher */}
            <Button
              variant="ghost"
              size="icon"
              className="h-9 w-9 shrink-0 text-muted-foreground hover:text-foreground"
              onClick={toggleTheme}
            >
              {theme === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
              <span className="sr-only">Toggle theme</span>
            </Button>

            {/* Notifications */}
            <TooltipProvider>
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-9 w-9 text-muted-foreground hover:text-foreground"
                    aria-label="Show dashboard guide"
                    onClick={() => { setTourStep(-1); setTourOpen(true) }}
                  >
                    <HelpCircle className="h-4 w-4" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent>Show dashboard guide</TooltipContent>
              </Tooltip>
            </TooltipProvider>
            <div className="relative">
              <Button
                variant="ghost"
                size="icon"
                className="h-9 w-9 relative text-muted-foreground hover:text-foreground"
                aria-label={`Notifications${unreadNotifications.length ? `, ${unreadNotifications.length} unread` : ""}`}
                aria-expanded={notificationsOpen}
                onClick={() => setNotificationsOpen((open) => !open)}
              >
                <Bell className="h-4 w-4" />
                {unreadNotifications.length > 0 && (
                  <span className="absolute top-2 right-2 h-2 w-2 rounded-full bg-warning" />
                )}
                <span className="sr-only">Notifications</span>
              </Button>
              {notificationsOpen && (
                <div className="absolute right-0 top-11 z-50 w-80 rounded-xl border border-border bg-card p-3 text-card-foreground shadow-xl">
                  <div className="mb-2 flex items-center justify-between">
                    <h2 className="text-sm font-semibold">Notifications</h2>
                    <span className="text-xs text-muted-foreground">Data freshness</span>
                  </div>
                  {notificationError && <p role="alert" className="mb-2 text-xs text-destructive">{notificationError}</p>}
                  {notifications.length === 0 ? (
                    <p className="py-5 text-center text-xs text-muted-foreground">No data alerts.</p>
                  ) : (
                    <div className="space-y-2">
                      {notifications.map((notification) => (
                        <button
                          key={notification.id}
                          type="button"
                          className={`w-full rounded-lg border p-3 text-left text-xs transition-colors hover:bg-accent ${notification.read ? "border-border" : "border-warning-foreground/40 bg-warning/10"}`}
                          onClick={() => void markNotificationRead(notification)}
                        >
                          <span className="font-semibold">{notification.dataset}</span>
                          <span className="mt-1 block text-muted-foreground">{notification.message}</span>
                          <span className="mt-2 block text-xs text-muted-foreground">{new Date(notification.createdAt).toLocaleString()}</span>
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        </header>

        {tourOpen && (
          <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 p-4" role="presentation">
            <section
              role="dialog"
              aria-modal="true"
              aria-labelledby="onboarding-title"
              aria-describedby="onboarding-description"
              className="w-full max-w-lg rounded-2xl border border-border bg-card p-6 text-card-foreground shadow-2xl sm:p-8"
            >
              <div className="mb-5 flex items-center gap-2 text-sm font-semibold text-primary">
                <Sparkles className="h-4 w-4" />
                {tourStep < 0 ? "A quick tour" : `Step ${tourStep + 1} of ${tourSteps.length}`}
              </div>
              <h2 id="onboarding-title" className="text-xl font-semibold tracking-tight">
                {tourStep < 0 ? `Welcome${currentUser?.fullName ? `, ${currentUser.fullName.split(" ")[0]}` : ""}!` : tourSteps[tourStep].title}
              </h2>
              <p id="onboarding-description" className="mt-3 text-sm leading-6 text-muted-foreground">
                {tourStep < 0
                  ? "GACS helps you understand energy conditions and plan compute schedules. This short guide shows you where to find the key information."
                  : tourSteps[tourStep].description}
              </p>
              <div className="mt-7 flex items-center justify-between gap-3">
                <Button variant="ghost" onClick={() => closeTour(true)}>
                  {tourStep < 0 ? "Explore on my own" : "Skip tour"}
                </Button>
                <div className="flex items-center gap-2">
                  {tourStep >= 0 && tourStep > 0 && (
                    <Button variant="outline" onClick={() => goToTourStep(tourStep - 1)}>Back</Button>
                  )}
                  <Button
                    onClick={() => tourStep < 0 ? goToTourStep(0) : tourStep < tourSteps.length - 1 ? goToTourStep(tourStep + 1) : closeTour(true)}
                  >
                    {tourStep < 0 ? "Take the tour" : tourStep === tourSteps.length - 1 ? "Finish" : "Next"}
                    <ChevronRight className="ml-1 h-4 w-4" />
                  </Button>
                </div>
              </div>
              {tourStep >= 0 && (
                <div className="mt-5 flex justify-center gap-1.5" aria-label={`Step ${tourStep + 1} of ${tourSteps.length}`}>
                  {tourSteps.map((step, index) => (
                    <span key={step.title} className={`h-1.5 w-6 rounded-full ${index === tourStep ? "bg-primary" : "bg-muted"}`} />
                  ))}
                </div>
              )}
            </section>
          </div>
        )}

        {/* Page Body */}
        <main className="flex-1 p-4 sm:p-6 lg:p-8 max-w-7xl w-full mx-auto space-y-6">
          {children}
        </main>
      </div>
    </div>
  )
}

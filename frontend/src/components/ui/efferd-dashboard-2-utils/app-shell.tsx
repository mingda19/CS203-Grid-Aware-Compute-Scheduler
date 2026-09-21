"use client"

import * as React from "react"
import {
  Zap,
  LayoutDashboard,
  CalendarClock,
  TrendingDown,
  BatteryCharging,
  CheckCircle2,
  FileText,
  Settings,
  Bell,
  Search,
  Menu,
  Sun,
  Moon,
  ShieldCheck,
  ChevronRight,
  Sparkles,
  LogOut,
  SlidersHorizontal,
} from "lucide-react"
import Link from "next/link"
import { usePathname, useRouter } from "next/navigation"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { Separator } from "@/components/ui/separator"
import { useIsMobile } from "@/components/ui/use-mobile"
import { authApi, getStoredUser, removeStoredUser, type User } from "@/lib/api"

interface AppShellProps {
  children: React.ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const isMobile = useIsMobile()
  const router = useRouter()
  const [theme, setTheme] = React.useState<"light" | "dark">("dark")
  const [mobileOpen, setMobileOpen] = React.useState(false)
  const [currentUser, setCurrentUser] = React.useState<User | null>(null)

  React.useEffect(() => {
    setCurrentUser(getStoredUser())
  }, [])

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

  const navItems = [
    { label: "Live Grid & Overview", icon: LayoutDashboard, href: "#overview", active: true },
    { label: "Workload Scheduler", icon: CalendarClock, href: "#workloads", badge: "4 Active" },
    { label: "Price & Wind Forecast", icon: TrendingDown, href: "#forecasts" },
    { label: "Battery & Energy Mix", icon: BatteryCharging, href: "#battery" },
    { label: "Pending Approvals", icon: CheckCircle2, href: "#approvals", badge: "1 New", highlight: true },
    { label: "Historical Audit Logs", icon: FileText, href: "#audit" },
    { label: "Facility Constraints", icon: SlidersHorizontal, href: "#constraints" },
    { label: "System Settings", icon: Settings, href: "#settings" },
  ]

  const sidebarContent = (
    <div className="flex h-full flex-col bg-card border-r border-border text-card-foreground">
      {/* Brand Header */}
      <div className="flex items-center gap-3 px-5 py-5 border-b border-border">
        <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-tr from-emerald-600 to-teal-400 text-white shadow-md shadow-emerald-500/20">
          <Zap className="h-6 w-6 fill-current" />
        </div>
        <div className="flex flex-col">
          <div className="flex items-center gap-2">
            <span className="font-bold tracking-tight text-lg leading-none">GACS</span>
            <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] font-semibold text-emerald-600 dark:text-emerald-400 border border-emerald-500/30">
              v1.0
            </span>
          </div>
          <span className="text-xs text-muted-foreground mt-0.5">Grid-Aware Compute</span>
        </div>
      </div>

      {/* Grid Region Badge */}
      <div className="px-4 py-3 border-b border-border/50 bg-muted/40">
        <div className="flex items-center justify-between text-xs">
          <span className="text-muted-foreground font-medium">Market Hub</span>
          <span className="font-mono font-semibold text-emerald-600 dark:text-emerald-400 flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full bg-emerald-500 animate-ping inline-block" />
            ERCOT North
          </span>
        </div>
        <div className="mt-1 flex items-center justify-between text-[11px] text-muted-foreground">
          <span>Target Savings</span>
          <span className="font-mono font-bold text-foreground">15–25%</span>
        </div>
      </div>

      {/* Navigation Links */}
      <div className="flex-1 overflow-y-auto px-3 py-4 space-y-1">
        <div className="px-2 pb-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/70">
          Operations
        </div>
        {navItems.map((item) => {
          const Icon = item.icon
          return (
            <a
              key={item.label}
              href={item.href}
              onClick={() => isMobile && setMobileOpen(false)}
              className={`flex items-center justify-between gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-all group ${
                item.active
                  ? "bg-primary text-primary-foreground shadow-sm shadow-primary/20"
                  : "text-muted-foreground hover:bg-accent hover:text-accent-foreground"
              }`}
            >
              <div className="flex items-center gap-3">
                <Icon className={`h-4 w-4 ${item.active ? "text-primary-foreground" : "text-muted-foreground group-hover:text-foreground"}`} />
                <span>{item.label}</span>
              </div>
              {item.badge && (
                <span
                  className={`text-[10px] font-semibold px-2 py-0.5 rounded-full ${
                    item.highlight
                      ? "bg-amber-500 text-white shadow-xs animate-pulse"
                      : item.active
                      ? "bg-primary-foreground/20 text-primary-foreground"
                      : "bg-muted text-muted-foreground"
                  }`}
                >
                  {item.badge}
                </span>
              )}
            </a>
          )
        })}
      </div>

      {/* User Footer Profile */}
      <div className="p-4 border-t border-border mt-auto bg-muted/20">
        <div className="flex items-center gap-3">
          <div className="h-9 w-9 rounded-full bg-emerald-500/20 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30 flex items-center justify-center font-bold text-xs uppercase">
            {(currentUser?.fullName || currentUser?.email || "U")[0]}
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-xs font-semibold text-foreground truncate">
              {currentUser?.fullName || currentUser?.email?.split("@")[0] || "Elena Vance"}
            </p>
            <p className="text-[11px] text-muted-foreground truncate">
              {currentUser?.role || "Data Center Operations Manager"}
            </p>
          </div>
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-8 w-8 text-muted-foreground hover:text-red-500 cursor-pointer"
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
        <header className="sticky top-0 z-20 flex h-16 items-center justify-between gap-4 border-b border-border bg-card/80 backdrop-blur px-4 sm:px-6">
          <div className="flex items-center gap-3">
            <Button
              variant="outline"
              size="icon"
              className="md:hidden h-9 w-9"
              onClick={() => setMobileOpen(true)}
            >
              <Menu className="h-5 w-5" />
              <span className="sr-only">Open menu</span>
            </Button>

            <div className="relative hidden sm:block w-64 md:w-80">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
              <Input
                placeholder="Search workloads, node clusters, runs..."
                className="pl-9 h-9 text-xs bg-background/60"
              />
            </div>
          </div>

          <div className="flex items-center gap-2 sm:gap-3">
            {/* Live Data Status Indicator */}
            <div className="hidden lg:flex items-center gap-2 rounded-full border border-emerald-500/20 bg-emerald-500/10 px-3 py-1 text-xs text-emerald-600 dark:text-emerald-400">
              <span className="h-2 w-2 rounded-full bg-emerald-500" />
              <span className="font-medium">EIA & ERCOT Feeds Ingested (Fresh)</span>
            </div>

            {/* Quick Theme Switcher */}
            <Button
              variant="ghost"
              size="icon"
              className="h-9 w-9 text-muted-foreground hover:text-foreground"
              onClick={toggleTheme}
            >
              {theme === "dark" ? <Sun className="h-4 w-4 text-amber-400" /> : <Moon className="h-4 w-4" />}
              <span className="sr-only">Toggle theme</span>
            </Button>

            {/* Notifications */}
            <Button
              variant="ghost"
              size="icon"
              className="h-9 w-9 relative text-muted-foreground hover:text-foreground"
            >
              <Bell className="h-4 w-4" />
              <span className="absolute top-2 right-2 h-2 w-2 rounded-full bg-amber-500" />
              <span className="sr-only">Notifications</span>
            </Button>

            <Separator orientation="vertical" className="h-6 mx-1 hidden sm:block" />

            {/* Quick Action Button */}
            <Button
              size="sm"
              className="h-9 gap-1.5 text-xs bg-emerald-600 hover:bg-emerald-700 text-white shadow-xs font-semibold"
              onClick={() => {
                const el = document.getElementById("approvals")
                if (el) el.scrollIntoView({ behavior: "smooth" })
              }}
            >
              <Sparkles className="h-3.5 w-3.5" />
              <span className="hidden sm:inline">Optimize Schedules</span>
              <span className="sm:hidden">Optimize</span>
            </Button>
          </div>
        </header>

        {/* Page Body */}
        <main className="flex-1 p-4 sm:p-6 lg:p-8 max-w-7xl w-full mx-auto space-y-6">
          {children}
        </main>
      </div>
    </div>
  )
}

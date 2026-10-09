"use client"

import * as React from "react"
import {
  ChevronLeft,
  ChevronRight,
  Calendar as CalendarIcon,
  Clock,
  Plus,
  Search,
  Filter,
  Check,
  X,
  Server,
  Zap,
  DollarSign,
  AlertCircle,
  Play,
  Pause,
  ChevronDown,
  Info,
  CalendarDays,
  SlidersHorizontal,
  RefreshCw,
  Sliders,
  CheckSquare,
  Square,
  Sparkles,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"

export type WorkloadType = "ML Training" | "Crypto Mining" | "HPC Batch" | "HVAC Pre-Cool" | "Grid Alert"

export interface CalendarEvent {
  id: string
  title: string
  type: WorkloadType
  cluster: string
  powerKw: number
  savings: string
  status: "Scheduled" | "Running" | "Throttled" | "Completed"
  dateStr: string // YYYY-MM-DD
  startHour: number // 0-23
  startMinute: number // 0 or 30
  durationHours: number // e.g. 2, 3.5, 4
  colorScheme: {
    bg: string
    border: string
    text: string
    badge: string
    dot: string
  }
  notes?: string
}

const TYPE_CONFIG: Record<
  WorkloadType,
  {
    name: string
    bg: string
    border: string
    text: string
    badge: string
    dot: string
    iconColor: string
  }
> = {
  "ML Training": {
    name: "ML Training",
    bg: "bg-blue-500/15 dark:bg-blue-500/20 hover:bg-blue-500/25",
    border: "border-l-4 border-l-blue-500 border border-blue-500/30",
    text: "text-blue-700 dark:text-blue-300",
    badge: "bg-blue-500/20 text-blue-700 dark:text-blue-300",
    dot: "bg-blue-500",
    iconColor: "#3b82f6",
  },
  "Crypto Mining": {
    name: "Crypto Mining",
    bg: "bg-amber-500/15 dark:bg-amber-500/20 hover:bg-amber-500/25",
    border: "border-l-4 border-l-amber-500 border border-amber-500/30",
    text: "text-amber-700 dark:text-amber-300",
    badge: "bg-amber-500/20 text-amber-700 dark:text-amber-300",
    dot: "bg-amber-500",
    iconColor: "#f59e0b",
  },
  "HPC Batch": {
    name: "HPC Batch",
    bg: "bg-purple-500/15 dark:bg-purple-500/20 hover:bg-purple-500/25",
    border: "border-l-4 border-l-purple-500 border border-purple-500/30",
    text: "text-purple-700 dark:text-purple-300",
    badge: "bg-purple-500/20 text-purple-700 dark:text-purple-300",
    dot: "bg-purple-500",
    iconColor: "#8b5cf6",
  },
  "HVAC Pre-Cool": {
    name: "HVAC Pre-Cool",
    bg: "bg-emerald-500/15 dark:bg-emerald-500/20 hover:bg-emerald-500/25",
    border: "border-l-4 border-l-emerald-500 border border-emerald-500/30",
    text: "text-emerald-700 dark:text-emerald-300",
    badge: "bg-emerald-500/20 text-emerald-700 dark:text-emerald-300",
    dot: "bg-emerald-500",
    iconColor: "#10b981",
  },
  "Grid Alert": {
    name: "Grid Peak Alert",
    bg: "bg-rose-500/15 dark:bg-rose-500/20 hover:bg-rose-500/25",
    border: "border-l-4 border-l-rose-500 border border-rose-500/30",
    text: "text-rose-700 dark:text-rose-300",
    badge: "bg-rose-500/20 text-rose-700 dark:text-rose-300",
    dot: "bg-rose-500",
    iconColor: "#f43f5e",
  },
}

// Format date helper: YYYY-MM-DD
function toDateStr(d: Date): string {
  const year = d.getFullYear()
  const month = String(d.getMonth() + 1).padStart(2, "0")
  const day = String(d.getDate()).padStart(2, "0")
  return `${year}-${month}-${day}`
}

function getRelativeDate(offsetDays: number): string {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  return toDateStr(d)
}

// Generate realistic mock events relative to today so calendar is always populated
const getMockEvents = (): CalendarEvent[] => [
  {
    id: "WL-409",
    title: "Llama-3-70B Fine-Tuning Run #4",
    type: "ML Training",
    cluster: "64x NVIDIA H100 SXM5",
    powerKw: 1200,
    savings: "$1,840 (27%)",
    status: "Running",
    dateStr: getRelativeDate(0), // Today
    startHour: 1,
    startMinute: 30,
    durationHours: 4,
    colorScheme: TYPE_CONFIG["ML Training"],
    notes: "High priority LLM workload shifted to off-peak wind hours.",
  },
  {
    id: "WL-108",
    title: "ASIC Pod Alpha - Dynamic Mining",
    type: "Crypto Mining",
    cluster: "Antminer S19 Pro+ Pod 2",
    powerKw: 1800,
    savings: "$1,450 (31%)",
    status: "Running",
    dateStr: getRelativeDate(0), // Today
    startHour: 8,
    startMinute: 0,
    durationHours: 6.5,
    colorScheme: TYPE_CONFIG["Crypto Mining"],
    notes: "Dynamic curtailment enabled. Throttles during ERCOT $100+/MWh spikes.",
  },
  {
    id: "WL-812",
    title: "Monte Carlo Risk Analysis Batch",
    type: "HPC Batch",
    cluster: "Slurm HPC Cluster (96 Nodes)",
    powerKw: 450,
    savings: "$520 (19%)",
    status: "Scheduled",
    dateStr: getRelativeDate(0), // Today
    startHour: 15,
    startMinute: 0,
    durationHours: 3.5,
    colorScheme: TYPE_CONFIG["HPC Batch"],
    notes: "Financial modeling batch with hard deadline at 21:00 UTC.",
  },
  {
    id: "WL-022",
    title: "Facility Thermal Chiller Pre-Cool",
    type: "HVAC Pre-Cool",
    cluster: "Trane Centrifugal Chiller Bank",
    powerKw: 320,
    savings: "$310 (16%)",
    status: "Scheduled",
    dateStr: getRelativeDate(0), // Today
    startHour: 19,
    startMinute: 0,
    durationHours: 2.5,
    colorScheme: TYPE_CONFIG["HVAC Pre-Cool"],
    notes: "Pre-cool water loop to 42°F before expected evening tariff increase.",
  },
  {
    id: "WL-301",
    title: "DeepSeek-R1 Distillation Epoch 12",
    type: "ML Training",
    cluster: "128x NVIDIA H100 SXM5",
    powerKw: 2400,
    savings: "$3,120 (34%)",
    status: "Scheduled",
    dateStr: getRelativeDate(1), // Tomorrow
    startHour: 2,
    startMinute: 0,
    durationHours: 5,
    colorScheme: TYPE_CONFIG["ML Training"],
    notes: "Distributed multi-node checkpointing enabled across Pods A & B.",
  },
  {
    id: "WL-604",
    title: "Weather WRF Simulation 1km Grid",
    type: "HPC Batch",
    cluster: "Slurm HPC Cluster (96 Nodes)",
    powerKw: 680,
    savings: "$780 (22%)",
    status: "Scheduled",
    dateStr: getRelativeDate(1), // Tomorrow
    startHour: 10,
    startMinute: 30,
    durationHours: 4,
    colorScheme: TYPE_CONFIG["HPC Batch"],
    notes: "NOAA regional weather modeling run. Low interruption tolerance.",
  },
  {
    id: "WL-550",
    title: "ERCOT Peak Price Curtailment Alert",
    type: "Grid Alert",
    cluster: "All High-Density Racks",
    powerKw: 0,
    savings: "Avoided $5,400",
    status: "Scheduled",
    dateStr: getRelativeDate(1), // Tomorrow
    startHour: 16,
    startMinute: 0,
    durationHours: 3,
    colorScheme: TYPE_CONFIG["Grid Alert"],
    notes: "Forecasted wholesale electricity spike above $250/MWh. Non-critical jobs paused.",
  },
  {
    id: "WL-772",
    title: "ASIC Pod Beta - Night Low-Tariff Ramp",
    type: "Crypto Mining",
    cluster: "Antminer S19 XP Pod 1",
    powerKw: 1600,
    savings: "$1,620 (38%)",
    status: "Scheduled",
    dateStr: getRelativeDate(2),
    startHour: 0,
    startMinute: 30,
    durationHours: 6,
    colorScheme: TYPE_CONFIG["Crypto Mining"],
    notes: "Overnight West Texas surplus wind capture window.",
  },
  {
    id: "WL-921",
    title: "AlphaFold-3 Protein Structure Batch",
    type: "HPC Batch",
    cluster: "Slurm HPC Cluster (96 Nodes)",
    powerKw: 550,
    savings: "$610 (21%)",
    status: "Scheduled",
    dateStr: getRelativeDate(-1), // Yesterday
    startHour: 6,
    startMinute: 0,
    durationHours: 4,
    colorScheme: TYPE_CONFIG["HPC Batch"],
    notes: "Biotech screening dataset processing completed smoothly.",
  },
  {
    id: "WL-119",
    title: "Liquid Cooling Loop Purge & Flush",
    type: "HVAC Pre-Cool",
    cluster: "Facility Operations",
    powerKw: 150,
    savings: "$140 (12%)",
    status: "Completed",
    dateStr: getRelativeDate(-1), // Yesterday
    startHour: 13,
    startMinute: 0,
    durationHours: 2,
    colorScheme: TYPE_CONFIG["HVAC Pre-Cool"],
    notes: "Scheduled maintenance during solar production peak.",
  },
  {
    id: "WL-883",
    title: "Mistral Large Instruct Fine-Tuning",
    type: "ML Training",
    cluster: "64x NVIDIA H100 SXM5",
    powerKw: 1200,
    savings: "$1,910 (29%)",
    status: "Scheduled",
    dateStr: getRelativeDate(3),
    startHour: 3,
    startMinute: 0,
    durationHours: 5,
    colorScheme: TYPE_CONFIG["ML Training"],
    notes: "Dispatched under ERCOT dynamic flexible load tariff.",
  },
]

type CalendarViewMode = "week" | "month" | "day" | "schedule"

const HOURS_OF_DAY = Array.from({ length: 24 }, (_, i) => i)

export function GoogleCalendarView() {
  const [currentDate, setCurrentDate] = React.useState<Date>(new Date())
  const [viewMode, setViewMode] = React.useState<CalendarViewMode>("week")
  const [events, setEvents] = React.useState<CalendarEvent[]>(getMockEvents)
  const [selectedEvent, setSelectedEvent] = React.useState<CalendarEvent | null>(null)
  const [isSidebarOpen, setIsSidebarOpen] = React.useState<boolean>(true)
  const [searchQuery, setSearchQuery] = React.useState<string>("")
  const [activeTypes, setActiveTypes] = React.useState<Record<WorkloadType, boolean>>({
    "ML Training": true,
    "Crypto Mining": true,
    "HPC Batch": true,
    "HVAC Pre-Cool": true,
    "Grid Alert": true,
  })

  // Quick event creation modal state
  const [isCreateModalOpen, setIsCreateModalOpen] = React.useState(false)
  const [newEventTitle, setNewEventTitle] = React.useState("")
  const [newEventType, setNewEventType] = React.useState<WorkloadType>("ML Training")
  const [newEventStartHour, setNewEventStartHour] = React.useState(9)
  const [newEventDuration, setNewEventDuration] = React.useState(3)
  const [newEventCluster, setNewEventCluster] = React.useState("64x NVIDIA H100 SXM5")
  const [newEventPowerKw, setNewEventPowerKw] = React.useState(1200)

  // Current time representation for live red line
  const now = new Date()
  const todayStr = toDateStr(now)
  const currentHourDecimal = now.getHours() + now.getMinutes() / 60

  // Filter events
  const filteredEvents = React.useMemo(() => {
    return events.filter((ev) => {
      if (!activeTypes[ev.type]) return false
      if (searchQuery.trim()) {
        const query = searchQuery.toLowerCase()
        return (
          ev.title.toLowerCase().includes(query) ||
          ev.cluster.toLowerCase().includes(query) ||
          ev.id.toLowerCase().includes(query)
        )
      }
      return true
    })
  }, [events, activeTypes, searchQuery])

  // Navigation handlers
  const handlePrev = () => {
    const next = new Date(currentDate)
    if (viewMode === "day") {
      next.setDate(next.getDate() - 1)
    } else if (viewMode === "week") {
      next.setDate(next.getDate() - 7)
    } else {
      next.setMonth(next.getMonth() - 1)
    }
    setCurrentDate(next)
  }

  const handleNext = () => {
    const next = new Date(currentDate)
    if (viewMode === "day") {
      next.setDate(next.getDate() + 1)
    } else if (viewMode === "week") {
      next.setDate(next.getDate() + 7)
    } else {
      next.setMonth(next.getMonth() + 1)
    }
    setCurrentDate(next)
  }

  const handleToday = () => {
    setCurrentDate(new Date())
  }

  // Calculate week days for the week view
  const weekDays = React.useMemo(() => {
    const start = new Date(currentDate)
    const day = start.getDay() // 0 = Sun
    // Align to Sunday
    start.setDate(start.getDate() - day)
    start.setHours(0, 0, 0, 0)

    const days: { date: Date; dateStr: string; dayName: string; dayNum: number; isToday: boolean }[] = []
    for (let i = 0; i < 7; i++) {
      const d = new Date(start)
      d.setDate(start.getDate() + i)
      const dStr = toDateStr(d)
      days.push({
        date: d,
        dateStr: dStr,
        dayName: d.toLocaleDateString("en-US", { weekday: "short" }),
        dayNum: d.getDate(),
        isToday: dStr === todayStr,
      })
    }
    return days
  }, [currentDate, todayStr])

  // Calculate month cells for the month view
  const monthCells = React.useMemo(() => {
    const year = currentDate.getFullYear()
    const month = currentDate.getMonth()

    const firstDay = new Date(year, month, 1)
    const lastDay = new Date(year, month + 1, 0)

    const startOffset = firstDay.getDay() // 0 = Sun
    const totalDays = lastDay.getDate()

    const cells: {
      date: Date
      dateStr: string
      dayNum: number
      isCurrentMonth: boolean
      isToday: boolean
    }[] = []

    // Previous month fill
    for (let i = startOffset - 1; i >= 0; i--) {
      const d = new Date(year, month, -i)
      const dStr = toDateStr(d)
      cells.push({
        date: d,
        dateStr: dStr,
        dayNum: d.getDate(),
        isCurrentMonth: false,
        isToday: dStr === todayStr,
      })
    }

    // Current month days
    for (let i = 1; i <= totalDays; i++) {
      const d = new Date(year, month, i)
      const dStr = toDateStr(d)
      cells.push({
        date: d,
        dateStr: dStr,
        dayNum: i,
        isCurrentMonth: true,
        isToday: dStr === todayStr,
      })
    }

    // Trailing next month days (to complete multiple of 7)
    const remaining = 42 - cells.length
    for (let i = 1; i <= remaining; i++) {
      const d = new Date(year, month + 1, i)
      const dStr = toDateStr(d)
      cells.push({
        date: d,
        dateStr: dStr,
        dayNum: d.getDate(),
        isCurrentMonth: false,
        isToday: dStr === todayStr,
      })
    }

    return cells
  }, [currentDate, todayStr])

  // Mini-calendar date matrix for sidebar
  const miniCalendarCells = React.useMemo(() => {
    const year = currentDate.getFullYear()
    const month = currentDate.getMonth()

    const firstDay = new Date(year, month, 1)
    const lastDay = new Date(year, month + 1, 0)
    const startOffset = firstDay.getDay()
    const totalDays = lastDay.getDate()

    const cells: { date: Date; dateStr: string; dayNum: number; isCurrentMonth: boolean; isSelected: boolean }[] = []

    for (let i = startOffset - 1; i >= 0; i--) {
      const d = new Date(year, month, -i)
      cells.push({
        date: d,
        dateStr: toDateStr(d),
        dayNum: d.getDate(),
        isCurrentMonth: false,
        isSelected: toDateStr(d) === toDateStr(currentDate),
      })
    }

    for (let i = 1; i <= totalDays; i++) {
      const d = new Date(year, month, i)
      cells.push({
        date: d,
        dateStr: toDateStr(d),
        dayNum: i,
        isCurrentMonth: true,
        isSelected: toDateStr(d) === toDateStr(currentDate),
      })
    }

    const remaining = 35 - cells.length
    if (remaining > 0) {
      for (let i = 1; i <= remaining; i++) {
        const d = new Date(year, month + 1, i)
        cells.push({
          date: d,
          dateStr: toDateStr(d),
          dayNum: d.getDate(),
          isCurrentMonth: false,
          isSelected: toDateStr(d) === toDateStr(currentDate),
        })
      }
    }

    return cells
  }, [currentDate])

  // Formatted date title
  const dateTitle = React.useMemo(() => {
    if (viewMode === "day") {
      return currentDate.toLocaleDateString("en-US", {
        weekday: "short",
        month: "long",
        day: "numeric",
        year: "numeric",
      })
    }
    if (viewMode === "week") {
      const start = weekDays[0].date
      const end = weekDays[6].date
      const startMonth = start.toLocaleDateString("en-US", { month: "short" })
      const endMonth = end.toLocaleDateString("en-US", { month: "short" })
      if (startMonth === endMonth) {
        return `${startMonth} ${start.getDate()} – ${end.getDate()}, ${start.getFullYear()}`
      }
      return `${startMonth} ${start.getDate()} – ${endMonth} ${end.getDate()}, ${end.getFullYear()}`
    }
    return currentDate.toLocaleDateString("en-US", { month: "long", year: "numeric" })
  }, [currentDate, viewMode, weekDays])

  // Format hour label
  const formatHour = (hour: number) => {
    if (hour === 0) return "12 AM"
    if (hour < 12) return `${hour} AM`
    if (hour === 12) return "12 PM"
    return `${hour - 12} PM`
  }

  // Handle adding new workload
  const handleCreateEvent = (e: React.FormEvent) => {
    e.preventDefault()
    if (!newEventTitle.trim()) return

    const newEv: CalendarEvent = {
      id: `WL-${Math.floor(100 + Math.random() * 900)}`,
      title: newEventTitle.trim(),
      type: newEventType,
      cluster: newEventCluster,
      powerKw: newEventPowerKw,
      savings: "$890 (24%)",
      status: "Scheduled",
      dateStr: toDateStr(currentDate),
      startHour: newEventStartHour,
      startMinute: 0,
      durationHours: newEventDuration,
      colorScheme: TYPE_CONFIG[newEventType],
      notes: "Manually scheduled workload via Calendar overview.",
    }

    setEvents((prev) => [newEv, ...prev])
    setIsCreateModalOpen(false)
    setNewEventTitle("")
    setSelectedEvent(newEv)
  }

  return (
    <div className="flex flex-col h-[820px] bg-background text-foreground rounded-xl border border-border shadow-sm overflow-hidden font-sans">
      {/* 1. TOP GOOGLE CALENDAR HEADER / TOOLBAR */}
      <header className="flex items-center justify-between px-4 py-2.5 border-b border-border bg-card/60 backdrop-blur-sm select-none gap-2">
        {/* Left branding & navigation */}
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setIsSidebarOpen(!isSidebarOpen)}
            className="h-8 w-8 p-0 text-muted-foreground hover:text-foreground"
            title="Toggle sidebar"
          >
            <SlidersHorizontal className="h-4 w-4" />
          </Button>

          <div className="flex items-center gap-2 mr-2">
            <div className="h-8 w-8 rounded-lg bg-blue-600/10 border border-blue-500/20 flex items-center justify-center text-blue-600 dark:text-blue-400">
              <CalendarIcon className="h-4 w-4" />
            </div>
            <div className="hidden sm:block">
              <span className="font-semibold text-sm tracking-tight">GACS Schedule</span>
              <span className="text-[10px] ml-1.5 px-1.5 py-0.5 rounded-full bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 font-medium">
                Live Overview
              </span>
            </div>
          </div>

          <Button
            variant="outline"
            size="sm"
            onClick={handleToday}
            className="h-8 px-3 text-xs font-medium rounded-md hover:bg-muted"
          >
            Today
          </Button>

          <div className="flex items-center gap-0.5">
            <Button
              variant="ghost"
              size="sm"
              onClick={handlePrev}
              className="h-8 w-8 p-0 rounded-full text-muted-foreground hover:text-foreground"
              title="Previous"
            >
              <ChevronLeft className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={handleNext}
              className="h-8 w-8 p-0 rounded-full text-muted-foreground hover:text-foreground"
              title="Next"
            >
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>

          <h2 className="text-sm md:text-base font-semibold text-foreground tracking-tight ml-1 min-w-[140px]">
            {dateTitle}
          </h2>
        </div>

        {/* Center search bar */}
        <div className="hidden md:flex items-center relative max-w-xs w-full mx-4">
          <Search className="absolute left-2.5 h-3.5 w-3.5 text-muted-foreground" />
          <input
            type="text"
            placeholder="Search scheduled jobs, clusters..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full h-8 pl-8 pr-3 text-xs rounded-lg bg-muted/60 border border-border/80 focus:bg-background focus:outline-none focus:ring-1 focus:ring-primary placeholder:text-muted-foreground/70"
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery("")}
              className="absolute right-2 text-muted-foreground hover:text-foreground"
            >
              <X className="h-3 w-3" />
            </button>
          )}
        </div>

        {/* Right view switcher & create button */}
        <div className="flex items-center gap-2">
          {/* View mode switcher */}
          <div className="inline-flex rounded-lg border border-border bg-muted/40 p-0.5 text-xs">
            <button
              onClick={() => setViewMode("week")}
              className={cn(
                "px-2.5 py-1 rounded-md font-medium transition-all",
                viewMode === "week"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              Week
            </button>
            <button
              onClick={() => setViewMode("month")}
              className={cn(
                "px-2.5 py-1 rounded-md font-medium transition-all",
                viewMode === "month"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              Month
            </button>
            <button
              onClick={() => setViewMode("day")}
              className={cn(
                "px-2.5 py-1 rounded-md font-medium transition-all",
                viewMode === "day"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              Day
            </button>
            <button
              onClick={() => setViewMode("schedule")}
              className={cn(
                "px-2.5 py-1 rounded-md font-medium transition-all",
                viewMode === "schedule"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              Agenda
            </button>
          </div>

          {/* "+ Create" button Google Calendar Style */}
          <Button
            size="sm"
            onClick={() => setIsCreateModalOpen(true)}
            className="h-8 px-3 text-xs bg-primary text-primary-foreground hover:bg-primary/90 rounded-md font-medium shadow-xs gap-1.5"
          >
            <Plus className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">New Schedule</span>
          </Button>
        </div>
      </header>

      {/* 2. BODY CONTENT: SIDEBAR + MAIN CALENDAR */}
      <div className="flex flex-1 overflow-hidden relative">
        {/* COLLAPSIBLE LEFT SIDEBAR (GOOGLE CALENDAR STYLE) */}
        {isSidebarOpen && (
          <aside className="w-64 border-r border-border bg-card/40 flex flex-col p-3.5 gap-4 overflow-y-auto select-none shrink-0 transition-all">
            {/* Quick Action Button */}
            <button
              onClick={() => setIsCreateModalOpen(true)}
              className="flex items-center gap-2.5 px-4 py-2.5 rounded-full border border-border shadow-xs bg-background hover:shadow-md hover:bg-muted/30 transition-all text-xs font-semibold text-foreground group"
            >
              <div className="h-5 w-5 rounded-full bg-blue-500/10 text-blue-600 flex items-center justify-center group-hover:rotate-90 transition-transform">
                <Plus className="h-3.5 w-3.5" />
              </div>
              <span>Schedule Workload</span>
            </button>

            {/* Mini Month Calendar */}
            <div className="border border-border/70 rounded-lg p-2.5 bg-background/50">
              <div className="flex items-center justify-between mb-2 px-1">
                <span className="text-xs font-semibold text-foreground">
                  {currentDate.toLocaleDateString("en-US", { month: "short", year: "numeric" })}
                </span>
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => {
                      const d = new Date(currentDate)
                      d.setMonth(d.getMonth() - 1)
                      setCurrentDate(d)
                    }}
                    className="h-5 w-5 rounded hover:bg-muted flex items-center justify-center text-muted-foreground"
                  >
                    <ChevronLeft className="h-3 w-3" />
                  </button>
                  <button
                    onClick={() => {
                      const d = new Date(currentDate)
                      d.setMonth(d.getMonth() + 1)
                      setCurrentDate(d)
                    }}
                    className="h-5 w-5 rounded hover:bg-muted flex items-center justify-center text-muted-foreground"
                  >
                    <ChevronRight className="h-3 w-3" />
                  </button>
                </div>
              </div>

              {/* Day headers */}
              <div className="grid grid-cols-7 gap-1 text-center text-[10px] font-semibold text-muted-foreground mb-1">
                <span>S</span>
                <span>M</span>
                <span>T</span>
                <span>W</span>
                <span>T</span>
                <span>F</span>
                <span>S</span>
              </div>

              {/* Days grid */}
              <div className="grid grid-cols-7 gap-1 text-center text-[11px]">
                {miniCalendarCells.map((cell, idx) => (
                  <button
                    key={idx}
                    onClick={() => {
                      setCurrentDate(cell.date)
                    }}
                    className={cn(
                      "h-6 w-6 mx-auto rounded-full flex items-center justify-center transition-colors",
                      cell.isCurrentMonth ? "text-foreground font-medium" : "text-muted-foreground/50",
                      cell.isSelected && "bg-blue-600 text-white font-bold",
                      !cell.isSelected && cell.dateStr === todayStr && "border border-blue-500 font-bold text-blue-600",
                      !cell.isSelected && "hover:bg-muted"
                    )}
                  >
                    {cell.dayNum}
                  </button>
                ))}
              </div>
            </div>

            {/* Calendar Cluster / Type Filters */}
            <div className="flex flex-col gap-2">
              <div className="flex items-center justify-between px-1">
                <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                  Workload Categories
                </span>
                <span className="text-[10px] text-muted-foreground font-mono">{filteredEvents.length} active</span>
              </div>

              <div className="space-y-1">
                {(Object.keys(TYPE_CONFIG) as WorkloadType[]).map((typeKey) => {
                  const cfg = TYPE_CONFIG[typeKey]
                  const isChecked = activeTypes[typeKey]
                  const count = events.filter((e) => e.type === typeKey).length

                  return (
                    <label
                      key={typeKey}
                      onClick={() =>
                        setActiveTypes((prev) => ({
                          ...prev,
                          [typeKey]: !prev[typeKey],
                        }))
                      }
                      className="flex items-center justify-between px-2 py-1.5 rounded-md hover:bg-muted/60 cursor-pointer text-xs transition-colors"
                    >
                      <div className="flex items-center gap-2">
                        <div
                          className={cn(
                            "h-3.5 w-3.5 rounded border flex items-center justify-center transition-colors",
                            isChecked
                              ? `${cfg.dot} border-transparent text-white`
                              : "border-muted-foreground/40 bg-background"
                          )}
                        >
                          {isChecked && <Check className="h-2.5 w-2.5 stroke-[3]" />}
                        </div>
                        <span className={cn("text-xs", isChecked ? "text-foreground font-medium" : "text-muted-foreground line-through")}>
                          {cfg.name}
                        </span>
                      </div>
                      <span className="text-[10px] text-muted-foreground font-mono">
                        {count}
                      </span>
                    </label>
                  )
                })}
              </div>
            </div>

            {/* Grid Awareness Info Card */}
            <div className="mt-auto p-3 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-xs text-foreground">
              <div className="flex items-center gap-1.5 font-semibold text-emerald-600 dark:text-emerald-400 mb-1">
                <Sparkles className="h-3.5 w-3.5" />
                <span>Grid Price Dispatch</span>
              </div>
              <p className="text-[11px] text-muted-foreground leading-relaxed">
                Color-coded blocks represent optimized execution windows calculated from wholesale grid nodal prices.
              </p>
            </div>
          </aside>
        )}

        {/* MAIN CALENDAR DISPLAY AREA */}
        <main className="flex-1 flex flex-col overflow-hidden bg-background">
          {/* ======================================================== */}
          {/* VIEW 1: WEEK VIEW (SIGNATURE GOOGLE CALENDAR TIME GRID) */}
          {/* ======================================================== */}
          {viewMode === "week" && (
            <div className="flex-1 flex flex-col overflow-y-auto">
              {/* Day header row */}
              <div className="sticky top-0 z-20 flex border-b border-border bg-card/95 backdrop-blur-sm">
                {/* Time gutter spacer */}
                <div className="w-16 shrink-0 border-r border-border/80 flex items-end justify-center pb-2 text-[10px] font-mono text-muted-foreground">
                  UTC
                </div>
                {/* 7 Days headers */}
                <div className="grid grid-cols-7 flex-1 divide-x divide-border/80">
                  {weekDays.map((colDay, idx) => (
                    <div
                      key={idx}
                      className={cn(
                        "py-2 px-2 text-center transition-colors",
                        colDay.isToday && "bg-blue-500/5 dark:bg-blue-500/10"
                      )}
                    >
                      <div className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
                        {colDay.dayName}
                      </div>
                      <div
                        className={cn(
                          "inline-flex items-center justify-center h-7 w-7 rounded-full text-xs font-bold mt-0.5",
                          colDay.isToday
                            ? "bg-blue-600 text-white shadow-xs"
                            : "text-foreground"
                        )}
                      >
                        {colDay.dayNum}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Time grid body */}
              <div className="flex flex-1 relative min-h-[1440px]">
                {/* Vertical time labels */}
                <div className="w-16 shrink-0 border-r border-border/80 select-none divide-y divide-transparent">
                  {HOURS_OF_DAY.map((h) => (
                    <div
                      key={h}
                      className="h-[60px] relative text-[10px] font-mono text-muted-foreground pr-2 text-right pt-0.5"
                    >
                      <span>{formatHour(h)}</span>
                    </div>
                  ))}
                </div>

                {/* 7 Days Columns */}
                <div className="grid grid-cols-7 flex-1 divide-x divide-border/80 relative">
                  {weekDays.map((colDay, colIdx) => {
                    const dayEvents = filteredEvents.filter((e) => e.dateStr === colDay.dateStr)

                    return (
                      <div
                        key={colIdx}
                        className={cn(
                          "relative h-full",
                          colDay.isToday && "bg-blue-500/[0.02]"
                        )}
                      >
                        {/* 24 Horizontal hour lines */}
                        {HOURS_OF_DAY.map((h) => (
                          <div
                            key={h}
                            className="h-[60px] border-b border-border/40 hover:bg-muted/15 transition-colors"
                          />
                        ))}

                        {/* Red current time indicator line if today */}
                        {colDay.isToday && (
                          <div
                            className="absolute left-0 right-0 z-10 pointer-events-none flex items-center"
                            style={{ top: `${currentHourDecimal * 60}px` }}
                          >
                            <div className="h-2.5 w-2.5 rounded-full bg-red-500 -ml-1 shadow-xs" />
                            <div className="flex-1 h-[2px] bg-red-500" />
                          </div>
                        )}

                        {/* Event blocks */}
                        {dayEvents.map((ev) => {
                          const topPx = ev.startHour * 60 + (ev.startMinute / 60) * 60
                          const heightPx = Math.max(ev.durationHours * 60 - 3, 24)

                          return (
                            <button
                              key={ev.id}
                              onClick={() => setSelectedEvent(ev)}
                              style={{
                                top: `${topPx}px`,
                                height: `${heightPx}px`,
                              }}
                              className={cn(
                                "absolute left-1 right-1 rounded-md p-1.5 text-left text-xs overflow-hidden transition-all shadow-xs cursor-pointer z-10 hover:shadow-md hover:scale-[1.01]",
                                ev.colorScheme.bg,
                                ev.colorScheme.border
                              )}
                            >
                              <div className="flex items-center justify-between gap-1">
                                <span className={cn("font-semibold truncate text-[11px]", ev.colorScheme.text)}>
                                  {ev.title}
                                </span>
                                <span className="text-[9px] font-mono shrink-0 px-1 rounded bg-background/60 text-foreground font-medium">
                                  {ev.powerKw > 0 ? `${ev.powerKw} kW` : "ALERT"}
                                </span>
                              </div>

                              <div className="flex items-center gap-1.5 mt-0.5 text-[10px] text-muted-foreground font-mono">
                                <Clock className="h-2.5 w-2.5 shrink-0" />
                                <span>
                                  {String(ev.startHour).padStart(2, "0")}:{String(ev.startMinute).padStart(2, "0")} (
                                  {ev.durationHours}h)
                                </span>
                              </div>

                              {heightPx > 45 && (
                                <div className="mt-1 flex items-center justify-between text-[10px] text-muted-foreground truncate">
                                  <span className="truncate">{ev.cluster}</span>
                                  <span className="font-semibold text-emerald-600 dark:text-emerald-400 font-mono shrink-0">
                                    {ev.savings}
                                  </span>
                                </div>
                              )}
                            </button>
                          )
                        })}
                      </div>
                    )
                  })}
                </div>
              </div>
            </div>
          )}

          {/* ======================================================== */}
          {/* VIEW 2: MONTH VIEW (GOOGLE CALENDAR 7X6 GRID)           */}
          {/* ======================================================== */}
          {viewMode === "month" && (
            <div className="flex-1 flex flex-col overflow-y-auto">
              {/* Day header row */}
              <div className="grid grid-cols-7 border-b border-border bg-card/95 sticky top-0 z-10 text-center py-2 text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                <span>Sun</span>
                <span>Mon</span>
                <span>Tue</span>
                <span>Wed</span>
                <span>Thu</span>
                <span>Fri</span>
                <span>Sat</span>
              </div>

              {/* 7x6 month grid */}
              <div className="grid grid-cols-7 flex-1 auto-rows-fr divide-x divide-y divide-border/60 min-h-[640px]">
                {monthCells.map((cell, idx) => {
                  const dayEvents = filteredEvents.filter((e) => e.dateStr === cell.dateStr)

                  return (
                    <div
                      key={idx}
                      onClick={() => {
                        setCurrentDate(cell.date)
                        setViewMode("day")
                      }}
                      className={cn(
                        "p-1.5 flex flex-col min-h-[95px] transition-colors cursor-pointer hover:bg-muted/30 group",
                        !cell.isCurrentMonth && "bg-muted/15 text-muted-foreground/60",
                        cell.isToday && "bg-blue-500/[0.04]"
                      )}
                    >
                      <div className="flex items-center justify-between mb-1">
                        <span
                          className={cn(
                            "h-5 w-5 rounded-full inline-flex items-center justify-center text-xs font-semibold",
                            cell.isToday
                              ? "bg-blue-600 text-white font-bold"
                              : cell.isCurrentMonth
                              ? "text-foreground group-hover:bg-muted"
                              : "text-muted-foreground/50"
                          )}
                        >
                          {cell.dayNum}
                        </span>
                        {dayEvents.length > 0 && (
                          <span className="text-[10px] text-muted-foreground font-mono">
                            {dayEvents.length} job{dayEvents.length > 1 ? "s" : ""}
                          </span>
                        )}
                      </div>

                      {/* Event Chips */}
                      <div className="space-y-1 overflow-hidden flex-1">
                        {dayEvents.slice(0, 3).map((ev) => (
                          <button
                            key={ev.id}
                            onClick={(e) => {
                              e.stopPropagation()
                              setSelectedEvent(ev)
                            }}
                            className={cn(
                              "w-full text-left px-1.5 py-0.5 rounded text-[10px] font-medium truncate flex items-center gap-1 hover:brightness-95 transition-all",
                              ev.colorScheme.bg,
                              ev.colorScheme.text
                            )}
                          >
                            <span className={cn("h-1.5 w-1.5 rounded-full shrink-0", ev.colorScheme.dot)} />
                            <span className="font-mono text-[9px] shrink-0">
                              {String(ev.startHour).padStart(2, "0")}:00
                            </span>
                            <span className="truncate">{ev.title}</span>
                          </button>
                        ))}

                        {dayEvents.length > 3 && (
                          <div className="text-[10px] text-muted-foreground font-semibold px-1">
                            +{dayEvents.length - 3} more
                          </div>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* ======================================================== */}
          {/* VIEW 3: DAY VIEW (DETAILED 24H TIMELINE)                */}
          {/* ======================================================== */}
          {viewMode === "day" && (
            <div className="flex-1 flex flex-col overflow-y-auto">
              <div className="sticky top-0 z-20 flex border-b border-border bg-card/95 px-6 py-3 items-center justify-between">
                <div>
                  <h3 className="text-base font-bold text-foreground">
                    {currentDate.toLocaleDateString("en-US", { weekday: "long", month: "long", day: "numeric" })}
                  </h3>
                  <p className="text-xs text-muted-foreground">
                    {filteredEvents.filter((e) => e.dateStr === toDateStr(currentDate)).length} workloads scheduled for
                    this 24-hour cycle
                  </p>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setIsCreateModalOpen(true)}
                  className="text-xs"
                >
                  <Plus className="h-3.5 w-3.5 mr-1" /> Add to this day
                </Button>
              </div>

              <div className="flex flex-1 relative min-h-[1440px]">
                {/* Time gutter */}
                <div className="w-20 shrink-0 border-r border-border select-none">
                  {HOURS_OF_DAY.map((h) => (
                    <div
                      key={h}
                      className="h-[60px] text-xs font-mono text-muted-foreground pr-3 text-right pt-0.5"
                    >
                      {formatHour(h)}
                    </div>
                  ))}
                </div>

                {/* Day column */}
                <div className="flex-1 relative">
                  {HOURS_OF_DAY.map((h) => (
                    <div key={h} className="h-[60px] border-b border-border/40 hover:bg-muted/10 transition-colors" />
                  ))}

                  {/* Red line if today */}
                  {toDateStr(currentDate) === todayStr && (
                    <div
                      className="absolute left-0 right-0 z-10 pointer-events-none flex items-center"
                      style={{ top: `${currentHourDecimal * 60}px` }}
                    >
                      <div className="h-3 w-3 rounded-full bg-red-500 -ml-1.5 shadow-xs" />
                      <div className="flex-1 h-[2px] bg-red-500" />
                    </div>
                  )}

                  {/* Events */}
                  {filteredEvents
                    .filter((e) => e.dateStr === toDateStr(currentDate))
                    .map((ev) => {
                      const topPx = ev.startHour * 60 + (ev.startMinute / 60) * 60
                      const heightPx = Math.max(ev.durationHours * 60 - 4, 36)

                      return (
                        <button
                          key={ev.id}
                          onClick={() => setSelectedEvent(ev)}
                          style={{
                            top: `${topPx}px`,
                            height: `${heightPx}px`,
                          }}
                          className={cn(
                            "absolute left-4 right-8 rounded-lg p-3 text-left overflow-hidden transition-all shadow-xs cursor-pointer z-10 hover:shadow-lg hover:ring-2 hover:ring-primary/40",
                            ev.colorScheme.bg,
                            ev.colorScheme.border
                          )}
                        >
                          <div className="flex items-center justify-between">
                            <span className={cn("font-bold text-sm", ev.colorScheme.text)}>{ev.title}</span>
                            <span className="text-xs font-mono px-2 py-0.5 rounded bg-background/80 font-bold">
                              {ev.powerKw} kW
                            </span>
                          </div>
                          <div className="flex items-center gap-4 mt-1 text-xs text-muted-foreground">
                            <span className="font-mono">
                              {String(ev.startHour).padStart(2, "0")}:{String(ev.startMinute).padStart(2, "0")} –{" "}
                              {String(Math.floor(ev.startHour + ev.durationHours)).padStart(2, "0")}:00 (
                              {ev.durationHours} hrs)
                            </span>
                            <span>•</span>
                            <span className="font-medium text-foreground">{ev.cluster}</span>
                            <span>•</span>
                            <span className="font-mono text-emerald-600 dark:text-emerald-400 font-bold">
                              Savings: {ev.savings}
                            </span>
                          </div>
                          {ev.notes && <p className="mt-1 text-xs text-muted-foreground italic">{ev.notes}</p>}
                        </button>
                      )
                    })}
                </div>
              </div>
            </div>
          )}

          {/* ======================================================== */}
          {/* VIEW 4: SCHEDULE / AGENDA VIEW (CHRONOLOGICAL LIST)     */}
          {/* ======================================================== */}
          {viewMode === "schedule" && (
            <div className="flex-1 overflow-y-auto p-4 sm:p-6 space-y-4">
              <div className="flex items-center justify-between pb-3 border-b border-border">
                <div>
                  <h3 className="text-base font-bold text-foreground">Agenda & Dispatch Schedule</h3>
                  <p className="text-xs text-muted-foreground">Chronological sequence of all compute jobs and curtailments</p>
                </div>
                <div className="text-xs text-muted-foreground font-mono">
                  Showing {filteredEvents.length} scheduled jobs
                </div>
              </div>

              {filteredEvents.length === 0 ? (
                <div className="text-center py-16 text-muted-foreground">
                  <CalendarDays className="h-10 w-10 mx-auto mb-2 opacity-40" />
                  <p className="text-sm font-medium">No schedules match your current filters.</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {filteredEvents.map((ev) => (
                    <div
                      key={ev.id}
                      onClick={() => setSelectedEvent(ev)}
                      className={cn(
                        "p-4 rounded-xl border transition-all cursor-pointer hover:shadow-md flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3",
                        ev.colorScheme.border,
                        "bg-card hover:bg-muted/30"
                      )}
                    >
                      <div className="flex items-start gap-3">
                        <div
                          className={cn(
                            "h-10 w-10 rounded-lg flex items-center justify-center shrink-0 mt-0.5",
                            ev.colorScheme.bg
                          )}
                        >
                          <Server className="h-5 w-5" style={{ color: ev.colorScheme.dot }} />
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="font-bold text-sm text-foreground">{ev.title}</span>
                            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-muted text-muted-foreground">
                              {ev.id}
                            </span>
                            <span className={cn("text-[10px] px-2 py-0.5 rounded-full font-semibold", ev.colorScheme.badge)}>
                              {ev.type}
                            </span>
                          </div>
                          <div className="flex flex-wrap items-center gap-3 mt-1.5 text-xs text-muted-foreground">
                            <span className="font-mono text-foreground font-medium">{ev.cluster}</span>
                            <span>•</span>
                            <span className="font-mono">
                              Date: {ev.dateStr} at {String(ev.startHour).padStart(2, "0")}:{String(ev.startMinute).padStart(2, "0")}
                            </span>
                            <span>•</span>
                            <span>Duration: {ev.durationHours}h</span>
                          </div>
                        </div>
                      </div>

                      <div className="flex items-center gap-4 self-end sm:self-center">
                        <div className="text-right">
                          <div className="text-xs font-mono font-bold text-foreground">{ev.powerKw} kW</div>
                          <div className="text-[11px] font-mono font-semibold text-emerald-600 dark:text-emerald-400">
                            {ev.savings}
                          </div>
                        </div>
                        <span
                          className={cn(
                            "px-2.5 py-1 rounded-full text-xs font-semibold inline-flex items-center gap-1.5",
                            ev.status === "Running"
                              ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                              : "bg-blue-500/15 text-blue-600 dark:text-blue-400"
                          )}
                        >
                          <span
                            className={cn(
                              "h-1.5 w-1.5 rounded-full",
                              ev.status === "Running" ? "bg-emerald-500 animate-pulse" : "bg-blue-500"
                            )}
                          />
                          {ev.status}
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </main>
      </div>

      {/* 3. EVENT DETAILS MODAL (GOOGLE CALENDAR DIALOG STYLE) */}
      {selectedEvent && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-xs animate-in fade-in duration-150">
          <div
            className="w-full max-w-lg rounded-2xl bg-card border border-border shadow-2xl overflow-hidden p-6 relative animate-in zoom-in-95 duration-150"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Close button */}
            <button
              onClick={() => setSelectedEvent(null)}
              className="absolute top-4 right-4 h-8 w-8 rounded-full hover:bg-muted flex items-center justify-center text-muted-foreground hover:text-foreground"
            >
              <X className="h-4 w-4" />
            </button>

            {/* Header tag */}
            <div className="flex items-center gap-2 mb-2">
              <span className={cn("text-xs font-semibold px-2.5 py-0.5 rounded-full", selectedEvent.colorScheme.badge)}>
                {selectedEvent.type}
              </span>
              <span className="text-xs font-mono text-muted-foreground">{selectedEvent.id}</span>
              <span
                className={cn(
                  "ml-auto text-xs px-2 py-0.5 rounded font-semibold",
                  selectedEvent.status === "Running"
                    ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                    : "bg-muted text-muted-foreground"
                )}
              >
                {selectedEvent.status}
              </span>
            </div>

            <h3 className="text-lg font-bold text-foreground mb-3">{selectedEvent.title}</h3>

            <div className="space-y-3 py-2 text-xs">
              <div className="flex items-center gap-3 text-muted-foreground">
                <CalendarIcon className="h-4 w-4 text-blue-500 shrink-0" />
                <span className="font-medium text-foreground">
                  {selectedEvent.dateStr} • {String(selectedEvent.startHour).padStart(2, "0")}:
                  {String(selectedEvent.startMinute).padStart(2, "0")} UTC ({selectedEvent.durationHours} hours)
                </span>
              </div>

              <div className="flex items-center gap-3 text-muted-foreground">
                <Server className="h-4 w-4 text-purple-500 shrink-0" />
                <span>
                  Hardware Cluster: <strong className="text-foreground">{selectedEvent.cluster}</strong>
                </span>
              </div>

              <div className="flex items-center gap-3 text-muted-foreground">
                <Zap className="h-4 w-4 text-amber-500 shrink-0" />
                <span>
                  Power Draw: <strong className="text-foreground">{selectedEvent.powerKw} kW</strong>
                </span>
              </div>

              <div className="flex items-center gap-3 text-muted-foreground">
                <DollarSign className="h-4 w-4 text-emerald-500 shrink-0" />
                <span>
                  Grid Tariff Optimization:{" "}
                  <strong className="text-emerald-600 dark:text-emerald-400">{selectedEvent.savings}</strong>
                </span>
              </div>

              {selectedEvent.notes && (
                <div className="p-3 rounded-lg bg-muted/60 border border-border/80 text-muted-foreground mt-2">
                  <div className="font-semibold text-foreground text-[11px] mb-1">Dispatch Notes</div>
                  <p className="text-[11px] leading-relaxed">{selectedEvent.notes}</p>
                </div>
              )}
            </div>

            <div className="flex items-center justify-between gap-3 mt-6 pt-4 border-t border-border">
              <Button
                variant="destructive"
                size="sm"
                onClick={() => {
                  setEvents((prev) => prev.filter((e) => e.id !== selectedEvent.id))
                  setSelectedEvent(null)
                }}
                className="text-xs h-8"
              >
                Cancel Schedule
              </Button>

              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setSelectedEvent(null)}
                  className="text-xs h-8"
                >
                  Close
                </Button>
                <Button
                  size="sm"
                  onClick={() => {
                    setEvents((prev) =>
                      prev.map((e) =>
                        e.id === selectedEvent.id
                          ? { ...e, status: e.status === "Running" ? "Scheduled" : "Running" }
                          : e
                      )
                    )
                    setSelectedEvent((prev) =>
                      prev ? { ...prev, status: prev.status === "Running" ? "Scheduled" : "Running" } : null
                    )
                  }}
                  className="text-xs h-8 bg-primary text-primary-foreground"
                >
                  {selectedEvent.status === "Running" ? "Pause Job" : "Dispatch Now"}
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* 4. CREATE NEW WORKLOAD MODAL */}
      {isCreateModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-xs animate-in fade-in duration-150">
          <div
            className="w-full max-w-md rounded-2xl bg-card border border-border shadow-2xl p-6 relative animate-in zoom-in-95 duration-150"
            onClick={(e) => e.stopPropagation()}
          >
            <button
              onClick={() => setIsCreateModalOpen(false)}
              className="absolute top-4 right-4 h-8 w-8 rounded-full hover:bg-muted flex items-center justify-center text-muted-foreground hover:text-foreground"
            >
              <X className="h-4 w-4" />
            </button>

            <h3 className="text-base font-bold text-foreground mb-1">Schedule New Workload</h3>
            <p className="text-xs text-muted-foreground mb-4">Add a compute job or maintenance window to the calendar</p>

            <form onSubmit={handleCreateEvent} className="space-y-3.5 text-xs">
              <div>
                <label className="block font-medium text-foreground mb-1">Workload Name</label>
                <input
                  type="text"
                  required
                  placeholder="e.g. Llama-3-70B Fine-Tuning #5"
                  value={newEventTitle}
                  onChange={(e) => setNewEventTitle(e.target.value)}
                  className="w-full h-8 px-3 rounded-lg bg-muted/60 border border-border focus:bg-background focus:outline-none focus:ring-1 focus:ring-primary text-xs"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block font-medium text-foreground mb-1">Category</label>
                  <select
                    value={newEventType}
                    onChange={(e) => setNewEventType(e.target.value as WorkloadType)}
                    className="w-full h-8 px-2 rounded-lg bg-muted/60 border border-border focus:bg-background focus:outline-none focus:ring-1 focus:ring-primary text-xs"
                  >
                    <option value="ML Training">ML Training</option>
                    <option value="Crypto Mining">Crypto Mining</option>
                    <option value="HPC Batch">HPC Batch</option>
                    <option value="HVAC Pre-Cool">HVAC Pre-Cool</option>
                    <option value="Grid Alert">Grid Peak Alert</option>
                  </select>
                </div>

                <div>
                  <label className="block font-medium text-foreground mb-1">Cluster</label>
                  <select
                    value={newEventCluster}
                    onChange={(e) => setNewEventCluster(e.target.value)}
                    className="w-full h-8 px-2 rounded-lg bg-muted/60 border border-border focus:bg-background focus:outline-none focus:ring-1 focus:ring-primary text-xs"
                  >
                    <option value="64x NVIDIA H100 SXM5">64x NVIDIA H100 SXM5</option>
                    <option value="128x NVIDIA H100 SXM5">128x NVIDIA H100 SXM5</option>
                    <option value="Slurm HPC Cluster (96 Nodes)">Slurm HPC Cluster</option>
                    <option value="Antminer S19 Pro+ Pod 2">Antminer S19 Pod</option>
                    <option value="Trane Centrifugal Chiller Bank">Trane Chiller Bank</option>
                  </select>
                </div>
              </div>

              <div className="grid grid-cols-3 gap-2">
                <div>
                  <label className="block font-medium text-foreground mb-1">Start Hour</label>
                  <select
                    value={newEventStartHour}
                    onChange={(e) => setNewEventStartHour(Number(e.target.value))}
                    className="w-full h-8 px-2 rounded-lg bg-muted/60 border border-border focus:bg-background focus:outline-none focus:ring-1 focus:ring-primary text-xs font-mono"
                  >
                    {HOURS_OF_DAY.map((h) => (
                      <option key={h} value={h}>
                        {formatHour(h)}
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="block font-medium text-foreground mb-1">Duration (Hrs)</label>
                  <input
                    type="number"
                    min="1"
                    max="24"
                    value={newEventDuration}
                    onChange={(e) => setNewEventDuration(Number(e.target.value))}
                    className="w-full h-8 px-2 rounded-lg bg-muted/60 border border-border focus:bg-background focus:outline-none focus:ring-1 focus:ring-primary text-xs font-mono"
                  />
                </div>

                <div>
                  <label className="block font-medium text-foreground mb-1">Power (kW)</label>
                  <input
                    type="number"
                    min="0"
                    step="50"
                    value={newEventPowerKw}
                    onChange={(e) => setNewEventPowerKw(Number(e.target.value))}
                    className="w-full h-8 px-2 rounded-lg bg-muted/60 border border-border focus:bg-background focus:outline-none focus:ring-1 focus:ring-primary text-xs font-mono"
                  />
                </div>
              </div>

              <div className="flex items-center justify-end gap-2 pt-3 border-t border-border mt-4">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setIsCreateModalOpen(false)}
                  className="text-xs h-8"
                >
                  Cancel
                </Button>
                <Button
                  type="submit"
                  size="sm"
                  className="text-xs h-8 bg-primary text-primary-foreground"
                >
                  Add to Calendar
                </Button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}

export default GoogleCalendarView

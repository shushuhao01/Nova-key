"use client"

import { useCallback, useEffect, useState, type ComponentType } from "react"
import {
  Eye,
  TrendingUp,
  Users,
  Globe2,
  ContactRound,
  Activity,
  Clock,
  MousePointerClick,
  RefreshCw,
  Download,
  Settings2,
  Search,
  X,
  Monitor,
  Smartphone,
  Tablet,
  Bot,
  ChevronLeft,
  ChevronRight,
} from "lucide-react"
import {
  AreaChart,
  Area,
  BarChart,
  Bar,
  PieChart,
  Pie,
  Cell,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from "recharts"
import { cn } from "@/lib/utils"
import { toast } from "sonner"
import { adminVisitApi } from "@/services/api"
import type {
  VisitAnalytics,
  VisitLogItem,
  VisitOptions,
  VisitConfig,
} from "@/types"

const PAGE_SIZE = 20

const QUICK_RANGES: { key: string; label: string }[] = [
  { key: "today", label: "今日" },
  { key: "yesterday", label: "昨日" },
  { key: "7d", label: "近 7 天" },
  { key: "30d", label: "近 30 天" },
  { key: "90d", label: "近 90 天" },
]

const CHART_COLORS = [
  "hsl(217, 91%, 60%)",
  "hsl(160, 84%, 39%)",
  "hsl(38, 92%, 50%)",
  "hsl(280, 65%, 60%)",
  "hsl(0, 72%, 58%)",
  "hsl(190, 80%, 45%)",
  "hsl(330, 70%, 60%)",
  "hsl(90, 60%, 45%)",
]

const SOURCE_COLORS: Record<string, string> = {
  direct: "hsl(217, 91%, 60%)",
  search: "hsl(160, 84%, 39%)",
  social: "hsl(280, 65%, 60%)",
  external: "hsl(38, 92%, 50%)",
}

const DEVICE_ICONS: Record<string, ComponentType<{ className?: string }>> = {
  desktop: Monitor,
  mobile: Smartphone,
  tablet: Tablet,
  bot: Bot,
  unknown: Monitor,
}

function fmtNum(n: number | null | undefined): string {
  const v = typeof n === "number" && isFinite(n) ? n : 0
  return v.toLocaleString("zh-CN")
}

function fmtDuration(sec: number | null | undefined): string {
  const s = Math.max(0, Math.round(sec ?? 0))
  if (s < 60) return `${s} 秒`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} 分 ${s % 60} 秒`
  const h = Math.floor(m / 60)
  return `${h} 小时 ${m % 60} 分`
}

function fmtHour(hour: number | null | undefined): string {
  const h = typeof hour === "number" && hour >= 0 && hour < 24 ? hour : 0
  return `${String(h).padStart(2, "0")}:00`
}

function StatCard({
  icon: Icon,
  label,
  value,
  sub,
  accent = "text-primary",
}: {
  icon: ComponentType<{ className?: string }>
  label: string
  value: string | number
  sub?: string
  accent?: string
}) {
  return (
    <div className="rounded-xl border border-border bg-card p-4 shadow-sm">
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-muted-foreground">{label}</span>
        <Icon className={cn("h-4 w-4", accent)} />
      </div>
      <div className="mt-2 text-2xl font-bold text-foreground">{value}</div>
      {sub ? <div className="mt-1 text-xs text-muted-foreground">{sub}</div> : null}
    </div>
  )
}

function Panel({
  title,
  action,
  children,
  className,
}: {
  title: string
  action?: React.ReactNode
  children: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn("rounded-xl border border-border bg-card p-5 shadow-sm", className)}>
      <div className="mb-4 flex items-center justify-between gap-3">
        <h3 className="font-semibold text-foreground">{title}</h3>
        {action}
      </div>
      {children}
    </div>
  )
}

function Empty({ text = "暂无数据" }: { text?: string }) {
  return (
    <div className="flex items-center justify-center py-10 text-sm text-muted-foreground">{text}</div>
  )
}

function BarList({ items }: { items: { name: string; pv: number; uv?: number }[] }) {
  if (items.length === 0) return <Empty />
  const max = Math.max(1, ...items.map((i) => i.pv))
  return (
    <div className="flex flex-col gap-3">
      {items.map((it, idx) => (
        <div key={`${it.name}-${idx}`} className="flex flex-col gap-1">
          <div className="flex items-center justify-between gap-3 text-sm">
            <span className="truncate text-foreground" title={it.name}>{it.name}</span>
            <span className="shrink-0 text-muted-foreground">
              {fmtNum(it.pv)} PV{it.uv !== undefined ? ` · ${fmtNum(it.uv)} UV` : ""}
            </span>
          </div>
          <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
            <div
              className="h-full rounded-full bg-primary/70"
              style={{ width: `${Math.max(2, (it.pv / max) * 100)}%` }}
            />
          </div>
        </div>
      ))}
    </div>
  )
}

function Toggle({
  checked,
  onChange,
  label,
  desc,
}: {
  checked: boolean
  onChange: (v: boolean) => void
  label: string
  desc?: string
}) {
  return (
    <button
      type="button"
      onClick={() => onChange(!checked)}
      className="flex w-full items-center justify-between gap-3 rounded-lg border border-border px-3 py-2.5 text-left transition-colors hover:bg-accent/50"
    >
      <span>
        <span className="block text-sm font-medium text-foreground">{label}</span>
        {desc ? <span className="block text-xs text-muted-foreground">{desc}</span> : null}
      </span>
      <span className={cn("relative h-5 w-9 shrink-0 rounded-full transition-colors", checked ? "bg-primary" : "bg-muted")}>
        <span
          className={cn(
            "absolute top-0.5 left-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform",
            checked && "translate-x-4"
          )}
        />
      </span>
    </button>
  )
}

export default function AdminVisitPage() {
  const [tab, setTab] = useState<"overview" | "detail">("overview")

  // 日期筛选
  const [range, setRange] = useState("7d")
  const [startDate, setStartDate] = useState("")
  const [endDate, setEndDate] = useState("")
  const [showCustom, setShowCustom] = useState(false)

  const [analytics, setAnalytics] = useState<VisitAnalytics | null>(null)
  const [loading, setLoading] = useState(true)
  const [options, setOptions] = useState<VisitOptions | null>(null)

  // 明细筛选
  const [source, setSource] = useState("")
  const [device, setDevice] = useState("")
  const [ipFilter, setIpFilter] = useState("")
  const [keyword, setKeyword] = useState("")
  const [visits, setVisits] = useState<VisitLogItem[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [detailLoading, setDetailLoading] = useState(false)

  // 配置弹窗
  const [cfgOpen, setCfgOpen] = useState(false)
  const [cfg, setCfg] = useState<VisitConfig | null>(null)
  const [cfgSaving, setCfgSaving] = useState(false)
  const [cleaning, setCleaning] = useState(false)

  const baseParams = useCallback(() => {
    if (startDate && endDate) {
      return { start_date: startDate, end_date: endDate }
    }
    return { range }
  }, [range, startDate, endDate])

  const fetchAnalytics = useCallback(
    async (silent = false) => {
      if (!silent) setLoading(true)
      try {
        const data = await adminVisitApi.getAnalytics(baseParams())
        setAnalytics(data)
      } catch (err) {
        if (!silent) {
          toast.error(err instanceof Error ? err.message : "加载访问数据失败")
        }
      } finally {
        if (!silent) setLoading(false)
      }
    },
    [baseParams]
  )

  const fetchVisits = useCallback(async () => {
    setDetailLoading(true)
    try {
      const data = await adminVisitApi.getVisits({
        ...baseParams(),
        source: source || undefined,
        device: device || undefined,
        ip: ipFilter || undefined,
        keyword: keyword || undefined,
        page,
        page_size: PAGE_SIZE,
      })
      setVisits(data.list)
      setTotal(data.pagination.total)
    } catch (err) {
      setVisits([])
      setTotal(0)
      toast.error(err instanceof Error ? err.message : "加载访问明细失败")
    } finally {
      setDetailLoading(false)
    }
  }, [baseParams, source, device, ipFilter, keyword, page])

  useEffect(() => {
    fetchAnalytics()
  }, [fetchAnalytics])

  useEffect(() => {
    const timer = setInterval(() => fetchAnalytics(true), 30000)
    return () => clearInterval(timer)
  }, [fetchAnalytics])

  useEffect(() => {
    if (tab === "detail") fetchVisits()
  }, [tab, fetchVisits])

  useEffect(() => {
    setPage(1)
  }, [range, startDate, endDate, source, device, ipFilter, keyword])

  useEffect(() => {
    adminVisitApi
      .getOptions()
      .then(setOptions)
      .catch(() => undefined)
  }, [])

  const applyQuickRange = (key: string) => {
    setRange(key)
    setStartDate("")
    setEndDate("")
    setShowCustom(false)
  }

  const openConfig = async () => {
    setCfgOpen(true)
    if (cfg) return
    try {
      const data = await adminVisitApi.getConfig()
      setCfg(data)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载配置失败")
    }
  }

  const saveConfig = async () => {
    if (!cfg) return
    setCfgSaving(true)
    try {
      await adminVisitApi.updateConfig({
        visit_track_enabled: cfg.visit_track_enabled,
        visit_exclude_admin: cfg.visit_exclude_admin,
        visit_exclude_bot: cfg.visit_exclude_bot,
        visit_retention_days: cfg.visit_retention_days,
        visit_geolocation_enabled: cfg.visit_geolocation_enabled,
        visit_ip_xdb_path: cfg.visit_ip_xdb_path,
        visit_ip_whitelist: cfg.visit_ip_whitelist,
        visit_ip_blacklist: cfg.visit_ip_blacklist,
      })
      toast.success("配置已保存")
      setCfgOpen(false)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setCfgSaving(false)
    }
  }

  const runCleanup = async () => {
    setCleaning(true)
    try {
      const res = await adminVisitApi.cleanup()
      toast.success(`已清理 ${fmtNum(res.deleted_logs)} 条明细、${fmtNum(res.deleted_sessions)} 条会话（保留 ${res.retention_days} 天）`)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "清理失败")
    } finally {
      setCleaning(false)
    }
  }

  const handleExport = async () => {
    try {
      await adminVisitApi.exportVisits({
        ...baseParams(),
        source: source || undefined,
        device: device || undefined,
        ip: ipFilter || undefined,
        keyword: keyword || undefined,
      })
      toast.success("导出已开始下载")
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "导出失败")
    }
  }

  const summary = analytics?.summary
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  const trendData = analytics
    ? analytics.trend.dates.map((d, i) => ({
        date: d,
        pv: analytics.trend.pv[i] ?? 0,
        uv: analytics.trend.uv[i] ?? 0,
      }))
    : []

  const hourData = analytics
    ? analytics.hours.labels.map((l, i) => ({ hour: l, pv: analytics.hours.pv[i] ?? 0 }))
    : []

  const sourcePie = analytics
    ? analytics.sources.map((s) => ({ name: s.name, value: s.pv, code: s.code }))
    : []

  const refererItems = analytics ? analytics.referers.map((r) => ({ name: r.name, pv: r.pv, uv: r.uv })) : []
  const regionItems = analytics ? analytics.regions.map((r) => ({ name: r.name, pv: r.pv, uv: r.uv })) : []
  const cityItems = analytics ? analytics.cities.map((c) => ({ name: `${c.province} · ${c.name}`, pv: c.pv, uv: c.uv })) : []
  const osItems = analytics ? analytics.os.map((o) => ({ name: o.name, pv: o.pv, uv: o.uv })) : []
  const browserItems = analytics ? analytics.browsers.map((b) => ({ name: b.name, pv: b.pv, uv: b.uv })) : []
  const ispItems = analytics ? analytics.isps.map((i) => ({ name: i.name, pv: i.pv, uv: i.uv })) : []
  const pageItems = analytics ? analytics.pages.map((p) => ({ name: p.path, pv: p.pv, uv: p.uv })) : []

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-foreground">访问数据</h1>
          <p className="text-sm text-muted-foreground">统计分析商城前台网站的真实访问流量（仅统计前台访客，不含后台与爬虫）</p>
        </div>
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-500/30 bg-emerald-500/10 px-3 py-1.5 text-xs font-medium text-emerald-600">
            <span className="relative flex h-2 w-2">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-500 opacity-75" />
              <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
            </span>
            实时在线 {fmtNum(analytics?.realtime.online ?? 0)}
          </span>
          <button
            type="button"
            onClick={() => fetchAnalytics()}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
          >
            <RefreshCw className={cn("h-4 w-4", loading && "animate-spin")} />
            刷新
          </button>
          <button
            type="button"
            onClick={openConfig}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
          >
            <Settings2 className="h-4 w-4" />
            设置
          </button>
        </div>
      </div>

      {/* Filter toolbar */}
      <div className="flex flex-col gap-3 rounded-xl border border-border bg-card p-4 shadow-sm">
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex flex-wrap items-center gap-1 rounded-lg bg-muted p-1">
            {QUICK_RANGES.map((r) => {
              const active = !startDate && !endDate && range === r.key
              return (
                <button
                  key={r.key}
                  type="button"
                  onClick={() => applyQuickRange(r.key)}
                  className={cn(
                    "rounded-md px-3 py-1.5 text-xs font-medium transition-colors",
                    active ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  {r.label}
                </button>
              )
            })}
          </div>
          <button
            type="button"
            onClick={() => setShowCustom((v) => !v)}
            className={cn(
              "rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors",
              startDate || endDate
                ? "border-primary/40 bg-primary/10 text-primary"
                : "border-border text-muted-foreground hover:text-foreground"
            )}
          >
            自定义
          </button>
          {showCustom || startDate || endDate ? (
            <div className="flex items-center gap-2">
              <input
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
                className="rounded-lg border border-border bg-background px-2.5 py-1.5 text-xs text-foreground outline-none focus:border-primary"
              />
              <span className="text-xs text-muted-foreground">至</span>
              <input
                type="date"
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
                className="rounded-lg border border-border bg-background px-2.5 py-1.5 text-xs text-foreground outline-none focus:border-primary"
              />
              {(startDate || endDate) ? (
                <button
                  type="button"
                  onClick={() => {
                    setStartDate("")
                    setEndDate("")
                  }}
                  className="text-xs text-muted-foreground hover:text-foreground"
                >
                  清除
                </button>
              ) : null}
            </div>
          ) : null}
        </div>

        {tab === "detail" ? (
          <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3">
            <select
              value={source}
              onChange={(e) => setSource(e.target.value)}
              className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
            >
              <option value="">全部来源</option>
              {(options?.sources ?? []).map((s) => (
                <option key={s.value} value={s.value}>{s.label}</option>
              ))}
            </select>
            <select
              value={device}
              onChange={(e) => setDevice(e.target.value)}
              className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
            >
              <option value="">全部设备</option>
              {(options?.devices ?? []).map((d) => (
                <option key={d.value} value={d.value}>{d.label}</option>
              ))}
            </select>
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <input
                value={ipFilter}
                onChange={(e) => setIpFilter(e.target.value)}
                placeholder="按 IP 精确筛选"
                className="w-44 rounded-lg border border-border bg-background py-2 pl-9 pr-3 text-sm text-foreground outline-none focus:border-primary"
              />
            </div>
            <div className="relative">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <input
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder="搜索路径 / IP 关键词"
                className="w-52 rounded-lg border border-border bg-background py-2 pl-9 pr-3 text-sm text-foreground outline-none focus:border-primary"
              />
            </div>
            <button
              type="button"
              onClick={handleExport}
              className="ml-auto inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90"
            >
              <Download className="h-4 w-4" />
              导出 CSV
            </button>
          </div>
        ) : null}
      </div>

      {/* Tabs */}
      <div className="flex items-center gap-1 border-b border-border">
        {[
          { key: "overview" as const, label: "数据概览" },
          { key: "detail" as const, label: "访问明细" },
        ].map((tItem) => (
          <button
            key={tItem.key}
            type="button"
            onClick={() => setTab(tItem.key)}
            className={cn(
              "-mb-px border-b-2 px-4 py-2.5 text-sm font-medium transition-colors",
              tab === tItem.key
                ? "border-primary text-primary"
                : "border-transparent text-muted-foreground hover:text-foreground"
            )}
          >
            {tItem.label}
          </button>
        ))}
      </div>

      {tab === "overview" ? (
        loading && !analytics ? (
          <div className="flex items-center justify-center py-24">
            <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          </div>
        ) : !analytics ? (
          <Panel title="数据概览"><Empty text="暂无访问数据" /></Panel>
        ) : (
          <div className="flex flex-col gap-6">
            {/* Summary cards */}
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              <StatCard icon={Eye} label="今日 PV" value={fmtNum(summary?.today_pv)} sub={`今日 UV ${fmtNum(summary?.today_uv)}`} />
              <StatCard icon={TrendingUp} label="区间 PV" value={fmtNum(summary?.pv)} sub={`日均 ${fmtNum(summary?.avg_pv)}`} accent="text-blue-500" />
              <StatCard icon={Users} label="区间 UV" value={fmtNum(summary?.uv)} sub={`人均浏览 ${fmtNum(summary?.pv_per_visitor)} 次`} accent="text-emerald-500" />
              <StatCard icon={Globe2} label="独立 IP" value={fmtNum(summary?.ips)} sub={`独立访客 ${fmtNum(summary?.visitors)}`} accent="text-violet-500" />
              <StatCard icon={ContactRound} label="新访客 / 回访" value={fmtNum(summary?.new_uv)} sub={`回访 ${fmtNum(summary?.returning_uv)}`} accent="text-amber-500" />
              <StatCard icon={Activity} label="会话数" value={fmtNum(summary?.sessions)} sub={`跳出率 ${fmtNum(summary?.bounce_rate)}%`} accent="text-rose-500" />
              <StatCard icon={Clock} label="平均停留时长" value={fmtDuration(summary?.avg_duration_sec)} sub={`人均 ${fmtNum(summary?.avg_page_count)} 页`} accent="text-cyan-500" />
              <StatCard icon={MousePointerClick} label="流量峰值时段" value={fmtHour(summary?.peak_hour)} sub={`${fmtNum(summary?.peak_hour_pv)} PV`} accent="text-orange-500" />
            </div>

            {/* Trend + Hours */}
            <Panel title="访问趋势（PV / UV）">
              <div className="mb-4 flex gap-4 text-xs text-muted-foreground">
                <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-blue-500" />PV</span>
                <span className="flex items-center gap-1.5"><span className="h-2 w-2 rounded-full bg-emerald-500" />UV</span>
              </div>
              <div className="h-72">
                {trendData.length === 0 ? (
                  <Empty />
                ) : (
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={trendData} margin={{ top: 5, right: 5, left: -20, bottom: 0 }}>
                      <defs>
                        <linearGradient id="visitPv" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="hsl(217, 91%, 60%)" stopOpacity={0.25} />
                          <stop offset="95%" stopColor="hsl(217, 91%, 60%)" stopOpacity={0} />
                        </linearGradient>
                        <linearGradient id="visitUv" x1="0" y1="0" x2="0" y2="1">
                          <stop offset="5%" stopColor="hsl(160, 84%, 39%)" stopOpacity={0.25} />
                          <stop offset="95%" stopColor="hsl(160, 84%, 39%)" stopOpacity={0} />
                        </linearGradient>
                      </defs>
                      <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
                      <XAxis
                        dataKey="date"
                        tickFormatter={(v) => String(v).slice(5)}
                        tick={{ fontSize: 12, fill: "hsl(var(--muted-foreground))" }}
                        axisLine={{ stroke: "hsl(var(--border))" }}
                        tickLine={false}
                      />
                      <YAxis tick={{ fontSize: 12, fill: "hsl(var(--muted-foreground))" }} axisLine={false} tickLine={false} />
                      <Tooltip
                        contentStyle={{
                          background: "hsl(var(--card))",
                          border: "1px solid hsl(var(--border))",
                          borderRadius: "8px",
                          fontSize: "12px",
                          color: "hsl(var(--foreground))",
                        }}
                      />
                      <Area type="monotone" dataKey="pv" name="PV" stroke="hsl(217, 91%, 60%)" strokeWidth={2} fill="url(#visitPv)" />
                      <Area type="monotone" dataKey="uv" name="UV" stroke="hsl(160, 84%, 39%)" strokeWidth={2} fill="url(#visitUv)" />
                    </AreaChart>
                  </ResponsiveContainer>
                )}
              </div>
            </Panel>

            <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
              <Panel title="流量来源分布" className="xl:col-span-1">
                {sourcePie.length === 0 ? (
                  <Empty />
                ) : (
                  <div className="flex flex-col gap-4">
                    <div className="h-48">
                      <ResponsiveContainer width="100%" height="100%">
                        <PieChart>
                          <Pie data={sourcePie} dataKey="value" nameKey="name" innerRadius={45} outerRadius={70} paddingAngle={2}>
                            {sourcePie.map((entry, idx) => (
                              <Cell key={entry.code || idx} fill={SOURCE_COLORS[entry.code] || CHART_COLORS[idx % CHART_COLORS.length]} />
                            ))}
                          </Pie>
                          <Tooltip
                            contentStyle={{
                              background: "hsl(var(--card))",
                              border: "1px solid hsl(var(--border))",
                              borderRadius: "8px",
                              fontSize: "12px",
                              color: "hsl(var(--foreground))",
                            }}
                          />
                        </PieChart>
                      </ResponsiveContainer>
                    </div>
                    <div className="flex flex-col gap-2">
                      {analytics.sources.map((s, idx) => (
                        <div key={s.code || idx} className="flex items-center justify-between text-sm">
                          <span className="flex items-center gap-2 text-foreground">
                            <span className="h-2.5 w-2.5 rounded-full" style={{ background: SOURCE_COLORS[s.code] || CHART_COLORS[idx % CHART_COLORS.length] }} />
                            {s.name}
                          </span>
                          <span className="text-muted-foreground">{fmtNum(s.pv)} PV · {fmtNum(s.ratio)}%</span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </Panel>

              <Panel title="设备分布" className="xl:col-span-2">
                {analytics.devices.length === 0 ? (
                  <Empty />
                ) : (
                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                    {analytics.devices.map((d, idx) => {
                      const Icon = DEVICE_ICONS[d.code] || Monitor
                      return (
                        <div key={d.code || idx} className="flex items-center gap-3 rounded-lg border border-border p-3">
                          <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10 text-primary">
                            <Icon className="h-5 w-5" />
                          </span>
                          <div className="min-w-0 flex-1">
                            <div className="flex items-center justify-between">
                              <span className="text-sm font-medium text-foreground">{d.name}</span>
                              <span className="text-xs text-muted-foreground">{fmtNum(d.ratio)}%</span>
                            </div>
                            <div className="mt-1 text-xs text-muted-foreground">{fmtNum(d.pv)} PV · {fmtNum(d.uv)} UV</div>
                            <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                              <div className="h-full rounded-full bg-primary/70" style={{ width: `${Math.max(2, d.ratio)}%` }} />
                            </div>
                          </div>
                        </div>
                      )
                    })}
                  </div>
                )}
              </Panel>
            </div>

            <Panel title="时段分布（24 小时）">
              <div className="h-64">
                {hourData.length === 0 ? (
                  <Empty />
                ) : (
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={hourData} margin={{ top: 5, right: 5, left: -20, bottom: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
                      <XAxis dataKey="hour" tick={{ fontSize: 11, fill: "hsl(var(--muted-foreground))" }} axisLine={{ stroke: "hsl(var(--border))" }} tickLine={false} interval={1} />
                      <YAxis tick={{ fontSize: 12, fill: "hsl(var(--muted-foreground))" }} axisLine={false} tickLine={false} />
                      <Tooltip
                        cursor={{ fill: "hsl(var(--accent))" }}
                        contentStyle={{
                          background: "hsl(var(--card))",
                          border: "1px solid hsl(var(--border))",
                          borderRadius: "8px",
                          fontSize: "12px",
                          color: "hsl(var(--foreground))",
                        }}
                      />
                      <Bar dataKey="pv" name="PV" fill="hsl(217, 91%, 60%)" radius={[3, 3, 0, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                )}
              </div>
            </Panel>

            {/* Funnel + Sessions */}
            <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
              <Panel title="访问转化漏斗">
                {analytics.funnel.length === 0 ? (
                  <Empty />
                ) : (
                  <div className="flex flex-col gap-4">
                    {analytics.funnel.map((s, idx) => (
                      <div key={s.stage} className="flex flex-col gap-1.5">
                        <div className="flex items-center justify-between text-sm">
                          <span className="font-medium text-foreground">{s.label}</span>
                          <span className="text-muted-foreground">
                            {fmtNum(s.visitors)} 人 · {fmtNum(s.rate)}%
                            {idx > 0 ? <span className="ml-2 text-xs text-primary">转化 {fmtNum(s.conversion)}%</span> : null}
                          </span>
                        </div>
                        <div className="h-6 w-full overflow-hidden rounded-md bg-muted">
                          <div
                            className="flex h-full items-center justify-end rounded-md bg-gradient-to-r from-primary/60 to-primary pr-2 text-xs font-medium text-primary-foreground"
                            style={{ width: `${Math.max(4, s.rate)}%` }}
                          >
                            {fmtNum(s.visitors)}
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </Panel>

              <Panel title="会话质量">
                <div className="grid grid-cols-2 gap-4">
                  <StatCard icon={Activity} label="会话总数" value={fmtNum(analytics.sessions.sessions)} accent="text-rose-500" />
                  <StatCard icon={MousePointerClick} label="跳出率" value={`${fmtNum(analytics.sessions.bounce_rate)}%`} sub={`跳出 ${fmtNum(analytics.sessions.bounces)}`} accent="text-amber-500" />
                  <StatCard icon={Clock} label="平均停留" value={fmtDuration(analytics.sessions.avg_duration_sec)} accent="text-cyan-500" />
                  <StatCard icon={Eye} label="人均页数" value={fmtNum(analytics.sessions.avg_page_count)} accent="text-blue-500" />
                </div>
                <div className="mt-4 border-t border-border pt-4">
                  <h4 className="mb-3 text-sm font-medium text-foreground">会话来源质量</h4>
                  {analytics.sessions.sources.length === 0 ? (
                    <Empty text="暂无会话数据" />
                  ) : (
                    <div className="flex flex-col gap-2">
                      {analytics.sessions.sources.map((s, idx) => (
                        <div key={s.code || idx} className="flex items-center justify-between text-sm">
                          <span className="text-foreground">{s.name}</span>
                          <span className="text-muted-foreground">{fmtNum(s.sessions)} 会话 · 跳出 {fmtNum(s.bounce_rate)}%</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </Panel>
            </div>

            {/* Dimensions */}
            <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
              <Panel title="来源域名 TOP"><BarList items={refererItems} /></Panel>
              <Panel title="操作系统"><BarList items={osItems} /></Panel>
              <Panel title="浏览器"><BarList items={browserItems} /></Panel>
            </div>

            <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
              <Panel title="省份分布"><BarList items={regionItems} /></Panel>
              <Panel title="城市分布 TOP"><BarList items={cityItems} /></Panel>
              <Panel title="运营商分布"><BarList items={ispItems} /></Panel>
            </div>

            <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
              <Panel title="热门访问页面 TOP"><BarList items={pageItems} /></Panel>
              <Panel title="活跃 IP TOP">
                {analytics.ips.length === 0 ? (
                  <Empty />
                ) : (
                  <div className="max-h-80 overflow-y-auto">
                    <table className="w-full text-sm">
                      <thead className="sticky top-0 bg-card text-xs text-muted-foreground">
                        <tr className="border-b border-border">
                          <th className="py-2 text-left font-medium">IP</th>
                          <th className="py-2 text-left font-medium">归属地</th>
                          <th className="py-2 text-left font-medium">运营商</th>
                          <th className="py-2 text-right font-medium">PV</th>
                          <th className="py-2 text-right font-medium">最近访问</th>
                        </tr>
                      </thead>
                      <tbody>
                        {analytics.ips.map((item, idx) => (
                          <tr key={`${item.ip}-${idx}`} className="border-b border-border/60 last:border-0">
                            <td className="py-2 font-mono text-xs text-foreground">{item.ip}</td>
                            <td className="py-2 text-muted-foreground">{[item.province, item.city].filter(Boolean).join(" ") || "未知"}</td>
                            <td className="py-2 text-muted-foreground">{item.isp || "未知"}</td>
                            <td className="py-2 text-right font-medium text-foreground">{fmtNum(item.pv)}</td>
                            <td className="py-2 text-right text-xs text-muted-foreground">{item.last_time || "-"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </Panel>
            </div>
          </div>
        )
      ) : (
        /* Detail tab */
        <Panel title="访问明细">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-sm">
              <thead className="text-xs text-muted-foreground">
                <tr className="border-b border-border">
                  <th className="px-2 py-3 text-left font-medium">访问时间</th>
                  <th className="px-2 py-3 text-left font-medium">IP</th>
                  <th className="px-2 py-3 text-left font-medium">归属地</th>
                  <th className="px-2 py-3 text-left font-medium">运营商</th>
                  <th className="px-2 py-3 text-left font-medium">设备</th>
                  <th className="px-2 py-3 text-left font-medium">系统</th>
                  <th className="px-2 py-3 text-left font-medium">浏览器</th>
                  <th className="px-2 py-3 text-left font-medium">来源</th>
                  <th className="px-2 py-3 text-left font-medium">访问路径</th>
                  <th className="px-2 py-3 text-left font-medium">访客标识</th>
                </tr>
              </thead>
              <tbody>
                {detailLoading ? (
                  <tr>
                    <td colSpan={10} className="py-16 text-center">
                      <div className="mx-auto h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    </td>
                  </tr>
                ) : visits.length === 0 ? (
                  <tr>
                    <td colSpan={10} className="py-16 text-center text-sm text-muted-foreground">暂无访问明细</td>
                  </tr>
                ) : (
                  visits.map((v) => (
                    <tr key={v.id} className="border-b border-border/60 last:border-0 hover:bg-accent/40">
                      <td className="whitespace-nowrap px-2 py-3 text-xs text-muted-foreground">{v.visit_time || "-"}</td>
                      <td className="px-2 py-3 font-mono text-xs text-foreground">{v.ip}</td>
                      <td className="px-2 py-3 text-muted-foreground">
                        {[v.country, v.province, v.city].filter((x) => x && x !== "0").join(" ") || "未知"}
                      </td>
                      <td className="px-2 py-3 text-muted-foreground">{v.isp || "未知"}</td>
                      <td className="px-2 py-3 text-muted-foreground">{v.device_label}</td>
                      <td className="px-2 py-3 text-muted-foreground">{v.os || "未知"}</td>
                      <td className="px-2 py-3 text-muted-foreground">{v.browser || "未知"}</td>
                      <td className="px-2 py-3">
                        <span className="text-foreground">{v.source_label}</span>
                        {v.referer ? <span className="block text-xs text-muted-foreground">{v.referer}</span> : null}
                      </td>
                      <td className="max-w-[220px] truncate px-2 py-3 text-foreground" title={v.path}>{v.path}</td>
                      <td className="px-2 py-3 font-mono text-xs text-muted-foreground">{v.visitor_id ? v.visitor_id.slice(0, 12) : "-"}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div className="mt-4 flex items-center justify-between">
            <span className="text-sm text-muted-foreground">共 {fmtNum(total)} 条记录</span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                disabled={page <= 1 || detailLoading}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
                className="inline-flex items-center gap-1 rounded-lg border border-border px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
              >
                <ChevronLeft className="h-4 w-4" />上一页
              </button>
              <span className="text-sm text-foreground">{page} / {totalPages}</span>
              <button
                type="button"
                disabled={page >= totalPages || detailLoading}
                onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                className="inline-flex items-center gap-1 rounded-lg border border-border px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
              >
                下一页<ChevronRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        </Panel>
      )}

      {/* Config modal */}
      {cfgOpen ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={() => setCfgOpen(false)}
        >
          <div
            className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-xl border border-border bg-card p-6 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-5 flex items-center justify-between">
              <h2 className="text-lg font-semibold text-foreground">访问数据设置</h2>
              <button
                type="button"
                onClick={() => setCfgOpen(false)}
                className="rounded-md p-1 text-muted-foreground transition-colors hover:text-foreground"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {!cfg ? (
              <div className="flex items-center justify-center py-12">
                <div className="h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              </div>
            ) : (
              <div className="flex flex-col gap-4">
                <Toggle
                  checked={cfg.visit_track_enabled}
                  onChange={(v) => setCfg({ ...cfg, visit_track_enabled: v })}
                  label="开启访问统计"
                  desc="关闭后前台将不再采集任何访问数据"
                />
                <Toggle
                  checked={cfg.visit_exclude_admin}
                  onChange={(v) => setCfg({ ...cfg, visit_exclude_admin: v })}
                  label="排除管理员自身访问"
                  desc="登录后台的账号浏览前台时不计数"
                />
                <Toggle
                  checked={cfg.visit_exclude_bot}
                  onChange={(v) => setCfg({ ...cfg, visit_exclude_bot: v })}
                  label="排除爬虫 / 机器人"
                  desc="识别常见搜索引擎与自动化 UA，不计数"
                />
                <Toggle
                  checked={cfg.visit_geolocation_enabled}
                  onChange={(v) => setCfg({ ...cfg, visit_geolocation_enabled: v })}
                  label="启用 IP 归属地离线解析"
                  desc="基于 ip2region 离线库解析国家 / 省份 / 城市 / 运营商"
                />

                <div className="grid grid-cols-1 gap-3">
                  <label className="flex flex-col gap-1.5">
                    <span className="text-sm font-medium text-foreground">数据保留天数</span>
                    <input
                      type="number"
                      min={1}
                      value={cfg.visit_retention_days}
                      onChange={(e) => setCfg({ ...cfg, visit_retention_days: Number(e.target.value) || 1 })}
                      className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
                    />
                  </label>
                  <label className="flex flex-col gap-1.5">
                    <span className="text-sm font-medium text-foreground">ip2region.xdb 文件路径</span>
                    <input
                      value={cfg.visit_ip_xdb_path}
                      onChange={(e) => setCfg({ ...cfg, visit_ip_xdb_path: e.target.value })}
                      placeholder="./data/ip2region.xdb"
                      className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
                    />
                  </label>
                  <label className="flex flex-col gap-1.5">
                    <span className="text-sm font-medium text-foreground">IP 白名单</span>
                    <textarea
                      rows={2}
                      value={cfg.visit_ip_whitelist}
                      onChange={(e) => setCfg({ ...cfg, visit_ip_whitelist: e.target.value })}
                      placeholder="每行一个，支持前缀通配，如 10.0.*"
                      className="resize-none rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
                    />
                  </label>
                  <label className="flex flex-col gap-1.5">
                    <span className="text-sm font-medium text-foreground">IP 黑名单</span>
                    <textarea
                      rows={2}
                      value={cfg.visit_ip_blacklist}
                      onChange={(e) => setCfg({ ...cfg, visit_ip_blacklist: e.target.value })}
                      placeholder="每行一个，支持前缀通配，如 192.168.*"
                      className="resize-none rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
                    />
                  </label>
                </div>

                <div className="flex items-center justify-between border-t border-border pt-4">
                  <button
                    type="button"
                    onClick={runCleanup}
                    disabled={cleaning}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-destructive/40 px-3 py-2 text-sm font-medium text-destructive transition-colors hover:bg-destructive/10 disabled:opacity-50"
                  >
                    <RefreshCw className={cn("h-4 w-4", cleaning && "animate-spin")} />
                    立即清理过期数据
                  </button>
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => setCfgOpen(false)}
                      className="rounded-lg border border-border px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
                    >
                      取消
                    </button>
                    <button
                      type="button"
                      onClick={saveConfig}
                      disabled={cfgSaving}
                      className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-50"
                    >
                      {cfgSaving ? "保存中..." : "保存"}
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      ) : null}
    </div>
  )
}

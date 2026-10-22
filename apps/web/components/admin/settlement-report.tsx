"use client"

import { useState, useEffect, useCallback, useRef, type ReactNode } from "react"
import {
  Area,
  ComposedChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  PieChart,
  Pie,
  Cell,
} from "recharts"
import {
  Wallet,
  CircleDollarSign,
  Undo2,
  Coins,
  TrendingUp,
  CheckCircle,
  CalendarDays,
  ChevronDown,
  Check,
  RefreshCw,
  Loader2,
  type LucideIcon,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { toast } from "sonner"
import { adminOrderApi, withMockFallback } from "@/services/api"
import { mockSettlementReport } from "@/lib/mock-data"
import { PaymentIcon, getPaymentLabel } from "@/components/shared/payment-icon"
import type { SettlementReport } from "@/types"

// ── 快捷日期筛选 ──
type DatePreset = "today" | "yesterday" | "week" | "month" | "last_month" | "year" | "all" | "custom"

const DATE_PRESETS: { value: DatePreset; label: string }[] = [
  { value: "today", label: "今日" },
  { value: "yesterday", label: "昨日" },
  { value: "week", label: "本周" },
  { value: "month", label: "本月" },
  { value: "last_month", label: "上月" },
  { value: "year", label: "今年" },
  { value: "all", label: "全部" },
  { value: "custom", label: "自定义" },
]

/** 格式化为 YYYY-MM-DD（本地时区） */
function formatDate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, "0")
  const day = String(d.getDate()).padStart(2, "0")
  return `${y}-${m}-${day}`
}

/** 根据快捷选项解析出 [start, end] 日期（含首尾），全部则返回空 */
function resolveDateRange(preset: DatePreset, customStart: string, customEnd: string): { start?: string; end?: string } {
  const now = new Date()
  const y = now.getFullYear()
  const m = now.getMonth()
  switch (preset) {
    case "today":
      return { start: formatDate(now), end: formatDate(now) }
    case "yesterday": {
      const d = new Date(y, m, now.getDate() - 1)
      return { start: formatDate(d), end: formatDate(d) }
    }
    case "week": {
      const day = now.getDay() === 0 ? 7 : now.getDay()
      const monday = new Date(y, m, now.getDate() - (day - 1))
      return { start: formatDate(monday), end: formatDate(now) }
    }
    case "month":
      return { start: formatDate(new Date(y, m, 1)), end: formatDate(new Date(y, m + 1, 0)) }
    case "last_month":
      return { start: formatDate(new Date(y, m - 1, 1)), end: formatDate(new Date(y, m, 0)) }
    case "year":
      return { start: formatDate(new Date(y, 0, 1)), end: formatDate(new Date(y, 11, 31)) }
    case "custom":
      return { start: customStart || undefined, end: customEnd || undefined }
    default:
      return {}
  }
}

// ── 展示辅助 ──
const PIE_COLORS = ["#6366f1", "#10b981", "#0ea5e9", "#f59e0b", "#8b5cf6", "#ef4444"]

function money(value: number | null | undefined): string {
  return `¥${Number(value ?? 0).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function compactMoney(value: number): string {
  const v = Number(value ?? 0)
  if (Math.abs(v) >= 100000000) return `¥${(v / 100000000).toFixed(2)}亿`
  if (Math.abs(v) >= 10000) return `¥${(v / 10000).toFixed(1)}万`
  return `¥${v.toFixed(0)}`
}

const TOOLTIP_STYLE = {
  background: "hsl(var(--card))",
  border: "1px solid hsl(var(--border))",
  borderRadius: "8px",
  fontSize: "12px",
} as const

const ORDER_TYPE_LABELS: Record<string, string> = {
  DIRECT: "直接购买",
  CART: "购物车结算",
}

const CARD_TONES = {
  indigo: { icon: "bg-indigo-500/10 text-indigo-600 dark:text-indigo-400", bar: "from-indigo-500 to-violet-500" },
  emerald: { icon: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400", bar: "from-emerald-500 to-teal-500" },
  amber: { icon: "bg-amber-500/10 text-amber-600 dark:text-amber-400", bar: "from-amber-500 to-orange-500" },
  sky: { icon: "bg-sky-500/10 text-sky-600 dark:text-sky-400", bar: "from-sky-500 to-cyan-500" },
  violet: { icon: "bg-violet-500/10 text-violet-600 dark:text-violet-400", bar: "from-violet-500 to-fuchsia-500" },
  rose: { icon: "bg-rose-500/10 text-rose-600 dark:text-rose-400", bar: "from-rose-500 to-pink-500" },
} as const

type CardTone = keyof typeof CARD_TONES

function SummaryCard({ label, value, hint, icon: Icon, tone }: {
  label: string
  value: string
  hint?: string
  icon: LucideIcon
  tone: CardTone
}) {
  const t = CARD_TONES[tone]
  return (
    <div className="relative overflow-hidden rounded-xl border border-border bg-card p-5 shadow-sm transition-shadow hover:shadow-md">
      <div className={cn("absolute inset-x-0 top-0 h-0.5 bg-gradient-to-r", t.bar)} />
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm text-muted-foreground">{label}</p>
          <p className="mt-2 truncate text-2xl font-bold tracking-tight text-foreground">{value}</p>
          {hint && <p className="mt-1 truncate text-xs text-muted-foreground">{hint}</p>}
        </div>
        <div className={cn("flex h-10 w-10 shrink-0 items-center justify-center rounded-lg", t.icon)}>
          <Icon className="h-5 w-5" />
        </div>
      </div>
    </div>
  )
}

function SectionCard({ title, subtitle, action, className, children }: {
  title: string
  subtitle?: string
  action?: ReactNode
  className?: string
  children: ReactNode
}) {
  return (
    <div className={cn("rounded-xl border border-border bg-card p-5 shadow-sm", className)}>
      <div className="mb-4 flex items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-foreground">{title}</h3>
          {subtitle && <p className="mt-0.5 text-xs text-muted-foreground">{subtitle}</p>}
        </div>
        {action}
      </div>
      {children}
    </div>
  )
}

function RatioBar({ ratio, className }: { ratio: number; className?: string }) {
  return (
    <div className={cn("h-1.5 w-full overflow-hidden rounded-full bg-muted", className)}>
      <div
        className="h-full rounded-full bg-primary transition-all duration-500"
        style={{ width: `${Math.min(100, Math.max(0, ratio))}%` }}
      />
    </div>
  )
}

export function SettlementReportPanel() {
  const [report, setReport] = useState<SettlementReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null)

  // ── 日期筛选（默认本月）──
  const [datePreset, setDatePreset] = useState<DatePreset>("month")
  const [customStart, setCustomStart] = useState("")
  const [customEnd, setCustomEnd] = useState("")
  const [dateMenuOpen, setDateMenuOpen] = useState(false)
  const dateMenuRef = useRef<HTMLDivElement>(null)

  const datePresetLabel = DATE_PRESETS.find(p => p.value === datePreset)?.label ?? "本月"

  const getParams = useCallback(() => {
    const range = resolveDateRange(datePreset, customStart, customEnd)
    return { start_date: range.start, end_date: range.end }
  }, [datePreset, customStart, customEnd])

  // 筛选条件变化 → 动态加载
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    const params = getParams()
    withMockFallback(
      () => adminOrderApi.getSettlement(params),
      () => mockSettlementReport(params)
    )
      .then(data => {
        if (cancelled) return
        setReport(data)
        setUpdatedAt(new Date())
      })
      .catch((err: unknown) => {
        if (cancelled) return
        toast.error(err instanceof Error ? err.message : "加载业绩结算数据失败")
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => { cancelled = true }
  }, [getParams])

  // 点击外部关闭日期下拉
  useEffect(() => {
    if (!dateMenuOpen) return
    const handler = (e: MouseEvent) => {
      if (!dateMenuRef.current?.contains(e.target as Node)) setDateMenuOpen(false)
    }
    document.addEventListener("mousedown", handler)
    return () => document.removeEventListener("mousedown", handler)
  }, [dateMenuOpen])

  const handleSelectPreset = (preset: DatePreset) => {
    setDatePreset(preset)
    setDateMenuOpen(false)
    if (preset === "custom" && (!customStart || !customEnd)) {
      const now = new Date()
      setCustomStart(formatDate(new Date(now.getFullYear(), now.getMonth(), 1)))
      setCustomEnd(formatDate(now))
    }
  }

  const handleRefresh = async () => {
    setRefreshing(true)
    const params = getParams()
    try {
      const data = await withMockFallback(
        () => adminOrderApi.getSettlement(params),
        () => mockSettlementReport(params)
      )
      setReport(data)
      setUpdatedAt(new Date())
      toast.success("业绩数据已刷新")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "刷新失败")
    } finally {
      setRefreshing(false)
    }
  }

  const summary = report?.summary
  const trend = report?.trend ?? []
  const paymentMethods = report?.payment_methods ?? []
  const orderTypes = report?.order_types ?? []
  const distributors = report?.distributors ?? []
  const granularityLabel = report?.trend_granularity === "month" ? "按月" : "按日"

  const commissionRatio = summary && summary.effective_amount > 0
    ? (summary.commission_amount / summary.effective_amount) * 100
    : 0
  const profitRatio = summary && summary.effective_amount > 0
    ? (summary.profit_amount / summary.effective_amount) * 100
    : 0

  return (
    <div className="flex flex-col gap-6">
      {/* 汇总卡片 */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
        <SummaryCard
          label="总业绩"
          value={loading && !summary ? "..." : money(summary?.gross_amount)}
          hint={`${datePresetLabel} · 含退款`}
          icon={Wallet}
          tone="indigo"
        />
        <SummaryCard
          label="有效金额"
          value={loading && !summary ? "..." : money(summary?.effective_amount)}
          hint="已支付且未退款"
          icon={CircleDollarSign}
          tone="emerald"
        />
        <SummaryCard
          label="退款金额"
          value={loading && !summary ? "..." : money(summary?.refunded_amount)}
          hint={`退款订单 ${summary?.refunded_orders ?? 0} 单`}
          icon={Undo2}
          tone="rose"
        />
        <SummaryCard
          label="分销佣金"
          value={loading && !summary ? "..." : money(summary?.commission_amount)}
          hint={`占有效金额 ${commissionRatio.toFixed(1)}%`}
          icon={Coins}
          tone="amber"
        />
        <SummaryCard
          label="剩余利润"
          value={loading && !summary ? "..." : money(summary?.profit_amount)}
          hint={`利润率 ${profitRatio.toFixed(1)}%`}
          icon={TrendingUp}
          tone="violet"
        />
        <SummaryCard
          label="有效订单"
          value={loading && !summary ? "..." : (summary?.valid_orders ?? 0).toLocaleString()}
          hint={`总订单 ${summary?.total_orders ?? 0} · 客户 ${summary?.total_customers ?? 0}`}
          icon={CheckCircle}
          tone="sky"
        />
      </div>

      {/* 筛选 + 刷新 */}
      <div className="flex flex-wrap items-center gap-3">
        <div ref={dateMenuRef} className="relative">
          <button
            type="button"
            onClick={() => setDateMenuOpen(o => !o)}
            className="flex h-10 items-center gap-2 rounded-lg border border-input bg-background pl-3 pr-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <CalendarDays className="h-4 w-4 text-muted-foreground" />
            <span className="min-w-[2.5em] text-left">{datePresetLabel}</span>
            <ChevronDown className={cn("h-4 w-4 text-muted-foreground transition-transform", dateMenuOpen && "rotate-180")} />
          </button>
          {dateMenuOpen && (
            <div className="absolute left-0 z-30 mt-1 w-36 rounded-lg border border-border bg-background p-1 shadow-lg">
              {DATE_PRESETS.map(preset => (
                <button
                  key={preset.value}
                  type="button"
                  onClick={() => handleSelectPreset(preset.value)}
                  className={cn(
                    "flex w-full items-center justify-between rounded-md px-2.5 py-1.5 text-sm transition-colors hover:bg-accent",
                    datePreset === preset.value ? "font-medium text-primary" : "text-foreground"
                  )}
                >
                  {preset.label}
                  {datePreset === preset.value && <Check className="h-3.5 w-3.5" />}
                </button>
              ))}
            </div>
          )}
        </div>

        {datePreset === "custom" && (
          <div className="flex items-center gap-2">
            <input
              type="date"
              value={customStart}
              onChange={(e) => setCustomStart(e.target.value)}
              className="h-10 rounded-lg border border-input bg-background px-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
            <span className="text-sm text-muted-foreground">至</span>
            <input
              type="date"
              value={customEnd}
              onChange={(e) => setCustomEnd(e.target.value)}
              className="h-10 rounded-lg border border-input bg-background px-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
        )}

        <button
          type="button"
          onClick={handleRefresh}
          disabled={refreshing}
          className="flex h-10 items-center gap-2 rounded-lg border border-input bg-background px-3.5 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60"
        >
          {refreshing ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
          刷新
        </button>

        {updatedAt && (
          <span className="text-xs text-muted-foreground">
            数据更新于 {updatedAt.toLocaleTimeString("zh-CN", { hour12: false })}
          </span>
        )}
      </div>

      {/* 趋势图 + 支付方式分布 */}
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-3">
        <SectionCard
          title="业绩趋势"
          subtitle={`${granularityLabel}统计 · 总业绩与有效金额（右侧为订单数）`}
          className="xl:col-span-2"
          action={
            <div className="flex items-center gap-3 text-xs text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <span className="h-2 w-2 rounded-full bg-indigo-500" />总业绩
              </span>
              <span className="flex items-center gap-1.5">
                <span className="h-2 w-2 rounded-full bg-emerald-500" />有效金额
              </span>
              <span className="flex items-center gap-1.5">
                <span className="h-2 w-2 rounded-full bg-amber-500" />订单数
              </span>
            </div>
          }
        >
          <div className="h-72">
            {trend.length === 0 ? (
              <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
                {loading ? "加载中..." : "当前筛选区间暂无数据"}
              </div>
            ) : (
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={trend} margin={{ top: 8, right: 4, left: 0, bottom: 0 }}>
                  <defs>
                    <linearGradient id="settleGross" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#6366f1" stopOpacity={0.35} />
                      <stop offset="100%" stopColor="#6366f1" stopOpacity={0.02} />
                    </linearGradient>
                    <linearGradient id="settleNet" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#10b981" stopOpacity={0.3} />
                      <stop offset="100%" stopColor="#10b981" stopOpacity={0.02} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" vertical={false} />
                  <XAxis
                    dataKey="date"
                    tick={{ fontSize: 11, fill: "hsl(var(--muted-foreground))" }}
                    tickLine={false}
                    axisLine={{ stroke: "hsl(var(--border))" }}
                    minTickGap={16}
                  />
                  <YAxis
                    yAxisId="left"
                    tick={{ fontSize: 11, fill: "hsl(var(--muted-foreground))" }}
                    tickLine={false}
                    axisLine={false}
                    width={54}
                    tickFormatter={(v: number) => compactMoney(v)}
                  />
                  <YAxis
                    yAxisId="right"
                    orientation="right"
                    tick={{ fontSize: 11, fill: "hsl(var(--muted-foreground))" }}
                    tickLine={false}
                    axisLine={false}
                    width={38}
                    allowDecimals={false}
                  />
                  <Tooltip
                    contentStyle={TOOLTIP_STYLE}
                    labelStyle={{ color: "hsl(var(--foreground))", fontWeight: 600 }}
                    formatter={(value, name) => (
                      name === "订单数"
                        ? [`${Number(value).toLocaleString()} 单`, name]
                        : [money(Number(value)), name]
                    )}
                  />
                  <Area
                    yAxisId="left"
                    type="monotone"
                    dataKey="gross_amount"
                    name="总业绩"
                    stroke="#6366f1"
                    strokeWidth={2}
                    fill="url(#settleGross)"
                  />
                  <Area
                    yAxisId="left"
                    type="monotone"
                    dataKey="net_amount"
                    name="有效金额"
                    stroke="#10b981"
                    strokeWidth={2}
                    fill="url(#settleNet)"
                  />
                  <Line
                    yAxisId="right"
                    type="monotone"
                    dataKey="orders"
                    name="订单数"
                    stroke="#f59e0b"
                    strokeWidth={1.5}
                    strokeDasharray="4 3"
                    dot={false}
                  />
                </ComposedChart>
              </ResponsiveContainer>
            )}
          </div>
        </SectionCard>

        <SectionCard title="支付方式分布" subtitle="按有效金额占比">
          <div className="h-72">
            {paymentMethods.length === 0 ? (
              <div className="flex h-full items-center justify-center text-sm text-muted-foreground">
                {loading ? "加载中..." : "暂无数据"}
              </div>
            ) : (
              <div className="flex h-full flex-col">
                <div className="relative h-40">
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie
                        data={paymentMethods}
                        dataKey="amount"
                        nameKey="payment_method"
                        innerRadius={48}
                        outerRadius={72}
                        paddingAngle={2}
                        strokeWidth={0}
                      >
                        {paymentMethods.map((item, i) => (
                          <Cell key={item.payment_method} fill={PIE_COLORS[i % PIE_COLORS.length]} />
                        ))}
                      </Pie>
                      <Tooltip
                        contentStyle={TOOLTIP_STYLE}
                        formatter={(value, name) => [money(Number(value)), getPaymentLabel(String(name))]}
                      />
                    </PieChart>
                  </ResponsiveContainer>
                  <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                    <span className="text-[11px] text-muted-foreground">有效金额</span>
                    <span className="text-sm font-bold text-foreground">{compactMoney(summary?.effective_amount ?? 0)}</span>
                  </div>
                </div>
                <div className="mt-2 flex-1 space-y-2 overflow-y-auto pr-1">
                  {paymentMethods.map((item, i) => (
                    <div key={item.payment_method} className="flex items-center gap-2 text-xs">
                      <span
                        className="h-2 w-2 shrink-0 rounded-full"
                        style={{ background: PIE_COLORS[i % PIE_COLORS.length] }}
                      />
                      <span className="min-w-0 flex-1 truncate text-muted-foreground">
                        {getPaymentLabel(item.payment_method)}
                      </span>
                      <span className="font-medium text-foreground">{item.ratio.toFixed(1)}%</span>
                      <span className="w-20 text-right text-muted-foreground">{money(item.amount)}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </SectionCard>
      </div>

      {/* 利润构成 + 订单类型 + 分销佣金 */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <SectionCard title="利润构成" subtitle="有效金额 → 分销佣金 → 剩余利润">
          <div className="space-y-5">
            <div>
              <div className="flex items-baseline justify-between">
                <span className="text-sm text-muted-foreground">有效金额</span>
                <span className="text-base font-semibold text-foreground">{money(summary?.effective_amount)}</span>
              </div>
              <RatioBar ratio={100} className="mt-2" />
            </div>
            <div>
              <div className="flex items-baseline justify-between">
                <span className="text-sm text-muted-foreground">分销佣金</span>
                <span className="text-base font-semibold text-amber-600 dark:text-amber-400">
                  − {money(summary?.commission_amount)}
                </span>
              </div>
              <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full rounded-full bg-amber-500 transition-all duration-500"
                  style={{ width: `${Math.min(100, commissionRatio)}%` }}
                />
              </div>
              <p className="mt-1 text-xs text-muted-foreground">占有效金额 {commissionRatio.toFixed(1)}%</p>
            </div>
            <div>
              <div className="flex items-baseline justify-between">
                <span className="text-sm text-muted-foreground">剩余利润</span>
                <span className="text-base font-semibold text-emerald-600 dark:text-emerald-400">
                  {money(summary?.profit_amount)}
                </span>
              </div>
              <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full rounded-full bg-emerald-500 transition-all duration-500"
                  style={{ width: `${Math.min(100, profitRatio)}%` }}
                />
              </div>
              <p className="mt-1 text-xs text-muted-foreground">利润率 {profitRatio.toFixed(1)}%</p>
            </div>
            <div className="rounded-lg bg-muted/40 p-3 text-xs text-muted-foreground">
              退款金额 {money(summary?.refunded_amount)}，已从总业绩中扣除，不计入利润。
            </div>
          </div>
        </SectionCard>

        <SectionCard title="订单类型分布" subtitle="按有效金额占比">
          {orderTypes.length === 0 ? (
            <div className="flex h-48 items-center justify-center text-sm text-muted-foreground">
              {loading ? "加载中..." : "暂无数据"}
            </div>
          ) : (
            <div className="space-y-4">
              {orderTypes.map((item, i) => (
                <div key={item.order_type} className="rounded-lg border border-border/70 p-3">
                  <div className="flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <span
                        className="h-2.5 w-2.5 rounded-full"
                        style={{ background: PIE_COLORS[i % PIE_COLORS.length] }}
                      />
                      <span className="text-sm font-medium text-foreground">
                        {ORDER_TYPE_LABELS[item.order_type] ?? item.order_type}
                      </span>
                    </div>
                    <span className="text-sm font-semibold text-foreground">{item.ratio.toFixed(1)}%</span>
                  </div>
                  <RatioBar ratio={item.ratio} className="mt-2.5" />
                  <div className="mt-2 flex items-center justify-between text-xs text-muted-foreground">
                    <span>{item.orders.toLocaleString()} 单</span>
                    <span>{money(item.amount)}</span>
                  </div>
                </div>
              ))}
              <div className="flex items-center justify-between rounded-lg bg-muted/40 px-3 py-2.5 text-xs text-muted-foreground">
                <span>有效订单合计</span>
                <span className="font-medium text-foreground">{money(summary?.effective_amount)}</span>
              </div>
            </div>
          )}
        </SectionCard>

        <SectionCard title="分销佣金 Top 10" subtitle="一级 + 二级佣金合计">
          {distributors.length === 0 ? (
            <div className="flex h-48 items-center justify-center text-sm text-muted-foreground">
              {loading ? "加载中..." : "暂无分销数据"}
            </div>
          ) : (
            <div className="max-h-64 space-y-1.5 overflow-y-auto pr-1">
              {distributors.map((d, i) => (
                <div key={d.distributor_code} className="flex items-center gap-3 rounded-lg px-1.5 py-2 hover:bg-accent/50">
                  <span
                    className={cn(
                      "flex h-6 w-6 shrink-0 items-center justify-center rounded-md text-xs font-semibold",
                      i === 0
                        ? "bg-amber-500/15 text-amber-600 dark:text-amber-400"
                        : i === 1
                          ? "bg-slate-400/15 text-slate-600 dark:text-slate-300"
                          : i === 2
                            ? "bg-orange-500/15 text-orange-600 dark:text-orange-400"
                            : "bg-muted text-muted-foreground"
                    )}
                  >
                    {i + 1}
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-foreground">{d.name || d.distributor_code}</p>
                    <p className="truncate text-xs text-muted-foreground">
                      {d.distributor_code} · {d.orders} 单
                    </p>
                  </div>
                  <span className="shrink-0 text-sm font-semibold text-foreground">{money(d.amount)}</span>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      </div>

      {/* 支付方式明细 */}
      <SectionCard title="支付方式明细" subtitle="仅统计有效订单（已支付 / 已发货 / 已完成）">
        {paymentMethods.length === 0 ? (
          <div className="flex h-32 items-center justify-center text-sm text-muted-foreground">
            {loading ? "加载中..." : "暂无数据"}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-sm">
              <thead>
                <tr className="border-b border-border text-left text-xs text-muted-foreground">
                  <th className="pb-3 font-medium">支付方式</th>
                  <th className="pb-3 text-right font-medium">订单数</th>
                  <th className="pb-3 text-right font-medium">有效金额</th>
                  <th className="pb-3 pl-6 font-medium">金额占比</th>
                </tr>
              </thead>
              <tbody>
                {paymentMethods.map((item, i) => (
                  <tr key={item.payment_method} className="border-b border-border/60 last:border-0">
                    <td className="py-3">
                      <div className="flex items-center gap-2.5">
                        <PaymentIcon method={item.payment_method} className="h-6 w-6" />
                        <span className="font-medium text-foreground">{getPaymentLabel(item.payment_method)}</span>
                        <span
                          className="h-1.5 w-1.5 rounded-full"
                          style={{ background: PIE_COLORS[i % PIE_COLORS.length] }}
                        />
                      </div>
                    </td>
                    <td className="py-3 text-right text-muted-foreground">{item.orders.toLocaleString()}</td>
                    <td className="py-3 text-right font-medium text-foreground">{money(item.amount)}</td>
                    <td className="py-3 pl-6">
                      <div className="flex items-center gap-3">
                        <RatioBar ratio={item.ratio} className="w-28" />
                        <span className="w-12 text-right text-xs text-muted-foreground">{item.ratio.toFixed(1)}%</span>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>
    </div>
  )
}

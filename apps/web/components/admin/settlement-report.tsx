"use client"

import { useState, useEffect, useCallback, type ReactNode } from "react"
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
  RefreshCw,
  Loader2,
  ChevronLeft,
  ChevronRight,
  Eye,
  X,
  type LucideIcon,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { toast } from "sonner"
import { adminOrderApi, withMockFallback } from "@/services/api"
import { mockAdminOrderList, mockSettlementDistributors, mockSettlementReport } from "@/lib/mock-data"
import { PaymentIcon, getPaymentLabel } from "@/components/shared/payment-icon"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { Modal } from "@/components/ui/modal"
import { OrderDetailModal } from "@/components/admin/order-detail-modal"
import type { AdminOrderItem, SettlementDistributorDetail, SettlementReport } from "@/types"

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

/** 有效订单明细每页条数 */
const DETAIL_PAGE_SIZE = 10
/** 推广员排行每页条数 */
const RANK_PAGE_SIZE = 10

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

const ORDER_STATUS_LABELS: Record<string, string> = {
  PENDING: "待支付",
  PAID: "已支付",
  DELIVERED: "已发货",
  COMPLETED: "已完成",
  EXPIRED: "已过期",
  REFUNDED: "已退款",
  PARTIALLY_REFUNDED: "部分退款",
}

const STATUS_COLORS: Record<string, string> = {
  PENDING: "#f59e0b",
  PAID: "#3b82f6",
  DELIVERED: "#8b5cf6",
  COMPLETED: "#10b981",
  EXPIRED: "#94a3b8",
  REFUNDED: "#ef4444",
  PARTIALLY_REFUNDED: "#f97316",
}

function money(value: number | null | undefined): string {
  return `¥${Number(value ?? 0).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function compactMoney(value: number): string {
  const v = Number(value ?? 0)
  if (Math.abs(v) >= 100000000) return `¥${(v / 100000000).toFixed(2)}亿`
  if (Math.abs(v) >= 10000) return `¥${(v / 10000).toFixed(1)}万`
  return `¥${v.toFixed(0)}`
}

/** 悬浮提示使用前景色作背景、背景色作文字，形成强反差 */
const TOOLTIP_STYLE = {
  background: "hsl(var(--foreground))",
  border: "none",
  borderRadius: "8px",
  fontSize: "12px",
  color: "hsl(var(--background))",
  boxShadow: "0 6px 18px rgba(0, 0, 0, 0.28)",
  padding: "8px 10px",
} as const

const TOOLTIP_LABEL_STYLE = {
  color: "hsl(var(--background))",
  fontWeight: 600,
  marginBottom: 2,
} as const

const TOOLTIP_ITEM_STYLE = {
  color: "hsl(var(--background))",
} as const

/** 计算分页按钮展示的页码（最多 5 个） */
function pageNumbers(current: number, total: number): number[] {
  const span = 5
  let start = Math.max(1, current - Math.floor(span / 2))
  const end = Math.min(total, start + span - 1)
  start = Math.max(1, end - span + 1)
  return Array.from({ length: end - start + 1 }, (_, i) => start + i)
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

export function SettlementReportPanel() {
  const [report, setReport] = useState<SettlementReport | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null)

  // ── 日期筛选（默认本月）──
  const [datePreset, setDatePreset] = useState<DatePreset>("month")
  const [customStart, setCustomStart] = useState("")
  const [customEnd, setCustomEnd] = useState("")

  // ── 有效订单明细（分页，默认 10 条）──
  const [detailOrders, setDetailOrders] = useState<AdminOrderItem[]>([])
  const [detailTotal, setDetailTotal] = useState(0)
  const [detailLoading, setDetailLoading] = useState(true)
  const [detailPage, setDetailPage] = useState(1)

  // ── 订单详情弹窗（复用订单管理）──
  const [showDetail, setShowDetail] = useState<AdminOrderItem | null>(null)

  // ── 推广员排行弹窗（查看更多，分页，默认 10 条）──
  const [rankOpen, setRankOpen] = useState(false)
  const [rankRows, setRankRows] = useState<SettlementDistributorDetail[]>([])
  const [rankTotal, setRankTotal] = useState(0)
  const [rankLoading, setRankLoading] = useState(false)
  const [rankPage, setRankPage] = useState(1)

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

  // 筛选条件变化 → 有效订单明细回到第一页
  useEffect(() => {
    setDetailPage(1)
  }, [getParams])

  // 有效订单明细分页加载
  useEffect(() => {
    let cancelled = false
    setDetailLoading(true)
    const params = getParams()
    withMockFallback(
      () => adminOrderApi.getList({
        status: "PAID,DELIVERED,COMPLETED",
        start_date: params.start_date,
        end_date: params.end_date,
        page: detailPage,
        page_size: DETAIL_PAGE_SIZE,
      }),
      () => mockAdminOrderList({
        status: "PAID,DELIVERED,COMPLETED",
        page: detailPage,
        page_size: DETAIL_PAGE_SIZE,
      })
    )
      .then(data => {
        if (cancelled) return
        setDetailOrders(data.list)
        setDetailTotal(data.pagination.total)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        toast.error(err instanceof Error ? err.message : "加载有效订单明细失败")
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false)
      })
    return () => { cancelled = true }
  }, [getParams, detailPage])

  // 推广员排行：弹窗打开或翻页时加载
  useEffect(() => {
    if (!rankOpen) return
    let cancelled = false
    setRankLoading(true)
    const params = getParams()
    withMockFallback(
      () => adminOrderApi.getSettlementDistributors({
        start_date: params.start_date,
        end_date: params.end_date,
        page: rankPage,
        page_size: RANK_PAGE_SIZE,
      }),
      () => mockSettlementDistributors({
        start_date: params.start_date,
        end_date: params.end_date,
        page: rankPage,
        page_size: RANK_PAGE_SIZE,
      })
    )
      .then(data => {
        if (cancelled) return
        setRankRows(data.list)
        setRankTotal(data.pagination.total)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        toast.error(err instanceof Error ? err.message : "加载推广员排行失败")
      })
      .finally(() => {
        if (!cancelled) setRankLoading(false)
      })
    return () => { cancelled = true }
  }, [rankOpen, rankPage, getParams])

  const openRank = () => {
    setRankPage(1)
    setRankOpen(true)
  }

  const handleSelectPreset = (preset: DatePreset) => {
    if (preset === datePreset) return
    setDatePreset(preset)
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
  const statuses = report?.statuses ?? []
  const distributors = report?.distributors ?? []
  const granularityLabel = report?.trend_granularity === "month" ? "按月" : "按日"

  const commissionRatio = summary && summary.effective_amount > 0
    ? (summary.commission_amount / summary.effective_amount) * 100
    : 0
  const profitRatio = summary && summary.effective_amount > 0
    ? (summary.profit_amount / summary.effective_amount) * 100
    : 0

  const profitData = [
    { name: "分销佣金", value: summary?.commission_amount ?? 0, color: "#f59e0b" },
    { name: "剩余利润", value: summary?.profit_amount ?? 0, color: "#10b981" },
  ]
  const hasProfitData = profitData.some(d => d.value > 0)

  const detailTotalPages = Math.max(1, Math.ceil(detailTotal / DETAIL_PAGE_SIZE))
  const detailPages = pageNumbers(detailPage, detailTotalPages)

  const rankTotalPages = Math.max(1, Math.ceil(rankTotal / RANK_PAGE_SIZE))
  const rankPages = pageNumbers(rankPage, rankTotalPages)

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

      {/* 快捷筛选（按钮）+ 刷新 */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex flex-wrap items-center gap-1 rounded-lg bg-muted p-1">
          <CalendarDays className="ml-1.5 mr-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
          {DATE_PRESETS.map(preset => (
            <button
              key={preset.value}
              type="button"
              onClick={() => handleSelectPreset(preset.value)}
              className={cn(
                "rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
                datePreset === preset.value
                  ? "bg-background text-foreground shadow-sm"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              {preset.label}
            </button>
          ))}
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
                    labelStyle={TOOLTIP_LABEL_STYLE}
                    itemStyle={TOOLTIP_ITEM_STYLE}
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
                        wrapperStyle={{ zIndex: 20 }}
                        contentStyle={TOOLTIP_STYLE}
                        labelStyle={TOOLTIP_LABEL_STYLE}
                        itemStyle={TOOLTIP_ITEM_STYLE}
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

      {/* 利润构成 + 订单状态分布 + 分销佣金 */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <SectionCard title="利润构成" subtitle="有效金额 = 分销佣金 + 剩余利润">
          {!hasProfitData ? (
            <div className="flex h-48 items-center justify-center text-sm text-muted-foreground">
              {loading ? "加载中..." : "暂无数据"}
            </div>
          ) : (
            <div className="flex flex-col">
              <div className="relative h-44">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={profitData}
                      dataKey="value"
                      nameKey="name"
                      innerRadius={52}
                      outerRadius={76}
                      paddingAngle={2}
                      strokeWidth={0}
                      startAngle={90}
                      endAngle={-270}
                    >
                      {profitData.map(item => (
                        <Cell key={item.name} fill={item.color} />
                      ))}
                    </Pie>
                    <Tooltip
                      wrapperStyle={{ zIndex: 20 }}
                      contentStyle={TOOLTIP_STYLE}
                      labelStyle={TOOLTIP_LABEL_STYLE}
                      itemStyle={TOOLTIP_ITEM_STYLE}
                      formatter={(value, name) => [money(Number(value)), String(name)]}
                    />
                  </PieChart>
                </ResponsiveContainer>
                <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                  <span className="text-[11px] text-muted-foreground">利润率</span>
                  <span className="text-xl font-bold text-foreground">{profitRatio.toFixed(1)}%</span>
                </div>
              </div>
              <div className="mt-2 space-y-2">
                {profitData.map(item => {
                  const ratio = summary && summary.effective_amount > 0
                    ? (item.value / summary.effective_amount) * 100
                    : 0
                  return (
                    <div key={item.name} className="flex items-center gap-2 text-xs">
                      <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: item.color }} />
                      <span className="flex-1 text-muted-foreground">{item.name}</span>
                      <span className="text-muted-foreground">{ratio.toFixed(1)}%</span>
                      <span className="w-24 text-right font-medium text-foreground">{money(item.value)}</span>
                    </div>
                  )
                })}
                <div className="flex items-center gap-2 rounded-lg bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
                  <Undo2 className="h-3.5 w-3.5 shrink-0" />
                  退款 {money(summary?.refunded_amount)} 已从总业绩中扣除，不计入利润
                </div>
              </div>
            </div>
          )}
        </SectionCard>

        <SectionCard title="订单状态分布" subtitle="按区间内订单金额占比">
          {statuses.length === 0 ? (
            <div className="flex h-48 items-center justify-center text-sm text-muted-foreground">
              {loading ? "加载中..." : "暂无数据"}
            </div>
          ) : (
            <div className="flex flex-col">
              <div className="relative h-44">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={statuses}
                      dataKey="amount"
                      nameKey="status"
                      innerRadius={52}
                      outerRadius={76}
                      paddingAngle={2}
                      strokeWidth={0}
                    >
                      {statuses.map(item => (
                        <Cell key={item.status} fill={STATUS_COLORS[item.status] ?? "#94a3b8"} />
                      ))}
                    </Pie>
                    <Tooltip
                      wrapperStyle={{ zIndex: 20 }}
                      contentStyle={TOOLTIP_STYLE}
                      labelStyle={TOOLTIP_LABEL_STYLE}
                      itemStyle={TOOLTIP_ITEM_STYLE}
                      formatter={(value, name) => [money(Number(value)), ORDER_STATUS_LABELS[String(name)] ?? String(name)]}
                    />
                  </PieChart>
                </ResponsiveContainer>
                <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                  <span className="text-[11px] text-muted-foreground">订单总数</span>
                  <span className="text-xl font-bold text-foreground">
                    {(summary?.total_orders ?? 0).toLocaleString()}
                  </span>
                </div>
              </div>
              <div className="mt-2 max-h-28 space-y-2 overflow-y-auto pr-1">
                {statuses.map(item => (
                  <div key={item.status} className="flex items-center gap-2 text-xs">
                    <span
                      className="h-2 w-2 shrink-0 rounded-full"
                      style={{ background: STATUS_COLORS[item.status] ?? "#94a3b8" }}
                    />
                    <span className="flex-1 text-muted-foreground">
                      {ORDER_STATUS_LABELS[item.status] ?? item.status}
                    </span>
                    <span className="text-muted-foreground">{item.orders.toLocaleString()} 单</span>
                    <span className="w-12 text-right font-medium text-foreground">{item.ratio.toFixed(1)}%</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </SectionCard>

        <SectionCard
          title="分销佣金 Top 10"
          subtitle="一级 + 二级佣金合计"
          action={
            <button
              type="button"
              onClick={openRank}
              className="inline-flex items-center gap-1 whitespace-nowrap rounded-lg border border-input px-2.5 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
            >
              查看更多
              <ChevronRight className="h-3.5 w-3.5" />
            </button>
          }
        >
          {distributors.length === 0 ? (
            <div className="flex h-48 items-center justify-center text-sm text-muted-foreground">
              {loading ? "加载中..." : "暂无分销数据"}
            </div>
          ) : (
            <div className="max-h-[19rem] space-y-1 overflow-y-auto pr-1">
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
                      {d.distributor_code} · {d.orders} 单 · 佣金比例 {d.ratio.toFixed(1)}%
                    </p>
                  </div>
                  <div className="shrink-0 text-right">
                    <p className="whitespace-nowrap text-xs text-muted-foreground">销售 {money(d.sales_amount)}</p>
                    <p className="whitespace-nowrap text-sm font-semibold text-amber-600 dark:text-amber-400">
                      佣金 {money(d.amount)}
                    </p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      </div>

      {/* 有效订单明细 */}
      <SectionCard title="有效订单明细" subtitle="已支付 / 已发货 / 已完成订单，每页 10 条">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1180px] text-sm">
            <thead>
              <tr className="border-b border-border text-left text-xs text-muted-foreground">
                <th className="pb-3 pr-4 font-medium">订单号</th>
                <th className="pb-3 pr-4 font-medium">商品</th>
                <th className="pb-3 pr-4 font-medium">数量</th>
                <th className="pb-3 pr-4 font-medium">客户</th>
                <th className="pb-3 pr-4 font-medium">推广员</th>
                <th className="pb-3 pr-4 font-medium">支付方式</th>
                <th className="pb-3 pr-4 font-medium">设备</th>
                <th className="pb-3 pr-4 text-right font-medium">金额</th>
                <th className="pb-3 pr-4 font-medium">状态</th>
                <th className="pb-3 pr-4 font-medium">下单时间</th>
                <th className="pb-3 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {detailLoading ? (
                <tr>
                  <td colSpan={11} className="py-12">
                    <div className="flex items-center justify-center">
                      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    </div>
                  </td>
                </tr>
              ) : detailOrders.length === 0 ? (
                <tr>
                  <td colSpan={11} className="py-8 text-center text-sm text-muted-foreground">当前筛选区间暂无有效订单</td>
                </tr>
              ) : (
                detailOrders.map(order => (
                  <tr key={order.id} className="border-b border-border/60 transition-colors last:border-0 hover:bg-muted/20">
                    <td className="py-3 pr-4">
                      <span className="font-mono text-sm font-medium text-foreground" title={order.id}>
                        {order.id.length > 16 ? `${order.id.slice(0, 8)}...${order.id.slice(-4)}` : order.id}
                      </span>
                    </td>
                    <td className="py-3 pr-4">
                      <span className="text-sm text-foreground">
                        {order.items?.[0]?.product_title || "-"}
                        {order.items?.[0]?.spec_name && (
                          <span className="text-muted-foreground"> - {order.items[0].spec_name}</span>
                        )}
                        {(order.items?.length ?? 0) > 1 && (
                          <span className="ml-1 text-xs text-muted-foreground">等{order.items.length}件</span>
                        )}
                      </span>
                    </td>
                    <td className="py-3 pr-4 text-foreground">
                      {order.items?.reduce((sum, item) => sum + item.quantity, 0) || 0}
                    </td>
                    <td className="py-3 pr-4">
                      <div className="flex flex-col gap-0.5">
                        <span className="text-xs text-foreground">{order.username || "游客"}</span>
                        <span className="text-xs text-muted-foreground">{order.email || order.phone || "-"}</span>
                      </div>
                    </td>
                    <td className="py-3 pr-4">
                      <span className="text-xs text-foreground">{order.promoter || "-"}</span>
                    </td>
                    <td className="py-3 pr-4">
                      <span className="inline-flex items-center gap-1.5 text-foreground">
                        <PaymentIcon method={order.payment_method} className="h-4 w-4" />
                        {getPaymentLabel(order.payment_method)}
                      </span>
                    </td>
                    <td className="max-w-[120px] truncate py-3 pr-4 text-xs text-muted-foreground" title={order.device ?? ""}>
                      {order.device || "-"}
                    </td>
                    <td className="py-3 pr-4 text-right">
                      <span className="font-medium text-foreground">¥{order.actual_amount.toFixed(2)}</span>
                    </td>
                    <td className="py-3 pr-4">
                      <OrderStatusBadge status={order.status} />
                    </td>
                    <td className="whitespace-nowrap py-3 pr-4 text-sm text-muted-foreground">
                      {new Date(order.created_at).toLocaleString("zh-CN", { hour12: false })}
                    </td>
                    <td className="py-3 text-right">
                      <button
                        type="button"
                        onClick={() => setShowDetail(order)}
                        className="inline-flex items-center gap-1 whitespace-nowrap rounded-lg border border-input px-2.5 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                      >
                        <Eye className="h-3.5 w-3.5" />
                        查看详情
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* 分页 */}
        {detailTotal > 0 && (
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
            <p className="text-xs text-muted-foreground">
              共 {detailTotal.toLocaleString()} 条 · 第 {detailPage} / {detailTotalPages} 页
            </p>
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setDetailPage(p => Math.max(1, p - 1))}
                disabled={detailPage === 1 || detailLoading}
                className="flex h-8 w-8 items-center justify-center rounded-lg border border-input text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              {detailPages.map(page => (
                <button
                  key={page}
                  type="button"
                  onClick={() => setDetailPage(page)}
                  disabled={detailLoading}
                  className={cn(
                    "flex h-8 min-w-8 items-center justify-center rounded-lg border px-2 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50",
                    page === detailPage
                      ? "border-primary bg-primary text-primary-foreground"
                      : "border-input text-muted-foreground hover:bg-accent hover:text-foreground"
                  )}
                >
                  {page}
                </button>
              ))}
              <button
                type="button"
                onClick={() => setDetailPage(p => Math.min(detailTotalPages, p + 1))}
                disabled={detailPage >= detailTotalPages || detailLoading}
                className="flex h-8 w-8 items-center justify-center rounded-lg border border-input text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
              >
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* 订单详情弹窗（复用订单管理样式与内容） */}
      <OrderDetailModal order={showDetail} onClose={() => setShowDetail(null)} />

      {/* 推广员排行弹窗 */}
      <Modal open={rankOpen} onClose={() => setRankOpen(false)} className="max-w-5xl">
        <div className="flex items-start justify-between gap-4 border-b border-border px-6 py-4">
          <div>
            <h3 className="text-base font-semibold text-foreground">推广员排行</h3>
            <p className="mt-0.5 text-xs text-muted-foreground">
              {datePresetLabel} · 按佣金合计降序 · 共 {rankTotal.toLocaleString()} 位推广员
            </p>
          </div>
          <button
            type="button"
            onClick={() => setRankOpen(false)}
            className="rounded-lg p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="max-h-[60vh] overflow-auto px-6 py-4">
          <table className="w-full min-w-[860px] text-sm">
            <thead>
              <tr className="border-b border-border text-left text-xs text-muted-foreground">
                <th className="pb-3 pr-4 font-medium">排名</th>
                <th className="pb-3 pr-4 font-medium">推广员</th>
                <th className="pb-3 pr-4 text-right font-medium">销售金额</th>
                <th className="pb-3 pr-4 text-right font-medium">订单数</th>
                <th className="pb-3 pr-4 text-right font-medium">一级佣金</th>
                <th className="pb-3 pr-4 text-right font-medium">二级佣金</th>
                <th className="pb-3 pr-4 text-right font-medium">佣金合计</th>
                <th className="pb-3 text-right font-medium">佣金比例</th>
              </tr>
            </thead>
            <tbody>
              {rankLoading ? (
                <tr>
                  <td colSpan={8} className="py-12">
                    <div className="flex items-center justify-center">
                      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    </div>
                  </td>
                </tr>
              ) : rankRows.length === 0 ? (
                <tr>
                  <td colSpan={8} className="py-10 text-center text-sm text-muted-foreground">当前筛选区间暂无推广员数据</td>
                </tr>
              ) : (
                rankRows.map((row, i) => {
                  const rank = (rankPage - 1) * RANK_PAGE_SIZE + i + 1
                  return (
                    <tr key={`${row.distributor_code}-${rank}`} className="border-b border-border/60 last:border-0 hover:bg-muted/20">
                      <td className="py-3 pr-4">
                        <span
                          className={cn(
                            "inline-flex h-6 w-6 items-center justify-center rounded-md text-xs font-semibold",
                            rank === 1
                              ? "bg-amber-500/15 text-amber-600 dark:text-amber-400"
                              : rank === 2
                                ? "bg-slate-400/15 text-slate-600 dark:text-slate-300"
                                : rank === 3
                                  ? "bg-orange-500/15 text-orange-600 dark:text-orange-400"
                                  : "bg-muted text-muted-foreground"
                          )}
                        >
                          {rank}
                        </span>
                      </td>
                      <td className="py-3 pr-4">
                        <div className="flex flex-col gap-0.5">
                          <span className="text-sm font-medium text-foreground">{row.name || "-"}</span>
                          <span className="font-mono text-xs text-muted-foreground">{row.distributor_code || "-"}</span>
                        </div>
                      </td>
                      <td className="whitespace-nowrap py-3 pr-4 text-right text-foreground">{money(row.sales_amount)}</td>
                      <td className="py-3 pr-4 text-right text-foreground">{row.orders.toLocaleString()}</td>
                      <td className="whitespace-nowrap py-3 pr-4 text-right text-muted-foreground">{money(row.commission_amount)}</td>
                      <td className="whitespace-nowrap py-3 pr-4 text-right text-muted-foreground">{money(row.parent_commission_amount)}</td>
                      <td className="whitespace-nowrap py-3 pr-4 text-right font-semibold text-amber-600 dark:text-amber-400">
                        {money(row.amount)}
                      </td>
                      <td className="whitespace-nowrap py-3 text-right text-foreground">{row.ratio.toFixed(1)}%</td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>

        {rankTotal > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border px-6 py-3">
            <p className="text-xs text-muted-foreground">
              共 {rankTotal.toLocaleString()} 条 · 第 {rankPage} / {rankTotalPages} 页
            </p>
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setRankPage(p => Math.max(1, p - 1))}
                disabled={rankPage === 1 || rankLoading}
                className="flex h-8 w-8 items-center justify-center rounded-lg border border-input text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              {rankPages.map(page => (
                <button
                  key={page}
                  type="button"
                  onClick={() => setRankPage(page)}
                  disabled={rankLoading}
                  className={cn(
                    "flex h-8 min-w-8 items-center justify-center rounded-lg border px-2 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50",
                    page === rankPage
                      ? "border-primary bg-primary text-primary-foreground"
                      : "border-input text-muted-foreground hover:bg-accent hover:text-foreground"
                  )}
                >
                  {page}
                </button>
              ))}
              <button
                type="button"
                onClick={() => setRankPage(p => Math.min(rankTotalPages, p + 1))}
                disabled={rankPage >= rankTotalPages || rankLoading}
                className="flex h-8 w-8 items-center justify-center rounded-lg border border-input text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
              >
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        )}
      </Modal>
    </div>
  )
}

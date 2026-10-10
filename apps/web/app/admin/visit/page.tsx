"use client"

import { useCallback, useEffect, useRef, useState, type ComponentType, type ReactNode } from "react"
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
  ChevronLeft,
  ChevronRight,
  Link2,
  Copy,
  Plus,
  Pencil,
  Trash2,
  Power,
  ArrowLeft,
  ShoppingCart,
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
  type TooltipProps,
} from "recharts"
import { cn } from "@/lib/utils"
import { toast } from "sonner"
import { adminVisitApi, type VisitQueryParams } from "@/services/api"
import type {
  VisitAnalytics,
  VisitLogItem,
  VisitOptions,
  VisitConfig,
  ChannelLink,
  ChannelAnalytics,
} from "@/types"

const PAGE_SIZE = 20

const QUICK_RANGES: { key: string; label: string }[] = [
  { key: "today", label: "今日" },
  { key: "yesterday", label: "昨日" },
  { key: "7d", label: "近 7 天" },
  { key: "30d", label: "近 30 天" },
  { key: "90d", label: "近 90 天" },
]

/** 图表统一色板（与参考项目一致） */
const PALETTE = [
  "#3A7AFE",
  "#22C55E",
  "#F59E0B",
  "#EF4444",
  "#8B5CF6",
  "#06B6D4",
  "#EC4899",
  "#84CC16",
]

const AXIS_STROKE = "hsl(var(--muted-foreground))"
const GRID_STROKE = "hsl(var(--border))"
const PEAK_COLOR = "#F59E0B"

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

function shortLabel(name: string, max = 8): string {
  if (!name) return "-"
  return name.length > max ? `${name.slice(0, max)}…` : name
}

/** 反差色图表提示：使用 popover 前景 / 背景，保证在任意主题下都清晰可读 */
function ChartTooltip({
  active,
  payload,
  label,
  unit = "",
  total,
  labelFormatter,
}: TooltipProps<number, string> & {
  unit?: string
  total?: number
  labelFormatter?: (v: string | number) => string
}) {
  if (!active || !payload || payload.length === 0) return null
  const items = payload.filter((p) => p.value !== undefined && p.value !== null)
  if (items.length === 0) return null

  const sum =
    total ??
    items.reduce((s, p) => s + (typeof p.value === "number" ? p.value : Number(p.value) || 0), 0)

  return (
    <div className="pointer-events-none min-w-[140px] rounded-lg border border-border bg-popover px-3 py-2 shadow-xl">
      {label !== undefined && label !== null && label !== "" ? (
        <div className="mb-1.5 text-xs font-semibold text-popover-foreground">
          {labelFormatter ? labelFormatter(label) : String(label)}
        </div>
      ) : null}
      <div className="flex flex-col gap-1">
        {items.map((p, i) => {
          const raw = p as {
            color?: string
            fill?: string
            stroke?: string
            payload?: { fill?: string }
          }
          const color =
            raw.color || raw.fill || raw.payload?.fill || raw.stroke || "hsl(var(--primary))"
          const val = typeof p.value === "number" ? p.value : Number(p.value) || 0
          return (
            <div key={`${p.name ?? i}-${i}`} className="flex items-center gap-2 text-xs">
              <span className="h-2.5 w-2.5 shrink-0 rounded-full" style={{ background: color }} />
              <span className="text-muted-foreground">{p.name}</span>
              <span className="ml-auto font-semibold tabular-nums text-popover-foreground">
                {fmtNum(val)}
                {unit}
              </span>
              {total ? (
                <span className="tabular-nums text-muted-foreground">
                  {fmtNum(sum > 0 ? (val / sum) * 100 : 0)}%
                </span>
              ) : null}
            </div>
          )
        })}
      </div>
    </div>
  )
}

function ChartCard({
  title,
  sub,
  action,
  children,
  className,
}: {
  title: string
  sub?: ReactNode
  action?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <div className={cn("flex flex-col rounded-xl border border-border bg-card p-4 shadow-sm", className)}>
      <div className="mb-3 flex items-center justify-between gap-3">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
          {title}
          {sub ? <span className="text-xs font-normal text-muted-foreground">{sub}</span> : null}
        </h3>
        {action}
      </div>
      <div className="flex-1">{children}</div>
    </div>
  )
}

function Empty({ text = "暂无数据" }: { text?: string }) {
  return (
    <div className="flex h-full min-h-[180px] items-center justify-center text-sm text-muted-foreground">
      {text}
    </div>
  )
}

function StatTile({
  icon: Icon,
  label,
  value,
  sub,
}: {
  icon: ComponentType<{ className?: string }>
  label: string
  value: string | number
  sub?: string
}) {
  return (
    <div className="flex flex-col rounded-xl border border-border bg-card p-3.5 shadow-sm">
      <div className="flex items-center gap-1.5 text-muted-foreground">
        <Icon className="h-3.5 w-3.5" />
        <span className="text-xs font-medium">{label}</span>
      </div>
      <div className="mt-1.5 text-xl font-bold leading-tight text-foreground">{value}</div>
      {sub ? <div className="mt-1 truncate text-xs text-muted-foreground">{sub}</div> : null}
    </div>
  )
}

function MiniStat({ label, value, sub }: { label: string; value: string | number; sub?: string }) {
  return (
    <div className="rounded-lg bg-muted/60 px-3 py-2.5">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 text-lg font-bold text-foreground">{value}</div>
      {sub ? <div className="text-xs text-muted-foreground">{sub}</div> : null}
    </div>
  )
}

/** 环形图（含自绘图例），用于来源 / 设备 / 系统 / 浏览器 */
function DonutChart({
  data,
  height = 176,
}: {
  data: { name: string; value: number }[]
  height?: number
}) {
  const total = data.reduce((s, d) => s + d.value, 0)
  if (data.length === 0 || total === 0) return <Empty />
  const pieData = data.map((d, idx) => ({ ...d, fill: PALETTE[idx % PALETTE.length] }))
  return (
    <div className="flex flex-col gap-3">
      <div className="w-full" style={{ height }}>
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={pieData}
              dataKey="value"
              nameKey="name"
              innerRadius="60%"
              outerRadius="88%"
              paddingAngle={2}
              stroke="hsl(var(--card))"
              strokeWidth={2}
            >
              {pieData.map((entry, idx) => (
                <Cell key={`${entry.name}-${idx}`} fill={entry.fill} />
              ))}
            </Pie>
            <Tooltip content={<ChartTooltip total={total} />} />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <div className="flex flex-col gap-1.5">
        {data.map((d, idx) => (
          <div key={`${d.name}-${idx}`} className="flex items-center gap-2 text-xs">
            <span
              className="h-2.5 w-2.5 shrink-0 rounded-full"
              style={{ background: PALETTE[idx % PALETTE.length] }}
            />
            <span className="truncate text-foreground" title={d.name}>
              {d.name}
            </span>
            <span className="ml-auto shrink-0 tabular-nums text-muted-foreground">
              {fmtNum(d.value)} · {fmtNum((d.value / total) * 100)}%
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

/** 访问趋势：PV / UV 双折线（带渐变面积） */
function TrendChart({ data }: { data: { date: string; pv: number; uv: number }[] }) {
  if (data.length === 0) return <Empty />
  return (
    <div className="h-[320px] w-full">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 8, right: 12, left: -18, bottom: 0 }}>
          <defs>
            <linearGradient id="visitTrendPv" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#3A7AFE" stopOpacity={0.28} />
              <stop offset="100%" stopColor="#3A7AFE" stopOpacity={0} />
            </linearGradient>
            <linearGradient id="visitTrendUv" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#22C55E" stopOpacity={0.22} />
              <stop offset="100%" stopColor="#22C55E" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke={GRID_STROKE} vertical={false} />
          <XAxis
            dataKey="date"
            tickFormatter={(v) => String(v).slice(5)}
            tick={{ fontSize: 11, fill: AXIS_STROKE }}
            axisLine={{ stroke: GRID_STROKE }}
            tickLine={false}
            minTickGap={16}
          />
          <YAxis
            tick={{ fontSize: 11, fill: AXIS_STROKE }}
            axisLine={false}
            tickLine={false}
            allowDecimals={false}
          />
          <Tooltip cursor={{ stroke: GRID_STROKE }} content={<ChartTooltip />} />
          <Area
            type="monotone"
            dataKey="pv"
            name="访问量 (PV)"
            stroke="#3A7AFE"
            strokeWidth={2}
            fill="url(#visitTrendPv)"
            dot={false}
            activeDot={{ r: 4 }}
          />
          <Area
            type="monotone"
            dataKey="uv"
            name="访客数 (UV)"
            stroke="#22C55E"
            strokeWidth={2}
            fill="url(#visitTrendUv)"
            dot={false}
            activeDot={{ r: 4 }}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  )
}

/** 时段分布：竖向柱状图，峰值高亮 */
function HoursChart({ data, peakHour }: { data: { hour: string; pv: number }[]; peakHour: number }) {
  if (data.length === 0) return <Empty />
  const hasData = data.some((d) => d.pv > 0)
  if (!hasData) return <Empty />
  return (
    <div className="h-[300px] w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 8, right: 8, left: -18, bottom: 0 }}>
          <defs>
            <linearGradient id="visitHourBar" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#3A7AFE" />
              <stop offset="100%" stopColor="#93B5FE" />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke={GRID_STROKE} vertical={false} />
          <XAxis
            dataKey="hour"
            interval={1}
            tick={{ fontSize: 10, fill: AXIS_STROKE }}
            axisLine={{ stroke: GRID_STROKE }}
            tickLine={false}
          />
          <YAxis
            tick={{ fontSize: 11, fill: AXIS_STROKE }}
            axisLine={false}
            tickLine={false}
            allowDecimals={false}
          />
          <Tooltip cursor={{ fill: "hsl(var(--accent))" }} content={<ChartTooltip unit=" 次" />} />
          <Bar dataKey="pv" name="访问量" radius={[4, 4, 0, 0]} maxBarSize={22}>
            {data.map((d, idx) => (
              <Cell
                key={d.hour}
                fill={idx === peakHour && d.pv > 0 ? PEAK_COLOR : "url(#visitHourBar)"}
              />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

/** 单行刻度：始终一行显示，超长截断并以原生 title 悬浮提示完整内容 */
function SingleLineTick({
  x,
  y,
  payload,
  maxChars = 8,
}: {
  x?: number
  y?: number
  payload?: { value?: string | number }
  maxChars?: number
}) {
  const full = payload?.value === undefined || payload?.value === null ? "" : String(payload.value)
  return (
    <text x={x} y={y} dx={-6} dy={4} textAnchor="end" fontSize={11} fill={AXIS_STROKE}>
      <title>{full}</title>
      {shortLabel(full, maxChars)}
    </text>
  )
}

/** 横向条形图：地域 / 运营商 / 城市 / 来源域名 */
function HBarChart({
  data,
  gradId,
  yAxisWidth = 84,
  maxLabel = 8,
  unit = " 次",
}: {
  data: { name: string; value: number }[]
  gradId: string
  yAxisWidth?: number
  maxLabel?: number
  unit?: string
}) {
  if (data.length === 0) return <Empty />
  const items = data.slice().reverse()
  return (
    <div className="h-[320px] w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={items} layout="vertical" margin={{ top: 4, right: 28, left: 4, bottom: 4 }}>
          <defs>
            <linearGradient id={gradId} x1="0" y1="0" x2="1" y2="0">
              <stop offset="0%" stopColor="#3A7AFE" />
              <stop offset="100%" stopColor="#93B5FE" />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke={GRID_STROKE} horizontal={false} />
          <XAxis
            type="number"
            tick={{ fontSize: 11, fill: AXIS_STROKE }}
            axisLine={{ stroke: GRID_STROKE }}
            tickLine={false}
            allowDecimals={false}
          />
          <YAxis
            type="category"
            dataKey="name"
            width={yAxisWidth}
            interval={0}
            tick={<SingleLineTick maxChars={maxLabel} />}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip cursor={{ fill: "hsl(var(--accent))" }} content={<ChartTooltip unit={unit} />} />
          <Bar dataKey="value" name="PV" fill={`url(#${gradId})`} radius={[0, 4, 4, 0]} barSize={14} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

/** 漏斗各层级浅色渐变（每层不同色、均为浅色系，在暗色背景下对比清晰） */
const FUNNEL_LEVEL_GRADIENTS: [string, string][] = [
  ["#6E9BFF", "#AEC8FF"],
  ["#57C9AE", "#A6E6D6"],
  ["#E0A94E", "#F7DDA6"],
  ["#E07AAE", "#F6C4DC"],
  ["#9A88F5", "#CFC4FF"],
  ["#57B6E8", "#A9DCF8"],
  ["#8DCF6A", "#C6EDB0"],
  ["#E58A8A", "#F6C3C3"],
]

/** 访问转化漏斗：居中梯形漏斗 + 右侧标签，每层浅色渐变，数据文字跟随层级颜色 */
function FunnelBars({ stages }: { stages: VisitAnalytics["funnel"] }) {
  if (stages.length === 0) return <Empty />

  const STAGE_H = 46
  const GAP = 3
  const VIEW_W = 560
  const CX = 150
  const MAX_HALF = 112
  const MIN_HALF = 10
  const LABEL_X = 292
  const total = stages.length
  const viewH = total * STAGE_H + (total - 1) * GAP
  const halfOf = (rate: number) =>
    MIN_HALF + ((MAX_HALF - MIN_HALF) * Math.max(0, Math.min(100, rate))) / 100

  // 各阶段人数相互独立：直接购买会跳过购物车，导致下层人数可能大于上层；
  // 这里把图形宽度钳制为单调不增，保证漏斗形状始终正常（文字仍显示各阶段真实人数）
  const topHalves: number[] = []
  stages.forEach((s, i) => {
    const w = halfOf(s.rate)
    topHalves.push(i === 0 ? w : Math.min(w, topHalves[i - 1]))
  })
  const bottomHalves = stages.map((_, i) =>
    i < total - 1 ? topHalves[i + 1] : Math.max(MIN_HALF * 0.5, topHalves[i] * 0.4),
  )

  return (
    <svg
      viewBox={`0 0 ${VIEW_W} ${viewH}`}
      className="mx-auto block w-full max-w-[520px]"
      style={{ height: "auto" }}
      role="img"
    >
      <defs>
        {stages.map((s, i) => {
          const [from, to] = FUNNEL_LEVEL_GRADIENTS[i % FUNNEL_LEVEL_GRADIENTS.length]
          return (
            <linearGradient key={s.stage} id={`visitFunnelGrad${i}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={from} />
              <stop offset="100%" stopColor={to} />
            </linearGradient>
          )
        })}
      </defs>
      {stages.map((s, i) => {
        const [levelColor] = FUNNEL_LEVEL_GRADIENTS[i % FUNNEL_LEVEL_GRADIENTS.length]
        const y0 = i * (STAGE_H + GAP)
        const y1 = y0 + STAGE_H
        const ht = topHalves[i]
        const hb = bottomHalves[i]
        const cy = y0 + STAGE_H / 2
        const mid = (ht + hb) / 2
        return (
          <g key={s.stage}>
            <polygon
              points={`${CX - ht},${y0} ${CX + ht},${y0} ${CX + hb},${y1} ${CX - hb},${y1}`}
              fill={`url(#visitFunnelGrad${i})`}
            />
            <line
              x1={CX + mid + 4}
              y1={cy}
              x2={LABEL_X - 10}
              y2={cy}
              style={{ stroke: "hsl(var(--border))" }}
              strokeWidth={1}
            />
            <text
              x={LABEL_X}
              y={cy - 3}
              style={{ fill: "hsl(var(--foreground))", fontSize: 12, fontWeight: 500 }}
            >
              {s.label}
            </text>
            <text x={LABEL_X} y={cy + 12} style={{ fill: levelColor, fontSize: 10.5, fontWeight: 600 }}>
              {`${fmtNum(s.visitors)} 人 · ${fmtNum(s.rate)}%${i > 0 ? ` · 转化 ${fmtNum(s.conversion)}%` : ""}`}
            </text>
          </g>
        )
      })}
    </svg>
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
      <span
        className={cn(
          "relative h-5 w-9 shrink-0 rounded-full transition-colors",
          checked ? "bg-primary" : "bg-muted"
        )}
      >
        <span
          className={cn(
            "absolute left-0.5 top-0.5 h-4 w-4 rounded-full bg-white shadow transition-transform",
            checked && "translate-x-4"
          )}
        />
      </span>
    </button>
  )
}

const CHANNEL_PAGE_SIZE = 20

type ChannelForm = {
  code: string
  name: string
  channel: string
  target_path: string
  remark: string
  enabled: boolean
}

const EMPTY_CHANNEL_FORM: ChannelForm = {
  code: "",
  name: "",
  channel: "",
  target_path: "/",
  remark: "",
  enabled: true,
}

function copyToClipboard(text: string): Promise<void> {
  if (typeof navigator !== "undefined" && navigator.clipboard) {
    return navigator.clipboard.writeText(text)
  }
  return Promise.reject(new Error("当前环境不支持复制"))
}

/** 渠道链接管理 + 渠道维度流量分析（引流归因） */
function ChannelsPanel({
  range,
  startDate,
  endDate,
  refreshToken,
}: {
  range: string
  startDate: string
  endDate: string
  /** 顶部「刷新」按钮递增该值以触发列表及已打开详情的重新拉取 */
  refreshToken: number
}) {
  const query = useCallback((): VisitQueryParams => {
    if (startDate && endDate) return { start_date: startDate, end_date: endDate }
    return { range }
  }, [range, startDate, endDate])

  // 渠道列表
  const [list, setList] = useState<ChannelLink[]>([])
  const [loading, setLoading] = useState(true)
  const [keyword, setKeyword] = useState("")
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)

  // 新建 / 编辑弹窗
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<ChannelLink | null>(null)
  const [form, setForm] = useState<ChannelForm>(EMPTY_CHANNEL_FORM)
  const [saving, setSaving] = useState(false)
  const [deletingId, setDeletingId] = useState<string | null>(null)

  // 渠道详情
  const [selected, setSelected] = useState<ChannelLink | null>(null)
  const [analytics, setAnalytics] = useState<ChannelAnalytics | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [dTab, setDTab] = useState<"overview" | "visits">("overview")
  const [visits, setVisits] = useState<VisitLogItem[]>([])
  const [visitsTotal, setVisitsTotal] = useState(0)
  const [visitsPage, setVisitsPage] = useState(1)
  const [visitsLoading, setVisitsLoading] = useState(false)

  const fetchList = useCallback(
    async (silent = false) => {
      if (!silent) setLoading(true)
      try {
        const data = await adminVisitApi.getChannels({
          keyword: keyword || undefined,
          page,
          page_size: CHANNEL_PAGE_SIZE,
        })
        setList(data.list)
        setTotal(data.pagination.total)
      } catch (err) {
        if (!silent) {
          setList([])
          setTotal(0)
          toast.error(err instanceof Error ? err.message : "加载渠道链接失败")
        }
      } finally {
        if (!silent) setLoading(false)
      }
    },
    [keyword, page]
  )

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const fetchDetail = useCallback(async () => {
    if (!selected) return
    setDetailLoading(true)
    try {
      const data = await adminVisitApi.getChannelAnalytics(selected.code, query())
      setAnalytics(data)
    } catch (err) {
      setAnalytics(null)
      toast.error(err instanceof Error ? err.message : "加载渠道分析失败")
    } finally {
      setDetailLoading(false)
    }
  }, [selected, query])

  const fetchVisits = useCallback(async () => {
    if (!selected) return
    setVisitsLoading(true)
    try {
      const data = await adminVisitApi.getChannelVisits(selected.code, {
        ...query(),
        page: visitsPage,
        page_size: CHANNEL_PAGE_SIZE,
      })
      setVisits(data.list)
      setVisitsTotal(data.pagination.total)
    } catch (err) {
      setVisits([])
      setVisitsTotal(0)
      toast.error(err instanceof Error ? err.message : "加载渠道访问明细失败")
    } finally {
      setVisitsLoading(false)
    }
  }, [selected, query, visitsPage])

  useEffect(() => {
    if (selected) fetchDetail()
  }, [selected, fetchDetail])

  useEffect(() => {
    if (selected && dTab === "visits") fetchVisits()
  }, [selected, dTab, fetchVisits])

  // 顶部「刷新」联动：仅当 refreshToken 变化时重新拉取列表及已打开的详情/明细
  const prevRefreshToken = useRef(refreshToken)
  useEffect(() => {
    if (prevRefreshToken.current === refreshToken) return
    prevRefreshToken.current = refreshToken
    fetchList(true)
    if (selected) {
      fetchDetail()
      if (dTab === "visits") fetchVisits()
    }
  }, [refreshToken, fetchList, fetchDetail, fetchVisits, selected, dTab])

  const openDetail = (link: ChannelLink) => {
    setSelected(link)
    setAnalytics(null)
    setDTab("overview")
    setVisits([])
    setVisitsPage(1)
  }

  const closeDetail = () => {
    setSelected(null)
    setAnalytics(null)
    setVisits([])
    setVisitsPage(1)
  }

  const openCreate = () => {
    setEditing(null)
    setForm(EMPTY_CHANNEL_FORM)
    setFormOpen(true)
  }

  const openEdit = (link: ChannelLink) => {
    setEditing(link)
    setForm({
      code: link.code,
      name: link.name ?? "",
      channel: link.channel ?? "",
      target_path: link.target_path || "/",
      remark: link.remark ?? "",
      enabled: link.enabled,
    })
    setFormOpen(true)
  }

  const submitForm = async () => {
    if (saving) return
    const name = form.name.trim()
    if (!name) {
      toast.error("请填写渠道名称")
      return
    }
    setSaving(true)
    try {
      const payload: Partial<{
        code: string
        name: string
        channel: string
        target_path: string
        remark: string
        enabled: boolean
      }> = {
        name,
        channel: form.channel.trim(),
        target_path: form.target_path.trim() || "/",
        remark: form.remark.trim(),
        enabled: form.enabled,
      }
      if (editing) {
        const updated = await adminVisitApi.updateChannel(editing.id, payload)
        toast.success("渠道已更新")
        setList((prev) => prev.map((it) => (it.id === editing.id ? { ...it, ...updated } : it)))
        if (selected && selected.id === editing.id) setSelected({ ...selected, ...updated })
      } else {
        const code = form.code.trim()
        if (code) payload.code = code
        await adminVisitApi.createChannel(payload)
        toast.success("渠道已创建")
      }
      setFormOpen(false)
      fetchList(true)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  const toggleEnabled = async (link: ChannelLink) => {
    try {
      const updated = await adminVisitApi.updateChannel(link.id, { enabled: !link.enabled })
      setList((prev) => prev.map((it) => (it.id === link.id ? { ...it, ...updated } : it)))
      if (selected && selected.id === link.id) setSelected({ ...selected, ...updated })
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  const removeChannel = async (link: ChannelLink) => {
    if (
      typeof window !== "undefined" &&
      !window.confirm(`确认删除渠道「${link.name || link.code}」？此操作不可恢复。`)
    ) {
      return
    }
    setDeletingId(link.id)
    try {
      await adminVisitApi.deleteChannel(link.id)
      toast.success("渠道已删除")
      if (selected && selected.id === link.id) closeDetail()
      fetchList(true)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败")
    } finally {
      setDeletingId(null)
    }
  }

  const copyLink = async (link: ChannelLink) => {
    try {
      await copyToClipboard(link.url)
      toast.success("短链已复制")
    } catch {
      toast.error("复制失败，请手动复制")
    }
  }

  const listPages = Math.max(1, Math.ceil(total / CHANNEL_PAGE_SIZE))
  const visitPages = Math.max(1, Math.ceil(visitsTotal / CHANNEL_PAGE_SIZE))

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
  const deviceData = analytics ? analytics.devices.map((d) => ({ name: d.name, value: d.pv })) : []
  const regionData = analytics
    ? analytics.regions.slice(0, 10).map((r) => ({ name: r.name, value: r.pv }))
    : []
  const peakHour = hourData.reduce((best, cur, idx) => (cur.pv > (hourData[best]?.pv ?? 0) ? idx : best), 0)

  const trafficStats: { icon: ComponentType<{ className?: string }>; label: string; value: string; sub?: string }[] =
    analytics
      ? [
          {
            icon: MousePointerClick,
            label: "链接点击",
            value: fmtNum(analytics.traffic.clicks),
            sub: `独立点击 ${fmtNum(analytics.traffic.unique_clicks)}`,
          },
          {
            icon: Eye,
            label: "引流访问 (PV)",
            value: fmtNum(analytics.traffic.pv),
            sub: `人均 ${fmtNum(analytics.traffic.pv_per_visitor)} 次`,
          },
          {
            icon: Users,
            label: "访客 (UV)",
            value: fmtNum(analytics.traffic.uv),
            sub: `独立 IP ${fmtNum(analytics.traffic.ips)}`,
          },
          {
            icon: Activity,
            label: "会话数",
            value: fmtNum(analytics.traffic.sessions),
            sub: `新访客 ${fmtNum(analytics.traffic.new_uv)}`,
          },
          {
            icon: TrendingUp,
            label: "跳出率",
            value: `${fmtNum(analytics.traffic.bounce_rate)}%`,
            sub: `跳出 ${fmtNum(analytics.traffic.bounces)}`,
          },
          {
            icon: Clock,
            label: "平均停留",
            value: fmtDuration(analytics.traffic.avg_duration_sec),
            sub: `人均 ${fmtNum(analytics.traffic.avg_page_count)} 页`,
          },
          {
            icon: ShoppingCart,
            label: "转化率",
            value: `${fmtNum(analytics.conversion.conversion_rate)}%`,
            sub: `订单 ${fmtNum(analytics.conversion.orders)}`,
          },
          {
            icon: ContactRound,
            label: "支付订单",
            value: fmtNum(analytics.conversion.paid_orders),
            sub: `客单价 ${fmtNum(analytics.conversion.aov)}`,
          },
        ]
      : []

  // ── 渠道详情视图 ──
  if (selected) {
    return (
      <div className="flex flex-col gap-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={closeDetail}
              className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
            >
              <ArrowLeft className="h-4 w-4" />
              返回
            </button>
            <div>
              <h2 className="flex items-center gap-2 text-lg font-semibold text-foreground">
                {selected.name || selected.code}
                <span className="rounded border border-border bg-muted px-1.5 py-0.5 font-mono text-xs text-muted-foreground">
                  {selected.code}
                </span>
                {!selected.enabled ? (
                  <span className="rounded bg-destructive/10 px-1.5 py-0.5 text-xs font-medium text-destructive">
                    已停用
                  </span>
                ) : null}
              </h2>
              <p className="mt-0.5 text-xs text-muted-foreground">
                短链：{selected.url} · 目标路径：{selected.target_path || "/"}
                {selected.remark ? ` · 备注：${selected.remark}` : ""}
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={() => copyLink(selected)}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
          >
            <Copy className="h-4 w-4" />
            复制短链
          </button>
        </div>

        <div className="flex items-center gap-1 border-b border-border">
          {[
            { key: "overview" as const, label: "渠道流量分析" },
            { key: "visits" as const, label: "渠道访问明细" },
          ].map((tItem) => (
            <button
              key={tItem.key}
              type="button"
              onClick={() => setDTab(tItem.key)}
              className={cn(
                "-mb-px border-b-2 px-4 py-2.5 text-sm font-medium transition-colors",
                dTab === tItem.key
                  ? "border-primary text-primary"
                  : "border-transparent text-muted-foreground hover:text-foreground"
              )}
            >
              {tItem.label}
            </button>
          ))}
        </div>

        {dTab === "overview" ? (
          detailLoading && !analytics ? (
            <div className="flex items-center justify-center py-24">
              <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            </div>
          ) : !analytics ? (
            <ChartCard title="渠道流量分析">
              <Empty text="暂无渠道数据" />
            </ChartCard>
          ) : (
            <div className="flex flex-col gap-5">
              <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-8">
                {trafficStats.map((s) => (
                  <StatTile key={s.label} icon={s.icon} label={s.label} value={s.value} sub={s.sub} />
                ))}
              </div>

              <div className="grid grid-cols-1 gap-5 xl:grid-cols-3">
                <ChartCard
                  title="引流趋势"
                  sub={`${analytics.range.days} 天 · PV / UV`}
                  className="xl:col-span-2"
                  action={
                    <div className="flex items-center gap-3 text-xs text-muted-foreground">
                      <span className="flex items-center gap-1.5">
                        <span className="h-2.5 w-2.5 rounded-full" style={{ background: "#3A7AFE" }} />
                        PV
                      </span>
                      <span className="flex items-center gap-1.5">
                        <span className="h-2.5 w-2.5 rounded-full" style={{ background: "#22C55E" }} />
                        UV
                      </span>
                    </div>
                  }
                >
                  <TrendChart data={trendData} />
                </ChartCard>
                <ChartCard title="转化概览">
                  <div className="grid grid-cols-2 gap-3">
                    <MiniStat label="订单数" value={fmtNum(analytics.conversion.orders)} />
                    <MiniStat label="支付订单" value={fmtNum(analytics.conversion.paid_orders)} />
                    <MiniStat label="销售额" value={fmtNum(analytics.conversion.sales)} />
                    <MiniStat label="客单价" value={fmtNum(analytics.conversion.aov)} />
                    <MiniStat
                      label="转化率"
                      value={`${fmtNum(analytics.conversion.conversion_rate)}%`}
                      sub="订单 / 访客"
                    />
                    <MiniStat label="独立点击" value={fmtNum(analytics.traffic.unique_clicks)} />
                  </div>
                </ChartCard>
              </div>

              <div className="grid grid-cols-1 gap-5 xl:grid-cols-3">
                <ChartCard title="时段分布（0-23 时）" className="xl:col-span-2">
                  <HoursChart data={hourData} peakHour={peakHour} />
                </ChartCard>
                <ChartCard title="设备类型">
                  <DonutChart data={deviceData} />
                </ChartCard>
              </div>

              <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
                <ChartCard title="访问转化漏斗">
                  <FunnelBars stages={analytics.funnel} />
                </ChartCard>
                <ChartCard title="地域分布 Top 10">
                  <HBarChart data={regionData} gradId="channelHbarRegion" />
                </ChartCard>
              </div>

              <ChartCard title="热门页面 Top 10">
                {analytics.pages.length === 0 ? (
                  <Empty />
                ) : (
                  <div className="max-h-[330px] overflow-y-auto">
                    <table className="w-full text-sm">
                      <thead className="sticky top-0 z-10 bg-card text-xs text-muted-foreground">
                        <tr className="border-b border-border">
                          <th className="w-10 py-2 text-left font-medium">#</th>
                          <th className="py-2 text-left font-medium">页面路径</th>
                          <th className="w-16 py-2 text-right font-medium">PV</th>
                          <th className="w-16 py-2 text-right font-medium">UV</th>
                        </tr>
                      </thead>
                      <tbody>
                        {analytics.pages.slice(0, 10).map((p, idx) => (
                          <tr
                            key={`${p.path}-${idx}`}
                            className="border-b border-border/60 last:border-0 hover:bg-accent/40"
                          >
                            <td className="py-2 text-muted-foreground">{idx + 1}</td>
                            <td className="max-w-0 truncate py-2 text-foreground" title={p.path}>
                              {p.path}
                            </td>
                            <td className="py-2 text-right font-medium tabular-nums text-foreground">
                              {fmtNum(p.pv)}
                            </td>
                            <td className="py-2 text-right tabular-nums text-muted-foreground">{fmtNum(p.uv)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </ChartCard>
            </div>
          )
        ) : (
          <ChartCard title="渠道访问明细" sub={`共 ${fmtNum(visitsTotal)} 条`}>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[1000px] text-sm">
                <thead className="text-xs text-muted-foreground">
                  <tr className="border-b border-border">
                    <th className="px-2 py-3 text-left font-medium">访问时间</th>
                    <th className="px-2 py-3 text-left font-medium">IP</th>
                    <th className="px-2 py-3 text-left font-medium">归属地</th>
                    <th className="px-2 py-3 text-left font-medium">运营商</th>
                    <th className="px-2 py-3 text-left font-medium">设备 / 系统 / 浏览器</th>
                    <th className="px-2 py-3 text-left font-medium">来源</th>
                    <th className="px-2 py-3 text-left font-medium">访问路径</th>
                    <th className="px-2 py-3 text-left font-medium">访客标识</th>
                  </tr>
                </thead>
                <tbody>
                  {visitsLoading ? (
                    <tr>
                      <td colSpan={8} className="py-16 text-center">
                        <div className="mx-auto h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                      </td>
                    </tr>
                  ) : visits.length === 0 ? (
                    <tr>
                      <td colSpan={8} className="py-16 text-center text-sm text-muted-foreground">
                        暂无该渠道的访问明细
                      </td>
                    </tr>
                  ) : (
                    visits.map((v) => (
                      <tr key={v.id} className="border-b border-border/60 last:border-0 hover:bg-accent/40">
                        <td className="whitespace-nowrap px-2 py-3 text-xs text-muted-foreground">
                          {v.visit_time || "-"}
                        </td>
                        <td className="px-2 py-3 font-mono text-xs text-foreground">{v.ip}</td>
                        <td className="px-2 py-3 text-muted-foreground">
                          {[v.country, v.province, v.city]
                            .filter((x) => x && x !== "0" && x !== "中国")
                            .join(" ") || "未知"}
                        </td>
                        <td className="px-2 py-3 text-muted-foreground">{v.isp || "未知"}</td>
                        <td className="px-2 py-3">
                          <span className="mr-1.5 inline-flex items-center rounded border border-border bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                            {v.device_label}
                          </span>
                          <span className="text-xs text-muted-foreground">
                            {[v.os, v.browser].filter(Boolean).join(" · ") || "-"}
                          </span>
                        </td>
                        <td className="px-2 py-3">
                          <span className="text-foreground">{v.source_label}</span>
                          {v.referer ? (
                            <span className="block text-xs text-muted-foreground">{v.referer}</span>
                          ) : null}
                        </td>
                        <td className="max-w-[220px] truncate px-2 py-3 text-foreground" title={v.path}>
                          {v.path}
                        </td>
                        <td className="px-2 py-3 font-mono text-xs text-muted-foreground">
                          {v.visitor_id ? v.visitor_id.slice(0, 12) : "-"}
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>

            <div className="mt-4 flex items-center justify-between">
              <span className="text-sm text-muted-foreground">共 {fmtNum(visitsTotal)} 条记录</span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  disabled={visitsPage <= 1 || visitsLoading}
                  onClick={() => setVisitsPage((p) => Math.max(1, p - 1))}
                  className="inline-flex items-center gap-1 rounded-lg border border-border px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
                >
                  <ChevronLeft className="h-4 w-4" />
                  上一页
                </button>
                <span className="text-sm text-foreground">
                  {visitsPage} / {visitPages}
                </span>
                <button
                  type="button"
                  disabled={visitsPage >= visitPages || visitsLoading}
                  onClick={() => setVisitsPage((p) => Math.min(visitPages, p + 1))}
                  className="inline-flex items-center gap-1 rounded-lg border border-border px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
                >
                  下一页
                  <ChevronRight className="h-4 w-4" />
                </button>
              </div>
            </div>
          </ChartCard>
        )}
      </div>
    )
  }

  // ── 渠道列表视图 ──
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2 rounded-xl border border-border bg-card p-3 shadow-sm">
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value)
              setPage(1)
            }}
            placeholder="搜索名称 / 编码 / 渠道"
            className="w-64 rounded-lg border border-border bg-background py-2 pl-9 pr-3 text-sm text-foreground outline-none focus:border-primary"
          />
        </div>
        <button
          type="button"
          onClick={openCreate}
          className="ml-auto inline-flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90"
        >
          <Plus className="h-4 w-4" />
          新建渠道链接
        </button>
      </div>

      <div className="flex items-start gap-2.5 rounded-xl border border-border bg-muted/40 p-3 text-xs text-muted-foreground">
        <Link2 className="mt-0.5 h-4 w-4 shrink-0" />
        <span>
          渠道短链格式为 <span className="font-mono text-foreground">/c/&lt;编码&gt;</span>
          ，把它挂到官网按钮或任意外部站点即可。用户点击后会自动记录点击并写入来源标识，后续访问与下单都会精准归因到该渠道。
          也支持带参数的普通链接（<span className="font-mono">?ch=</span> 或 <span className="font-mono">?utm_source=</span>）。
        </span>
      </div>

      <ChartCard title="渠道链接" sub={`共 ${fmtNum(total)} 个`}>
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1040px] text-sm">
            <thead className="text-xs text-muted-foreground">
              <tr className="border-b border-border">
                <th className="px-2 py-3 text-left font-medium">渠道名称</th>
                <th className="px-2 py-3 text-left font-medium">渠道标识</th>
                <th className="px-2 py-3 text-left font-medium">短链</th>
                <th className="px-2 py-3 text-left font-medium">目标路径</th>
                <th className="px-2 py-3 text-right font-medium">点击 / 独立</th>
                <th className="px-2 py-3 text-right font-medium">付费订单</th>
                <th className="px-2 py-3 text-right font-medium">转化率</th>
                <th className="px-2 py-3 text-left font-medium">状态</th>
                <th className="px-2 py-3 text-left font-medium">备注</th>
                <th className="px-2 py-3 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={10} className="py-16 text-center">
                    <div className="mx-auto h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                  </td>
                </tr>
              ) : list.length === 0 ? (
                <tr>
                  <td colSpan={10} className="py-16 text-center text-sm text-muted-foreground">
                    暂无渠道链接，点击右上角「新建渠道链接」开始
                  </td>
                </tr>
              ) : (
                list.map((link) => (
                  <tr key={link.id} className="border-b border-border/60 last:border-0 hover:bg-accent/40">
                    <td className="px-2 py-3">
                      <button
                        type="button"
                        onClick={() => openDetail(link)}
                        className="text-left font-medium text-primary hover:underline"
                      >
                        {link.name || link.code}
                      </button>
                    </td>
                    <td className="px-2 py-3 text-muted-foreground">{link.channel || "-"}</td>
                    <td className="px-2 py-3">
                      <div className="flex items-center gap-1.5">
                        <span className="max-w-[200px] truncate font-mono text-xs text-foreground" title={link.url}>
                          {link.url}
                        </span>
                        <button
                          type="button"
                          onClick={() => copyLink(link)}
                          title="复制短链"
                          className="rounded p-1 text-muted-foreground transition-colors hover:text-foreground"
                        >
                          <Copy className="h-3.5 w-3.5" />
                        </button>
                      </div>
                    </td>
                    <td className="max-w-[160px] truncate px-2 py-3 text-muted-foreground" title={link.target_path}>
                      {link.target_path || "/"}
                    </td>
                    <td className="px-2 py-3 text-right tabular-nums">
                      <span className="font-medium text-foreground">{fmtNum(link.click_count)}</span>
                      <span className="text-muted-foreground"> / {fmtNum(link.unique_click_count)}</span>
                    </td>
                    <td className="px-2 py-3 text-right tabular-nums font-medium text-foreground">
                      {fmtNum(link.paid_orders)}
                    </td>
                    <td className="px-2 py-3 text-right tabular-nums text-muted-foreground">
                      {fmtNum(link.conversion_rate)}%
                    </td>
                    <td className="px-2 py-3">
                      <button
                        type="button"
                        onClick={() => toggleEnabled(link)}
                        className={cn(
                          "inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium transition-colors",
                          link.enabled
                            ? "bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20"
                            : "bg-muted text-muted-foreground hover:bg-accent"
                        )}
                      >
                        <Power className="h-3 w-3" />
                        {link.enabled ? "启用" : "停用"}
                      </button>
                    </td>
                    <td className="max-w-[160px] truncate px-2 py-3 text-muted-foreground" title={link.remark ?? ""}>
                      {link.remark || "-"}
                    </td>
                    <td className="px-2 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => openDetail(link)}
                          className="rounded px-2 py-1 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground"
                        >
                          分析
                        </button>
                        <button
                          type="button"
                          onClick={() => openEdit(link)}
                          title="编辑"
                          className="rounded p-1.5 text-muted-foreground transition-colors hover:text-foreground"
                        >
                          <Pencil className="h-4 w-4" />
                        </button>
                        <button
                          type="button"
                          onClick={() => removeChannel(link)}
                          disabled={deletingId === link.id}
                          title="删除"
                          className="rounded p-1.5 text-muted-foreground transition-colors hover:text-destructive disabled:opacity-50"
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {listPages > 1 ? (
          <div className="mt-4 flex items-center justify-between">
            <span className="text-sm text-muted-foreground">共 {fmtNum(total)} 个渠道</span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                disabled={page <= 1 || loading}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
                className="inline-flex items-center gap-1 rounded-lg border border-border px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
              >
                <ChevronLeft className="h-4 w-4" />
                上一页
              </button>
              <span className="text-sm text-foreground">
                {page} / {listPages}
              </span>
              <button
                type="button"
                disabled={page >= listPages || loading}
                onClick={() => setPage((p) => Math.min(listPages, p + 1))}
                className="inline-flex items-center gap-1 rounded-lg border border-border px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
              >
                下一页
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        ) : null}
      </ChartCard>

      {formOpen ? (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={() => setFormOpen(false)}
        >
          <div
            className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-xl border border-border bg-card p-6 shadow-xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-5 flex items-center justify-between">
              <h2 className="text-lg font-semibold text-foreground">{editing ? "编辑渠道链接" : "新建渠道链接"}</h2>
              <button
                type="button"
                onClick={() => setFormOpen(false)}
                className="rounded-md p-1 text-muted-foreground transition-colors hover:text-foreground"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            <div className="flex flex-col gap-4">
              <label className="flex flex-col gap-1.5">
                <span className="text-sm font-medium text-foreground">
                  渠道名称 <span className="text-destructive">*</span>
                </span>
                <input
                  value={form.name}
                  maxLength={128}
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="如：官网首页购买按钮"
                  className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
                />
              </label>

              <label className="flex flex-col gap-1.5">
                <span className="text-sm font-medium text-foreground">渠道标识</span>
                <input
                  value={form.channel}
                  maxLength={64}
                  onChange={(e) => setForm({ ...form, channel: e.target.value })}
                  placeholder="如：官网 / 公众号 / 抖音（用于分组统计）"
                  className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
                />
              </label>

              <label className="flex flex-col gap-1.5">
                <span className="text-sm font-medium text-foreground">
                  渠道编码 <span className="text-xs font-normal text-muted-foreground">（短链标识，留空自动生成）</span>
                </span>
                <input
                  value={form.code}
                  maxLength={32}
                  disabled={!!editing}
                  onChange={(e) => setForm({ ...form, code: e.target.value.toLowerCase() })}
                  placeholder="小写字母 / 数字 / - / _，长度 1-32"
                  className="rounded-lg border border-border bg-background px-3 py-2 font-mono text-sm text-foreground outline-none focus:border-primary disabled:cursor-not-allowed disabled:opacity-60"
                />
                {editing ? (
                  <span className="text-xs text-muted-foreground">编码创建后不可修改</span>
                ) : (
                  <span className="text-xs text-muted-foreground">
                    短链形如 {`/c/${form.code || "<编码>"}`}
                  </span>
                )}
              </label>

              <label className="flex flex-col gap-1.5">
                <span className="text-sm font-medium text-foreground">目标路径</span>
                <input
                  value={form.target_path}
                  maxLength={255}
                  onChange={(e) => setForm({ ...form, target_path: e.target.value })}
                  placeholder="默认跳转到首页 /，如 /products/xxx"
                  className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
                />
              </label>

              <label className="flex flex-col gap-1.5">
                <span className="text-sm font-medium text-foreground">备注</span>
                <textarea
                  rows={2}
                  maxLength={512}
                  value={form.remark}
                  onChange={(e) => setForm({ ...form, remark: e.target.value })}
                  placeholder="记录该链接的投放位置、用途等"
                  className="resize-none rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
                />
              </label>

              <Toggle
                checked={form.enabled}
                onChange={(v) => setForm({ ...form, enabled: v })}
                label="启用该渠道"
                desc="停用后短链不再记录点击，但仍保留历史数据"
              />

              <div className="flex items-center justify-end gap-2 border-t border-border pt-4">
                <button
                  type="button"
                  onClick={() => setFormOpen(false)}
                  className="rounded-lg border border-border px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground"
                >
                  取消
                </button>
                <button
                  type="button"
                  onClick={submitForm}
                  disabled={saving}
                  className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-opacity hover:opacity-90 disabled:opacity-50"
                >
                  {saving ? "保存中..." : editing ? "保存修改" : "创建"}
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}
    </div>
  )
}

export default function AdminVisitPage() {
  const [tab, setTab] = useState<"overview" | "detail" | "channels">("overview")

  // 渠道链接 Tab 的刷新信号：递增即触发列表/详情重新拉取
  const [channelsToken, setChannelsToken] = useState(0)

  // 日期筛选
  const [range, setRange] = useState("7d")
  const [startDate, setStartDate] = useState("")
  const [endDate, setEndDate] = useState("")
  const [showCustom, setShowCustom] = useState(false)

  const [analytics, setAnalytics] = useState<VisitAnalytics | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
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

  /** 手动刷新：拉取最新统计数据（明细页同时刷新列表），并保证动画至少可见一段时间 */
  const handleRefresh = useCallback(async () => {
    if (refreshing) return
    setRefreshing(true)
    const started = Date.now()
    try {
      const tasks: Promise<unknown>[] = [fetchAnalytics(false)]
      if (tab === "detail") tasks.push(fetchVisits())
      if (tab === "channels") setChannelsToken((t) => t + 1)
      await Promise.all(tasks)
    } finally {
      const wait = Math.max(0, 600 - (Date.now() - started))
      setTimeout(() => setRefreshing(false), wait)
    }
  }, [refreshing, tab, fetchAnalytics, fetchVisits])

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
      toast.success(
        `已清理 ${fmtNum(res.deleted_logs)} 条明细、${fmtNum(res.deleted_sessions)} 条会话（保留 ${res.retention_days} 天）`
      )
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

  const sourceData = analytics ? analytics.sources.map((s) => ({ name: s.name, value: s.pv })) : []
  const deviceData = analytics ? analytics.devices.map((d) => ({ name: d.name, value: d.pv })) : []
  const osData = analytics ? analytics.os.map((o) => ({ name: o.name, value: o.pv })) : []
  const browserData = analytics ? analytics.browsers.map((b) => ({ name: b.name, value: b.pv })) : []
  const regionData = analytics ? analytics.regions.slice(0, 10).map((r) => ({ name: r.name, value: r.pv })) : []
  const ispData = analytics ? analytics.isps.slice(0, 10).map((r) => ({ name: r.name, value: r.pv })) : []
  const refererData = analytics ? analytics.referers.slice(0, 10).map((r) => ({ name: r.name, value: r.pv })) : []
  const cityData = analytics
    ? analytics.cities.slice(0, 10).map((c) => ({ name: `${c.province} · ${c.name}`, value: c.pv }))
    : []

  const stats: { icon: ComponentType<{ className?: string }>; label: string; value: string; sub?: string }[] = [
    { icon: Eye, label: "今日访问 (PV)", value: fmtNum(summary?.today_pv), sub: `今日访客 ${fmtNum(summary?.today_uv)}` },
    { icon: Users, label: "今日访客 (UV)", value: fmtNum(summary?.today_uv), sub: `独立 IP ${fmtNum(summary?.ips)}` },
    { icon: TrendingUp, label: "区间访问 (PV)", value: fmtNum(summary?.pv), sub: `日均 ${fmtNum(summary?.avg_pv)}` },
    { icon: ContactRound, label: "区间访客 (UV)", value: fmtNum(summary?.uv), sub: `人均浏览 ${fmtNum(summary?.pv_per_visitor)} 次` },
    { icon: Globe2, label: "独立 IP", value: fmtNum(summary?.ips), sub: `独立访客 ${fmtNum(summary?.visitors)}` },
    { icon: Activity, label: "会话数", value: fmtNum(summary?.sessions), sub: `人均 ${fmtNum(summary?.avg_page_count)} 页` },
    { icon: MousePointerClick, label: "跳出率", value: `${fmtNum(summary?.bounce_rate)}%`, sub: `跳出 ${fmtNum(analytics?.sessions.bounces)}` },
    { icon: Clock, label: "日均访问", value: fmtNum(summary?.avg_pv), sub: `平均停留 ${fmtDuration(summary?.avg_duration_sec)}` },
  ]

  return (
    <div className="flex flex-col gap-5">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-foreground">访问数据</h1>
          <p className="text-sm text-muted-foreground">
            统计分析商城前台网站的真实访问流量（仅统计前台访客，不含后台与爬虫）
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-500/30 bg-emerald-500/10 px-3 py-1.5 text-xs font-medium text-emerald-500">
            <span className="relative flex h-2 w-2">
              <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-500 opacity-75" />
              <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
            </span>
            实时在线 {fmtNum(analytics?.realtime.online ?? 0)}
          </span>
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
      <div className="flex flex-col gap-3 rounded-xl border border-border bg-card p-3 shadow-sm">
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
              {startDate || endDate ? (
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
          <button
            type="button"
            onClick={handleRefresh}
            disabled={refreshing}
            title="刷新最新数据"
            className="inline-flex items-center gap-1.5 rounded-lg border border-border bg-card px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-60"
          >
            <RefreshCw className={cn("h-3.5 w-3.5", (refreshing || loading) && "animate-spin")} />
            {refreshing ? "刷新中…" : "刷新"}
          </button>
          {analytics ? (
            <span className="ml-auto text-xs text-muted-foreground">
              统计区间：{analytics.range.start} ~ {analytics.range.end}
            </span>
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
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
            <select
              value={device}
              onChange={(e) => setDevice(e.target.value)}
              className="rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary"
            >
              <option value="">全部设备</option>
              {(options?.devices ?? []).map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
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
          { key: "overview" as const, label: "流量分析" },
          { key: "detail" as const, label: "访问明细" },
          { key: "channels" as const, label: "渠道链接" },
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
          <ChartCard title="流量分析">
            <Empty text="暂无访问数据" />
          </ChartCard>
        ) : (
          <div className="flex flex-col gap-5">
            {/* 概览指标 */}
            <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-8">
              {stats.map((s) => (
                <StatTile key={s.label} icon={s.icon} label={s.label} value={s.value} sub={s.sub} />
              ))}
            </div>

            {/* 访问趋势 + 流量来源 */}
            <div className="grid grid-cols-1 gap-5 xl:grid-cols-3">
              <ChartCard
                title="访问趋势"
                sub={`${analytics.range.days} 天 · PV / UV`}
                className="xl:col-span-2"
                action={
                  <div className="flex items-center gap-3 text-xs text-muted-foreground">
                    <span className="flex items-center gap-1.5">
                      <span className="h-2.5 w-2.5 rounded-full" style={{ background: "#3A7AFE" }} />
                      PV
                    </span>
                    <span className="flex items-center gap-1.5">
                      <span className="h-2.5 w-2.5 rounded-full" style={{ background: "#22C55E" }} />
                      UV
                    </span>
                  </div>
                }
              >
                <TrendChart data={trendData} />
              </ChartCard>
              <ChartCard title="流量来源">
                <DonutChart data={sourceData} />
              </ChartCard>
            </div>

            {/* 时段分布 + 设备类型 */}
            <div className="grid grid-cols-1 gap-5 xl:grid-cols-3">
              <ChartCard
                title="时段分布（0-23 时）"
                sub={
                  summary && summary.peak_hour_pv > 0
                    ? `高峰 ${fmtHour(summary.peak_hour)} · ${fmtNum(summary.peak_hour_pv)} 次`
                    : undefined
                }
                className="xl:col-span-2"
              >
                <HoursChart data={hourData} peakHour={summary?.peak_hour ?? -1} />
              </ChartCard>
              <ChartCard title="设备类型">
                <DonutChart data={deviceData} />
              </ChartCard>
            </div>

            {/* 操作系统 + 浏览器 */}
            <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
              <ChartCard title="操作系统">
                <DonutChart data={osData} height={200} />
              </ChartCard>
              <ChartCard title="浏览器">
                <DonutChart data={browserData} height={200} />
              </ChartCard>
            </div>

            {/* 地域分布 + 网络运营商 */}
            <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
              <ChartCard title="地域分布 Top 10">
                <HBarChart data={regionData} gradId="visitHbarRegion" />
              </ChartCard>
              <ChartCard title="网络运营商 Top 10">
                <HBarChart data={ispData} gradId="visitHbarIsp" />
              </ChartCard>
            </div>

            {/* 热门页面 + 活跃 IP */}
            <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
              <ChartCard title="热门页面 Top 10">
                {analytics.pages.length === 0 ? (
                  <Empty />
                ) : (
                  <div className="max-h-[330px] overflow-y-auto">
                    <table className="w-full text-sm">
                      <thead className="sticky top-0 z-10 bg-card text-xs text-muted-foreground">
                        <tr className="border-b border-border">
                          <th className="w-10 py-2 text-left font-medium">#</th>
                          <th className="py-2 text-left font-medium">页面路径</th>
                          <th className="w-16 py-2 text-right font-medium">PV</th>
                          <th className="w-16 py-2 text-right font-medium">UV</th>
                        </tr>
                      </thead>
                      <tbody>
                        {analytics.pages.slice(0, 10).map((p, idx) => (
                          <tr key={`${p.path}-${idx}`} className="border-b border-border/60 last:border-0 hover:bg-accent/40">
                            <td className="py-2 text-muted-foreground">{idx + 1}</td>
                            <td className="max-w-0 truncate py-2 text-foreground" title={p.path}>
                              {p.path}
                            </td>
                            <td className="py-2 text-right font-medium tabular-nums text-foreground">{fmtNum(p.pv)}</td>
                            <td className="py-2 text-right tabular-nums text-muted-foreground">{fmtNum(p.uv)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </ChartCard>

              <ChartCard title="活跃 IP Top">
                {analytics.ips.length === 0 ? (
                  <Empty />
                ) : (
                  <div className="max-h-[330px] overflow-y-auto">
                    <table className="w-full text-sm">
                      <thead className="sticky top-0 z-10 bg-card text-xs text-muted-foreground">
                        <tr className="border-b border-border">
                          <th className="w-10 py-2 text-left font-medium">#</th>
                          <th className="py-2 text-left font-medium">IP</th>
                          <th className="py-2 text-left font-medium">归属地</th>
                          <th className="py-2 text-left font-medium">运营商</th>
                          <th className="w-14 py-2 text-right font-medium">次数</th>
                        </tr>
                      </thead>
                      <tbody>
                        {analytics.ips.slice(0, 10).map((item, idx) => (
                          <tr key={`${item.ip}-${idx}`} className="border-b border-border/60 last:border-0 hover:bg-accent/40">
                            <td className="py-2 text-muted-foreground">{idx + 1}</td>
                            <td className="py-2 font-mono text-xs text-foreground">{item.ip}</td>
                            <td className="py-2 text-muted-foreground">
                              {[item.province, item.city].filter(Boolean).join(" ") || "未知"}
                            </td>
                            <td className="py-2 text-muted-foreground">{item.isp || "未知"}</td>
                            <td className="py-2 text-right font-medium tabular-nums text-foreground">{fmtNum(item.pv)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </ChartCard>
            </div>

            {/* 扩展分析（本项目额外指标） */}
            <div className="mt-1 flex items-center gap-3">
              <h2 className="text-sm font-semibold text-foreground">扩展分析</h2>
              <div className="h-px flex-1 bg-border" />
            </div>

            <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
              <ChartCard title="访问转化漏斗">
                <FunnelBars stages={analytics.funnel} />
              </ChartCard>

              <ChartCard title="会话质量">
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                  <MiniStat label="会话总数" value={fmtNum(analytics.sessions.sessions)} />
                  <MiniStat label="跳出率" value={`${fmtNum(analytics.sessions.bounce_rate)}%`} sub={`跳出 ${fmtNum(analytics.sessions.bounces)}`} />
                  <MiniStat label="平均停留" value={fmtDuration(analytics.sessions.avg_duration_sec)} />
                  <MiniStat label="人均页数" value={fmtNum(analytics.sessions.avg_page_count)} />
                  <MiniStat label="新访客" value={fmtNum(summary?.new_uv)} />
                  <MiniStat label="回访访客" value={fmtNum(summary?.returning_uv)} />
                </div>
                <div className="mt-4 border-t border-border pt-3">
                  <h4 className="mb-2.5 text-xs font-medium text-muted-foreground">会话来源质量</h4>
                  {analytics.sessions.sources.length === 0 ? (
                    <Empty text="暂无会话数据" />
                  ) : (
                    <div className="flex flex-col gap-2">
                      {analytics.sessions.sources.map((s, idx) => (
                        <div key={s.code || idx} className="flex items-center justify-between text-sm">
                          <span className="flex items-center gap-2 text-foreground">
                            <span
                              className="h-2.5 w-2.5 rounded-full"
                              style={{ background: PALETTE[idx % PALETTE.length] }}
                            />
                            {s.name}
                          </span>
                          <span className="text-muted-foreground">
                            {fmtNum(s.sessions)} 会话 · 跳出 {fmtNum(s.bounce_rate)}%
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </ChartCard>
            </div>

            <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
              <ChartCard title="来源域名 Top 10">
                <HBarChart data={refererData} gradId="visitHbarReferer" maxLabel={12} yAxisWidth={120} />
              </ChartCard>
              <ChartCard title="城市分布 Top 10">
                <HBarChart data={cityData} gradId="visitHbarCity" maxLabel={11} yAxisWidth={128} />
              </ChartCard>
            </div>
          </div>
        )
      ) : tab === "detail" ? (
        /* Detail tab */
        <ChartCard title="访问明细" sub={`共 ${fmtNum(total)} 条`}>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-sm">
              <thead className="text-xs text-muted-foreground">
                <tr className="border-b border-border">
                  <th className="px-2 py-3 text-left font-medium">访问时间</th>
                  <th className="px-2 py-3 text-left font-medium">IP</th>
                  <th className="px-2 py-3 text-left font-medium">归属地</th>
                  <th className="px-2 py-3 text-left font-medium">运营商</th>
                  <th className="px-2 py-3 text-left font-medium">设备 / 系统 / 浏览器</th>
                  <th className="px-2 py-3 text-left font-medium">来源</th>
                  <th className="px-2 py-3 text-left font-medium">访问路径</th>
                  <th className="px-2 py-3 text-left font-medium">访客标识</th>
                </tr>
              </thead>
              <tbody>
                {detailLoading ? (
                  <tr>
                    <td colSpan={8} className="py-16 text-center">
                      <div className="mx-auto h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    </td>
                  </tr>
                ) : visits.length === 0 ? (
                  <tr>
                    <td colSpan={8} className="py-16 text-center text-sm text-muted-foreground">
                      暂无访问明细
                    </td>
                  </tr>
                ) : (
                  visits.map((v) => (
                    <tr key={v.id} className="border-b border-border/60 last:border-0 hover:bg-accent/40">
                      <td className="whitespace-nowrap px-2 py-3 text-xs text-muted-foreground">{v.visit_time || "-"}</td>
                      <td className="px-2 py-3 font-mono text-xs text-foreground">{v.ip}</td>
                      <td className="px-2 py-3 text-muted-foreground">
                        {[v.country, v.province, v.city].filter((x) => x && x !== "0" && x !== "中国").join(" ") || "未知"}
                      </td>
                      <td className="px-2 py-3 text-muted-foreground">{v.isp || "未知"}</td>
                      <td className="px-2 py-3">
                        <span className="mr-1.5 inline-flex items-center rounded border border-border bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
                          {v.device_label}
                        </span>
                        <span className="text-xs text-muted-foreground">
                          {[v.os, v.browser].filter(Boolean).join(" · ") || "-"}
                        </span>
                      </td>
                      <td className="px-2 py-3">
                        <span className="text-foreground">{v.source_label}</span>
                        {v.referer ? <span className="block text-xs text-muted-foreground">{v.referer}</span> : null}
                      </td>
                      <td className="max-w-[220px] truncate px-2 py-3 text-foreground" title={v.path}>
                        {v.path}
                      </td>
                      <td className="px-2 py-3 font-mono text-xs text-muted-foreground">
                        {v.visitor_id ? v.visitor_id.slice(0, 12) : "-"}
                      </td>
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
                <ChevronLeft className="h-4 w-4" />
                上一页
              </button>
              <span className="text-sm text-foreground">
                {page} / {totalPages}
              </span>
              <button
                type="button"
                disabled={page >= totalPages || detailLoading}
                onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                className="inline-flex items-center gap-1 rounded-lg border border-border px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
              >
                下一页
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        </ChartCard>
      ) : (
        <ChannelsPanel range={range} startDate={startDate} endDate={endDate} refreshToken={channelsToken} />
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

"use client"

import { useState, useEffect, useMemo, useCallback, type ReactNode } from "react"
import {
  Users2, Search, Check, X, Ban, Pencil, ChevronLeft, ChevronRight,
  Package, Coins, Wallet, Settings, Plus, Trash2, Save, TrendingUp,
  Clock, CheckCircle2, UserCheck, UserX, Percent, RefreshCw, ShoppingBag,
  HandCoins, Loader2, ScrollText, ShieldCheck, Layers, CalendarRange,
  ArrowUpRight, ArrowDownRight, Eye, User, AtSign, KeyRound, Link2,
  Crown, Users, AlertCircle,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { toast } from "sonner"
import { adminDistributionApi } from "@/services/api"

const ITEMS_PER_PAGE = 10

type Tab = "overview" | "distributors" | "products" | "commissions" | "withdrawals" | "rules"

type DistributorStatus = "PENDING" | "APPROVED" | "REJECTED" | "DISABLED"
type CommissionStatus = "PENDING" | "SETTLED" | "WITHDRAWING" | "WITHDRAWN" | "REJECTED" | "CANCELLED"
type WithdrawalStatus = "PENDING" | "APPROVED" | "REJECTED" | "PROCESSING" | "SUCCESS" | "FAILED"

interface OverviewCard {
  value: number
  today: number
  prev: number
  money: boolean
}

interface OverviewData {
  range: string
  from: string | null
  to: string | null
  cards: Record<string, OverviewCard>
  today_sales: number
  today_commission: number
  today_new_distributors: number
}

interface RecentDistributionOrder {
  order_id: string
  paid_at: string
  status: string
  amount: number
  product_title: string
  quantity: number
  distributor_name: string
  distributor_code: string | null
  customer: string
  commission: number
}

/** 快捷日期区间 */
type RangeKey = "all" | "today" | "yesterday" | "thisMonth" | "lastMonth" | "thisYear" | "custom"

const RANGE_OPTIONS: { key: RangeKey; label: string }[] = [
  { key: "today", label: "今日" },
  { key: "yesterday", label: "昨日" },
  { key: "thisMonth", label: "本月" },
  { key: "lastMonth", label: "上月" },
  { key: "thisYear", label: "今年" },
  { key: "all", label: "全部" },
  { key: "custom", label: "自定义" },
]

const fmtDateStr = (dt: Date) =>
  `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, "0")}-${String(dt.getDate()).padStart(2, "0")}`

/** 快捷区间 → [from, to] 日期字符串（列表接口按 from/to 筛选） */
function rangeToDates(range: RangeKey): { from?: string; to?: string } {
  const now = new Date()
  const y = now.getFullYear()
  const m = now.getMonth()
  const d = now.getDate()
  switch (range) {
    case "today": return { from: fmtDateStr(new Date(y, m, d)), to: fmtDateStr(new Date(y, m, d)) }
    case "yesterday": {
      const dt = new Date(y, m, d - 1)
      return { from: fmtDateStr(dt), to: fmtDateStr(dt) }
    }
    case "thisMonth": return { from: fmtDateStr(new Date(y, m, 1)), to: fmtDateStr(new Date(y, m, d)) }
    case "lastMonth": {
      const lm = new Date(y, m - 1, 1)
      const le = new Date(y, m, 0)
      return { from: fmtDateStr(lm), to: fmtDateStr(le) }
    }
    case "thisYear": return { from: fmtDateStr(new Date(y, 0, 1)), to: fmtDateStr(new Date(y, m, d)) }
    default: return {}
  }
}

const orderStatusMap: Record<string, { label: string; cls: string }> = {
  PAID: { label: "已支付", cls: "bg-blue-500/10 text-blue-600" },
  DELIVERED: { label: "已发货", cls: "bg-amber-500/10 text-amber-600" },
  COMPLETED: { label: "已完成", cls: "bg-emerald-500/10 text-emerald-600" },
}

interface Distributor {
  id: string
  user_id: string
  username: string
  email: string
  phone: string
  status: DistributorStatus
  custom_rate: number | null
  sub_rate: number | null
  default_rate: number
  total_commission: number
  available_balance: number
  frozen_balance: number
  withdrawn_amount: number
  subordinate_count: number
  customer_count: number
  /** 成交数据（佣金记录/订单口径） */
  total_sales: number
  paid_order_count: number
  pending_commission: number
  settled_commission: number
  applied_at: string
  approved_at: string | null
  reject_reason: string | null
}

interface DistributionProduct {
  product_id: string
  product_title: string
  cover_url: string | null
  base_price: number
  enabled: boolean
  default_rate: number
  custom_rate: number | null
  /** 是否存在 product_commission 配置记录（false = 从未添加，默认不分销） */
  commission_set: boolean
  excluded: boolean
  /** 推广数据（通过推广链接累计） */
  promotion_sales: number
  promotion_commission: number
  click_count: number
  paid_count: number
  promoter_count: number
  conversion_rate: number
}

interface CommissionRecord {
  id: string
  distributor_id: string
  distributor_name: string
  distributor_code: string | null
  order_id: string
  order_no: string
  product_id: string
  product_title: string
  order_amount: number
  commission_rate: number
  commission_amount: number
  status: CommissionStatus
  created_at: string
  settled_at: string | null
  /** 是否可结算（PENDING 且订单完成超结算延迟期） */
  settlable?: boolean
  /** 结算延迟天数（后台配置 settle_delay_days） */
  settle_delay_days?: number
}

interface Withdrawal {
  id: string
  distributor_id: string
  distributor_name: string
  amount: number
  actual_amount: number | null
  status: WithdrawalStatus
  account_info: string
  reason: string | null
  fail_reason: string | null
  out_bill_no: string | null
  transfer_bill_no: string | null
  created_at: string
  approved_at: string | null
  transferred_at: string | null
  completed_at: string | null
  /** 关联的佣金明细条数（订单级提现：每条明细对应一笔佣金记录） */
  item_count?: number
}

interface DistributionRules {
  enabled: boolean
  auto_approve: boolean
  default_rate: number       // 百分比（0-100），提交时 /100 转系数
  default_sub_rate: number   // 百分比（0-100），提交时 /100 转系数
  min_withdraw_amount: number
  settle_delay_days: number
  withdraw_fee_rate: number  // 百分比（0-100），提交时 /100 转系数
  binding_protection_days: number
  tier_enabled: boolean
  sub_distribution_enabled: boolean
}

interface Tier {
  id: string
  tier_order: number
  rate: number       // 百分比（0-100），保存时 /100 转系数
  enabled: boolean
}

const defaultRules: DistributionRules = {
  enabled: false,
  auto_approve: false,
  default_rate: 10,
  default_sub_rate: 30,
  min_withdraw_amount: 10,
  settle_delay_days: 7,
  withdraw_fee_rate: 0,
  binding_protection_days: 30,
  tier_enabled: false,
  sub_distribution_enabled: true,
}

const fmtMoney = (n: number | null | undefined) => `¥${(Number(n) || 0).toFixed(2)}`
const fmtDate = (s: string | null | undefined) => (s ? new Date(s).toLocaleString() : "—")

export default function AdminDistributionPage() {
  const [tab, setTab] = useState<Tab>("overview")
  const [rulesOpen, setRulesOpen] = useState(false)

  // 日期筛选（概览卡片 + 各列表联动），默认本月
  const [range, setRange] = useState<RangeKey>("thisMonth")
  const [customFrom, setCustomFrom] = useState("")
  const [customTo, setCustomTo] = useState("")
  const [dateVersion, setDateVersion] = useState(0)

  const listDates = range === "custom"
    ? { from: customFrom || undefined, to: customTo || undefined }
    : rangeToDates(range)

  const overviewParams = {
    range,
    from: listDates.from,
    to: listDates.to,
  }

  const applyDateFilter = () => {
    setDateVersion(v => v + 1)
    setRange(range) // 保持当前区间，触发列表重新加载
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-foreground">分销推广管理</h1>
          <p className="text-sm text-muted-foreground">管理分销员、商品佣金、提现申请与分销规则</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => setRulesOpen(true)}
            className="inline-flex h-10 items-center gap-2 rounded-lg border border-input bg-background px-4 text-sm font-medium text-foreground transition-colors hover:bg-accent"
          >
            <ScrollText className="h-4 w-4" />
            分销规则
          </button>
          <TabSwitch tab={tab} setTab={setTab} />
        </div>
      </div>

      {/* 快捷日期筛选栏（概览 + 提现管理联动；推广员/佣金记录为全量管理列表不受日期过滤，商品佣金/规则设置不参与） */}
      {tab === "overview" || tab === "withdrawals" ? (
        <DateFilterBar
          range={range}
          onRange={setRange}
          from={customFrom}
          onFrom={setCustomFrom}
          to={customTo}
          onTo={setCustomTo}
          onApply={applyDateFilter}
        />
      ) : null}

      {tab === "overview" && <OverviewTab params={overviewParams} dateVersion={dateVersion} />}
      {tab === "distributors" && <DistributorsTab dateVersion={dateVersion} />}
      {tab === "products" && <ProductsTab />}
      {tab === "commissions" && <CommissionsTab dateVersion={dateVersion} />}
      {tab === "withdrawals" && <WithdrawalsTab dateFrom={listDates.from} dateTo={listDates.to} dateVersion={dateVersion} />}
      {tab === "rules" && <RulesTab />}

      {/* 管理员版分销规则弹窗 */}
      <AdminRulesModal open={rulesOpen} onClose={() => setRulesOpen(false)} />
    </div>
  )
}

// ═══════════════════════ 快捷日期筛选栏 ═══════════════════════

function DateFilterBar({
  range, onRange, from, onFrom, to, onTo, onApply,
}: {
  range: RangeKey
  onRange: (r: RangeKey) => void
  from: string
  onFrom: (v: string) => void
  to: string
  onTo: (v: string) => void
  onApply: () => void
}) {
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-xl border border-border bg-card p-3 shadow-sm">
      <CalendarRange className="h-4 w-4 shrink-0 text-muted-foreground" />
      <div className="flex flex-wrap gap-1">
        {RANGE_OPTIONS.map(opt => (
          <button
            key={opt.key}
            type="button"
            onClick={() => onRange(opt.key)}
            className={cn(
              "rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
              range === opt.key
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:bg-accent hover:text-foreground"
            )}
          >
            {opt.label}
          </button>
        ))}
      </div>
      {range === "custom" && (
        <div className="flex flex-wrap items-center gap-2">
          <input
            type="date"
            value={from}
            onChange={(e) => onFrom(e.target.value)}
            className="h-9 rounded-lg border border-input bg-background px-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <span className="text-xs text-muted-foreground">至</span>
          <input
            type="date"
            value={to}
            onChange={(e) => onTo(e.target.value)}
            className="h-9 rounded-lg border border-input bg-background px-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
      )}
      <button
        type="button"
        onClick={onApply}
        className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-input px-3 text-sm font-medium text-foreground transition-colors hover:bg-accent"
      >
        <RefreshCw className="h-3.5 w-3.5" />
        应用筛选
      </button>
    </div>
  )
}

function TabSwitch({ tab, setTab }: { tab: Tab; setTab: (v: Tab) => void }) {
  const options: { v: Tab; label: string; icon: typeof Users2 }[] = [
    { v: "overview", label: "概览", icon: TrendingUp },
    { v: "distributors", label: "推广员", icon: Users2 },
    { v: "products", label: "商品佣金", icon: Package },
    { v: "commissions", label: "佣金记录", icon: Coins },
    { v: "withdrawals", label: "提现管理", icon: Wallet },
    { v: "rules", label: "规则设置", icon: Settings },
  ]
  return (
    <div className="flex flex-wrap rounded-lg bg-muted p-1">
      {options.map(opt => (
        <button
          key={opt.v}
          type="button"
          onClick={() => setTab(opt.v)}
          className={cn(
            "flex items-center gap-2 rounded-md px-4 py-2 text-sm font-medium transition-colors",
            tab === opt.v ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
          )}
        >
          <opt.icon className="h-4 w-4" />
          {opt.label}
        </button>
      ))}
    </div>
  )
}

// ═══════════════════════ 概览 TAB ═══════════════════════

const CARD_DEFS: { key: string; label: string; icon: typeof Users2; color: string }[] = [
  { key: "total_sales", label: "总销售额", icon: TrendingUp, color: "text-blue-600 bg-blue-500/10" },
  { key: "total_distributors", label: "分销员总数", icon: Users2, color: "text-blue-600 bg-blue-500/10" },
  { key: "pending_count", label: "待审核数", icon: Clock, color: "text-amber-600 bg-amber-500/10" },
  { key: "total_commission", label: "总佣金", icon: Coins, color: "text-emerald-600 bg-emerald-500/10" },
  { key: "pending_settlement", label: "待结算", icon: Clock, color: "text-purple-600 bg-purple-500/10" },
  { key: "available_balance", label: "可提现", icon: Wallet, color: "text-cyan-600 bg-cyan-500/10" },
  { key: "frozen_balance", label: "冻结中", icon: Ban, color: "text-slate-600 bg-slate-500/10" },
  { key: "withdrawn_amount", label: "已提现", icon: CheckCircle2, color: "text-green-600 bg-green-500/10" },
]

function OverviewTab({ params, dateVersion }: { params: { range: RangeKey; from?: string; to?: string }; dateVersion: number }) {
  const [data, setData] = useState<OverviewData | null>(null)
  const [orders, setOrders] = useState<RecentDistributionOrder[]>([])
  const [ordersLoading, setOrdersLoading] = useState(false)
  const [loading, setLoading] = useState(true)

  const fetch = useCallback(async () => {
    setLoading(true)
    try {
      const res = await adminDistributionApi.getOverview({
        range: params.range,
        from: params.from,
        to: params.to,
      })
      setData(res as OverviewData)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载失败")
      setData(null)
    } finally {
      setLoading(false)
    }
  }, [params.range, params.from, params.to])

  const fetchOrders = useCallback(async () => {
    setOrdersLoading(true)
    try {
      const res = await adminDistributionApi.getRecentOrders({
        from: params.from,
        to: params.to,
        limit: 10,
      })
      setOrders((res?.list || []) as RecentDistributionOrder[])
    } catch {
      setOrders([])
    } finally {
      setOrdersLoading(false)
    }
  }, [params.from, params.to])

  useEffect(() => { fetch() }, [fetch, dateVersion])
  useEffect(() => { fetchOrders() }, [fetchOrders, dateVersion])

  const rangeLabel = RANGE_OPTIONS.find(r => r.key === params.range)?.label || "全部"

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-foreground">数据概览</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">
            当前统计范围：{rangeLabel}
            {data?.from && data.to && `（${data.from} ~ ${data.to}）`}
          </p>
        </div>
        <button
          type="button"
          onClick={() => { fetch(); fetchOrders() }}
          className="inline-flex h-9 items-center gap-2 rounded-lg border border-input px-3 text-sm text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <RefreshCw className="h-4 w-4" />
          刷新
        </button>
      </div>

      {/* 汇总卡片 */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
        {CARD_DEFS.map(def => {
          const card = data?.cards?.[def.key]
          if (!card) return null
          return (
            <div key={def.key} className="rounded-lg border border-border bg-card p-5 shadow-sm">
              <div className="flex items-center justify-between">
                <span className="text-sm text-muted-foreground">{def.label}</span>
                <span className={cn("flex h-8 w-8 items-center justify-center rounded-md", def.color)}>
                  <def.icon className="h-4 w-4" />
                </span>
              </div>
              <p className="mt-3 text-2xl font-bold text-foreground">
                {card.money ? fmtMoney(card.value) : card.value}
              </p>
              <div className="mt-2 flex items-center gap-2 text-xs">
                <span className="text-muted-foreground">
                  今日 {card.money ? fmtMoney(card.today) : card.today}
                </span>
                <TrendBadge value={card.value} prev={card.prev} />
              </div>
            </div>
          )
        })}
      </div>

      {/* 近期分销订单明细 */}
      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <ShoppingBag className="h-4 w-4 text-primary" />
            近期分销订单明细
          </h3>
          <span className="text-xs text-muted-foreground">按当前日期范围，最多显示 10 条</span>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">订单</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">商品</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">分销人</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">客户</th>
                <th className="px-4 py-3 text-right font-medium text-muted-foreground">金额</th>
                <th className="px-4 py-3 text-right font-medium text-muted-foreground">佣金</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">状态</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">支付时间</th>
              </tr>
            </thead>
            <tbody>
              {ordersLoading ? (
                <tr><td colSpan={8} className="py-12"><div className="flex items-center justify-center"><div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" /></div></td></tr>
              ) : orders.length === 0 ? (
                <tr><td colSpan={8} className="py-8 text-center text-sm text-muted-foreground">当前范围暂无分销订单</td></tr>
              ) : (
                orders.map(o => {
                  const st = orderStatusMap[o.status] || { label: o.status, cls: "bg-muted text-muted-foreground" }
                  return (
                    <tr key={o.order_id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                      <td className="px-4 py-3 font-mono text-xs text-foreground">{o.order_id.slice(0, 8)}</td>
                      <td className="max-w-[220px] truncate px-4 py-3 text-xs text-muted-foreground" title={o.product_title}>
                        {o.product_title}
                        {o.quantity > 1 && <span className="ml-1 text-muted-foreground/60">×{o.quantity}</span>}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex flex-col">
                          <span className="font-medium text-foreground">{o.distributor_name}</span>
                          <span className="text-xs text-muted-foreground">{o.distributor_code}</span>
                        </div>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{o.customer}</td>
                      <td className="px-4 py-3 text-right font-medium text-foreground">{fmtMoney(o.amount)}</td>
                      <td className="px-4 py-3 text-right font-medium text-emerald-600">{fmtMoney(o.commission)}</td>
                      <td className="px-4 py-3">
                        <span className={cn("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium", st.cls)}>
                          {st.label}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{fmtDate(o.paid_at)}</td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

/** 环比徽标：与上一周期比较 */
function TrendBadge({ value, prev }: { value: number; prev: number }) {
  if (!prev) return <span className="text-xs text-muted-foreground">环比 —</span>
  const pct = ((value - prev) / prev) * 100
  const up = pct >= 0
  const abs = Math.abs(pct)
  return (
    <span
      title="与上一周期对比"
      className={cn(
        "inline-flex items-center gap-0.5 rounded-full px-1.5 py-0.5 text-[11px] font-medium",
        up ? "bg-emerald-500/10 text-emerald-600" : "bg-red-500/10 text-red-500"
      )}
    >
      {up ? <ArrowUpRight className="h-3 w-3" /> : <ArrowDownRight className="h-3 w-3" />}
      环比 {abs >= 100 ? abs.toFixed(0) : abs.toFixed(1)}%
    </span>
  )
}

// ═══════════════════════ 推广员 TAB ═══════════════════════

const distributorStatusMap: Record<DistributorStatus, { label: string; cls: string }> = {
  PENDING: { label: "待审核", cls: "bg-amber-500/10 text-amber-600" },
  APPROVED: { label: "已通过", cls: "bg-emerald-500/10 text-emerald-600" },
  REJECTED: { label: "已拒绝", cls: "bg-red-500/10 text-red-500" },
  DISABLED: { label: "已禁用", cls: "bg-muted text-muted-foreground" },
}

function DistributorsTab({ dateVersion }: { dateVersion: number }) {
  const [list, setList] = useState<Distributor[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [keyword, setKeyword] = useState("")
  const [statusFilter, setStatusFilter] = useState<DistributorStatus | "">("")
  const [currentPage, setCurrentPage] = useState(1)
  const [rateModal, setRateModal] = useState<Distributor | null>(null)
  const [detailModal, setDetailModal] = useState<Distributor | null>(null)

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const data = await adminDistributionApi.listDistributors({
        page: currentPage,
        page_size: ITEMS_PER_PAGE,
        keyword: keyword || undefined,
        status: statusFilter || undefined,
      })
      setList((data.list || []) as Distributor[])
      setTotal(data.pagination?.total ?? 0)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载失败")
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [currentPage, keyword, statusFilter])

  useEffect(() => {
    const timer = setTimeout(() => setCurrentPage(1), 300)
    return () => clearTimeout(timer)
  }, [keyword, statusFilter])

  useEffect(() => { fetchList() }, [fetchList, dateVersion])

  const totalPages = Math.max(1, Math.ceil(total / ITEMS_PER_PAGE))

  const handleStatus = async (d: Distributor, status: DistributorStatus, reason?: string) => {
    const actionLabel = distributorStatusMap[status].label
    if (status === "REJECTED" || status === "DISABLED") {
      const input = window.prompt(`请输入${actionLabel}原因`, reason || "")
      if (input === null) return
      reason = input
    } else {
      if (!window.confirm(`确认${actionLabel}该分销员？`)) return
    }
    try {
      await adminDistributionApi.updateDistributorStatus(d.id, status, reason)
      toast.success(`${actionLabel}成功`)
      fetchList()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  const effectiveRate = (d: Distributor) => d.custom_rate != null ? d.custom_rate : d.default_rate
  const effectiveSubRate = (d: Distributor) => d.sub_rate != null ? d.sub_rate : 0

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative max-w-sm">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              placeholder="搜索用户名 / 邮箱 / 分销员编码"
              className="h-10 w-full rounded-lg border border-input bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as DistributorStatus | "")}
            className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="">全部状态</option>
            <option value="PENDING">待审核</option>
            <option value="APPROVED">已通过</option>
            <option value="REJECTED">已拒绝</option>
            <option value="DISABLED">已禁用</option>
          </select>
        </div>
        <button
          type="button"
          onClick={fetchList}
          className="inline-flex h-9 items-center gap-2 rounded-lg border border-input px-3 text-sm text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <RefreshCw className="h-4 w-4" />
          刷新
        </button>
      </div>

      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">分销员</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">联系方式</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">状态</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">佣金比例</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">下级比例</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">成交额</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">付款订单</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">总佣金</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">可提现</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">团队</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">申请时间</th>
                <th className="px-4 py-3 text-right font-medium text-muted-foreground">操作</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={12} className="py-12"><div className="flex items-center justify-center"><div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" /></div></td></tr>
              ) : list.length === 0 ? (
                <tr><td colSpan={12} className="py-8 text-center text-sm text-muted-foreground">暂无分销员数据</td></tr>
              ) : (
                list.map((d) => {
                  const st = distributorStatusMap[d.status]
                  const isCustomRate = d.custom_rate != null
                  return (
                    <tr key={d.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <Users2 className="h-4 w-4 shrink-0 text-primary" />
                          <div className="flex flex-col">
                            <span className="font-medium text-foreground">{d.username || "—"}</span>
                            <span className="text-xs text-muted-foreground">ID: {d.user_id}</span>
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">
                        <div>{d.email || "—"}</div>
                        <div>{d.phone || "—"}</div>
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium", st.cls)}>
                          {st.label}
                        </span>
                        {d.reject_reason && (
                          <p className="mt-1 text-xs text-red-500" title={d.reject_reason}>原因：{d.reject_reason}</p>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-1.5">
                          <span className="font-medium text-foreground">{effectiveRate(d).toFixed(2)}%</span>
                          {isCustomRate && (
                            <span className="rounded bg-primary/10 px-1.5 py-0.5 text-xs text-primary">自定义</span>
                          )}
                        </div>
                      </td>
                      <td className="px-4 py-3 text-muted-foreground">{effectiveSubRate(d).toFixed(2)}%</td>
                      <td className="px-4 py-3 font-medium text-primary">{fmtMoney(d.total_sales)}</td>
                      <td className="px-4 py-3 text-muted-foreground">{d.paid_order_count ?? 0} 单</td>
                      <td className="px-4 py-3 font-medium text-foreground">{fmtMoney(d.total_commission)}</td>
                      <td className="px-4 py-3 text-emerald-600">{fmtMoney(d.available_balance)}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">
                        <div>下级 {d.subordinate_count || 0}</div>
                        <div>客户 {d.customer_count || 0}</div>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{fmtDate(d.applied_at)}</td>
                      <td className="px-4 py-3">
                        <div className="flex items-center justify-end gap-1">
                          <button type="button" onClick={() => setDetailModal(d)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground" title="查看详情">
                            <Eye className="h-4 w-4" />
                          </button>
                          {d.status === "PENDING" && (
                            <>
                              <button type="button" onClick={() => handleStatus(d, "APPROVED")} className="flex h-8 w-8 items-center justify-center rounded-md text-emerald-600 hover:bg-emerald-500/10" title="审核通过">
                                <UserCheck className="h-4 w-4" />
                              </button>
                              <button type="button" onClick={() => handleStatus(d, "REJECTED")} className="flex h-8 w-8 items-center justify-center rounded-md text-red-500 hover:bg-red-500/10" title="拒绝">
                                <UserX className="h-4 w-4" />
                              </button>
                            </>
                          )}
                          {d.status === "APPROVED" && (
                            <button type="button" onClick={() => handleStatus(d, "DISABLED")} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-amber-500/10 hover:text-amber-600" title="禁用">
                              <Ban className="h-4 w-4" />
                            </button>
                          )}
                          {d.status === "DISABLED" && (
                            <button type="button" onClick={() => handleStatus(d, "APPROVED")} className="flex h-8 w-8 items-center justify-center rounded-md text-emerald-600 hover:bg-emerald-500/10" title="解禁">
                              <Check className="h-4 w-4" />
                            </button>
                          )}
                          {(d.status === "APPROVED" || d.status === "DISABLED") && (
                            <button type="button" onClick={() => setRateModal(d)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground" title="编辑佣金比例">
                              <Pencil className="h-4 w-4" />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
        <div className="flex items-center justify-between border-t border-border px-4 py-3">
          <span className="text-sm text-muted-foreground">共 {total} 条，第 {currentPage}/{totalPages} 页</span>
          <Pager page={currentPage} totalPages={totalPages} onChange={setCurrentPage} />
        </div>
      </div>

      {rateModal && (
        <RateEditModal
          distributor={rateModal}
          onClose={() => setRateModal(null)}
          onSaved={() => { setRateModal(null); fetchList() }}
        />
      )}

      {detailModal && (
        <DistributorDetailModal
          distributor={detailModal}
          onClose={() => setDetailModal(null)}
        />
      )}
    </div>
  )
}

/** 推广员详情弹窗：基础信息 + 佣金/余额数据 + 团队数据 */
function DistributorDetailModal({ distributor, onClose }: {
  distributor: Distributor
  onClose: () => void
}) {
  const [detail, setDetail] = useState<Record<string, any> | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    adminDistributionApi.getDistributor(distributor.id)
      .then((d) => { if (!cancelled) setDetail(d) })
      .catch((err) => toast.error(err instanceof Error ? err.message : "加载详情失败"))
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [distributor.id])

  // 详情接口字段优先，缺失时回退到列表数据
  const v = (key: string) => detail?.[key] ?? (distributor as Record<string, any>)[key]
  const st = distributorStatusMap[(v("status") as DistributorStatus) ?? distributor.status]
  const rate = v("custom_rate") != null ? v("custom_rate") : v("default_rate")
  const subRate = v("sub_rate")

  const infoItems: { icon: ReactNode; label: string; value: ReactNode }[] = [
    { icon: <AtSign className="h-4 w-4" />, label: "邮箱", value: v("email") || "—" },
    { icon: <KeyRound className="h-4 w-4" />, label: "分销员编码", value: v("distributor_code") || "—" },
    { icon: <Link2 className="h-4 w-4" />, label: "邀请码", value: v("invite_code") || "—" },
    { icon: <Crown className="h-4 w-4" />, label: "上级分销员", value: v("parent_id") ? String(v("parent_id")).slice(0, 8) + "…" : "无" },
    { icon: <User className="h-4 w-4" />, label: "微信绑定", value: v("wechat_bound") ? "已绑定" : "未绑定" },
    { icon: <CalendarRange className="h-4 w-4" />, label: "申请时间", value: fmtDate(v("applied_at") || v("created_at")) },
    { icon: <Clock className="h-4 w-4" />, label: "审核时间", value: fmtDate(v("approved_at")) },
    { icon: <ShieldCheck className="h-4 w-4" />, label: "拒绝原因", value: v("reject_reason") || "—" },
  ]

  const statCards = [
    { label: "总佣金", value: fmtMoney(v("total_commission")), cls: "text-primary" },
    { label: "可提现", value: fmtMoney(v("available_balance")), cls: "text-emerald-600" },
    { label: "冻结中", value: fmtMoney(v("frozen_balance")), cls: "text-slate-600" },
    { label: "已提现", value: fmtMoney(v("withdrawn_amount")), cls: "text-amber-600" },
    { label: "待结算", value: fmtMoney(v("pending_commission")), cls: "text-blue-600" },
    { label: "已结算", value: fmtMoney(v("settled_commission")), cls: "text-violet-600" },
  ]

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-xl border border-border bg-card p-6 shadow-2xl">
        <div className="mb-5 flex items-start justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary/10 text-primary">
              <User className="h-5 w-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-lg font-bold text-foreground">{distributor.username || "推广员"}</h2>
                <span className={cn("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium", st.cls)}>
                  {st.label}
                </span>
              </div>
              <p className="mt-0.5 text-xs text-muted-foreground">用户 ID: {distributor.user_id}</p>
            </div>
          </div>
          <button type="button" onClick={onClose} className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground">
            <X className="h-4 w-4" />
          </button>
        </div>

        {loading && !detail ? (
          <div className="flex items-center justify-center py-12">
            <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          </div>
        ) : (
          <div className="flex flex-col gap-6">
            {/* 佣金/余额数据 */}
            <div>
              <h3 className="mb-3 text-sm font-semibold text-muted-foreground">佣金与余额</h3>
              <div className="grid grid-cols-3 gap-3 sm:grid-cols-6">
                {statCards.map((c) => (
                  <div key={c.label} className="rounded-lg border border-border bg-muted/30 p-3">
                    <p className="text-xs text-muted-foreground">{c.label}</p>
                    <p className={cn("mt-1 text-sm font-semibold", c.cls)}>{c.value}</p>
                  </div>
                ))}
              </div>
            </div>

            {/* 佣金比例 */}
            <div>
              <h3 className="mb-3 text-sm font-semibold text-muted-foreground">佣金比例</h3>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                <div className="rounded-lg border border-border p-3">
                  <p className="text-xs text-muted-foreground">佣金比例</p>
                  <p className="mt-1 text-sm font-semibold text-foreground">{Number(rate ?? 0).toFixed(2)}%</p>
                  {v("custom_rate") != null && (
                    <p className="mt-0.5 text-[11px] text-primary">自定义（默认 {Number(v("default_rate") ?? 0).toFixed(2)}%）</p>
                  )}
                </div>
                <div className="rounded-lg border border-border p-3">
                  <p className="text-xs text-muted-foreground">下级抽成比例</p>
                  <p className="mt-1 text-sm font-semibold text-foreground">{Number(subRate ?? 0).toFixed(2)}%</p>
                </div>
                <div className="rounded-lg border border-border p-3">
                  <p className="text-xs text-muted-foreground">团队数据</p>
                  <p className="mt-1 text-sm font-semibold text-foreground">
                    下级 {v("subordinate_count") ?? 0} · 客户 {v("customer_count") ?? 0}
                  </p>
                </div>
              </div>
            </div>

            {/* 基本信息 */}
            <div>
              <h3 className="mb-3 text-sm font-semibold text-muted-foreground">基本信息</h3>
              <div className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
                {infoItems.map((item) => (
                  <div key={item.label} className="flex items-center gap-2.5 text-sm">
                    <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-muted/50 text-muted-foreground">
                      {item.icon}
                    </span>
                    <span className="w-20 shrink-0 text-muted-foreground">{item.label}</span>
                    <span className="min-w-0 break-all text-foreground">{item.value}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        <div className="mt-6 flex justify-end">
          <button type="button" onClick={onClose} className="h-10 rounded-lg border border-input px-5 text-sm font-medium text-foreground hover:bg-accent">关闭</button>
        </div>
      </div>
    </div>
  )
}

function RateEditModal({ distributor, onClose, onSaved }: {
  distributor: Distributor
  onClose: () => void
  onSaved: () => void
}) {
  const [customRate, setCustomRate] = useState<string>(
    distributor.custom_rate != null ? String(distributor.custom_rate) : ""
  )
  const [subRate, setSubRate] = useState<string>(
    distributor.sub_rate != null ? String(distributor.sub_rate) : ""
  )
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    const cr = customRate === "" ? null : parseFloat(customRate)
    const sr = subRate === "" ? null : parseFloat(subRate)
    if (cr != null && (Number.isNaN(cr) || cr < 0 || cr > 100)) {
      toast.error("佣金比例需在 0-100 之间")
      return
    }
    if (sr != null && (Number.isNaN(sr) || sr < 0 || sr > 100)) {
      toast.error("下级佣金比例需在 0-100 之间")
      return
    }
    setSaving(true)
    try {
      await adminDistributionApi.updateDistributorRate(distributor.id, cr ?? undefined, sr ?? undefined)
      toast.success("佣金比例已更新")
      onSaved()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-bold text-foreground">编辑佣金比例</h2>
          <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="mb-4 rounded-lg bg-muted/40 p-3 text-sm">
          <p className="font-medium text-foreground">{distributor.username}</p>
          <p className="mt-1 text-xs text-muted-foreground">默认佣金比例：{distributor.default_rate.toFixed(2)}%</p>
        </div>
        <div className="flex flex-col gap-4">
          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">自定义佣金比例 (%)</label>
            <input
              type="number"
              min={0}
              max={100}
              step="0.01"
              value={customRate}
              onChange={(e) => setCustomRate(e.target.value)}
              placeholder={`默认 ${distributor.default_rate}`}
              className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
            <p className="mt-1 text-xs text-muted-foreground">留空则使用默认比例</p>
          </div>
          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">下级佣金比例 (%)</label>
            <input
              type="number"
              min={0}
              max={100}
              step="0.01"
              value={subRate}
              onChange={(e) => setSubRate(e.target.value)}
              placeholder="0"
              className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
            <p className="mt-1 text-xs text-muted-foreground">分销员下级产生的佣金分成比例，留空表示 0</p>
          </div>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button type="button" onClick={onClose} className="h-10 rounded-lg border border-input px-4 text-sm font-medium text-foreground hover:bg-accent">取消</button>
          <button
            type="button"
            disabled={saving}
            onClick={handleSave}
            className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
          >
            {saving ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" /> : <Save className="h-4 w-4" />}
            保存
          </button>
        </div>
      </div>
    </div>
  )
}

// ═══════════════════════ 商品佣金 TAB ═══════════════════════

interface StatItem {
  total: number
  today: number
  prev: number
}

function ProductsTab() {
  const [list, setList] = useState<DistributionProduct[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [keyword, setKeyword] = useState("")
  const [currentPage, setCurrentPage] = useState(1)
  const [editModal, setEditModal] = useState<DistributionProduct | null>(null)
  const [addModalOpen, setAddModalOpen] = useState(false)

  // 汇总统计（默认本月，支持快捷日期）
  const [statsRange, setStatsRange] = useState<RangeKey>("thisMonth")
  const [stats, setStats] = useState<{ clicks: StatItem; paid_count: StatItem; conversion_rate: StatItem; commission_amount: StatItem } | null>(null)
  const [statsLoading, setStatsLoading] = useState(true)

  // 推广员排行弹窗
  const [promoterProduct, setPromoterProduct] = useState<DistributionProduct | null>(null)

  const fetchStats = useCallback(async () => {
    setStatsLoading(true)
    try {
      const dates = rangeToDates(statsRange)
      const data = await adminDistributionApi.productStats({
        range: statsRange,
        from: dates.from,
        to: dates.to,
      })
      setStats(data)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "统计数据加载失败")
    } finally {
      setStatsLoading(false)
    }
  }, [statsRange])

  useEffect(() => { fetchStats() }, [fetchStats])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const data = await adminDistributionApi.listProducts({
        page: currentPage,
        page_size: ITEMS_PER_PAGE,
        keyword: keyword || undefined,
      })
      setList((data.list || []) as DistributionProduct[])
      setTotal(data.pagination?.total ?? 0)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载失败")
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [currentPage, keyword])

  useEffect(() => {
    const timer = setTimeout(() => setCurrentPage(1), 300)
    return () => clearTimeout(timer)
  }, [keyword])

  useEffect(() => { fetchList() }, [fetchList])

  const totalPages = Math.max(1, Math.ceil(total / ITEMS_PER_PAGE))

  const handleToggleExcluded = async (p: DistributionProduct) => {
    // 新语义：默认不分销。未添加过 / 已排除 → 纳入分销；已开启 → 排除
    const inDistribution = p.commission_set && !p.excluded
    try {
      await adminDistributionApi.updateProductCommission(p.product_id, p.custom_rate, inDistribution)
      toast.success(inDistribution ? "已排除分销" : "已纳入分销")
      fetchList()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  // 环比涨跌幅（%）
  const deltaPercent = (item: StatItem | undefined) => {
    if (!item) return 0
    const prev = Number(item.prev) || 0
    const total = Number(item.total) || 0
    if (prev <= 0) return total > 0 ? 100 : 0
    return ((total - prev) / prev) * 100
  }

  const statCards: { key: "clicks" | "paid_count" | "conversion_rate" | "commission_amount"; label: string; render: (item: StatItem | undefined) => string }[] = [
    { key: "clicks", label: "点击率", render: (i) => `${Number(i?.total || 0).toLocaleString()} 次` },
    { key: "paid_count", label: "下单数量", render: (i) => `${Number(i?.total || 0).toLocaleString()} 单` },
    { key: "conversion_rate", label: "转化率", render: (i) => `${Number(i?.total || 0).toFixed(2)}%` },
    { key: "commission_amount", label: "佣金金额", render: (i) => fmtMoney(Number(i?.total || 0)) },
  ]

  return (
    <div className="flex flex-col gap-4">
      {/* ① 汇总统计卡片（快捷日期筛选 + 今日 + 环比） */}
      <div className="rounded-xl border border-border bg-card p-4 shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <CalendarRange className="h-4 w-4 text-muted-foreground" />
            <span className="text-sm font-semibold text-foreground">推广汇总</span>
          </div>
          <div className="flex flex-wrap gap-1">
            {RANGE_OPTIONS.filter(o => o.key !== "custom").map(opt => (
              <button
                key={opt.key}
                type="button"
                onClick={() => setStatsRange(opt.key)}
                className={cn(
                  "rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
                  statsRange === opt.key
                    ? "bg-primary text-primary-foreground"
                    : "text-muted-foreground hover:bg-accent hover:text-foreground"
                )}
              >
                {opt.label}
              </button>
            ))}
          </div>
        </div>
        {statsLoading && !stats ? (
          <div className="flex items-center justify-center py-10">
            <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          </div>
        ) : (
          <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {statCards.map(card => {
              const item = stats?.[card.key]
              const delta = deltaPercent(item)
              const up = delta > 0
              const down = delta < 0
              return (
                <div key={card.key} className="rounded-lg border border-border bg-background p-4">
                  <p className="text-sm text-muted-foreground">{card.label}</p>
                  <p className="mt-1 text-2xl font-bold text-foreground">{card.render(item!) || "—"}</p>
                  <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                    <span>今日 <b className="text-foreground">{item ? Number(item.today || 0).toLocaleString() : 0}</b></span>
                    <span className={cn(
                      "inline-flex items-center gap-0.5 font-medium",
                      up ? "text-emerald-600" : down ? "text-red-500" : "text-muted-foreground"
                    )}>
                      {up ? <ArrowUpRight className="h-3.5 w-3.5" /> : down ? <ArrowDownRight className="h-3.5 w-3.5" /> : null}
                      环比 {Math.abs(delta).toFixed(1)}%
                    </span>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-foreground">商品佣金配置</h2>
          <p className="text-sm text-muted-foreground">为商品设置自定义佣金比例或排除分销</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative w-56">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              placeholder="搜索商品名称"
              className="h-9 w-full rounded-lg border border-input bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
          <button
            type="button"
            onClick={() => setAddModalOpen(true)}
            className="inline-flex h-9 items-center gap-2 rounded-lg bg-primary px-3 text-sm font-medium text-primary-foreground transition-all hover:brightness-110"
          >
            <Plus className="h-4 w-4" />
            添加分销商品
          </button>
          <button
            type="button"
            onClick={fetchList}
            className="inline-flex h-9 items-center gap-2 rounded-lg border border-input px-3 text-sm text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <RefreshCw className="h-4 w-4" />
            刷新
          </button>
        </div>
      </div>

      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="whitespace-nowrap px-4 py-3 text-left font-medium text-muted-foreground">商品</th>
                <th className="whitespace-nowrap px-4 py-3 text-left font-medium text-muted-foreground">售价</th>
                <th className="whitespace-nowrap px-4 py-3 text-left font-medium text-muted-foreground">默认比例</th>
                <th className="whitespace-nowrap px-4 py-3 text-left font-medium text-muted-foreground">自定义比例</th>
                <th className="whitespace-nowrap px-4 py-3 text-left font-medium text-muted-foreground">分销状态</th>
                <th className="whitespace-nowrap px-4 py-3 text-center font-medium text-primary">推广销售额</th>
                <th className="whitespace-nowrap px-4 py-3 text-center font-medium text-primary">总佣金</th>
                <th className="whitespace-nowrap px-4 py-3 text-center font-medium text-primary">点击</th>
                <th className="whitespace-nowrap px-4 py-3 text-center font-medium text-primary">付款</th>
                <th className="whitespace-nowrap px-4 py-3 text-center font-medium text-primary">转化率</th>
                <th className="whitespace-nowrap px-4 py-3 text-center font-medium text-primary">推广人数</th>
                <th className="whitespace-nowrap px-4 py-3 text-right font-medium text-muted-foreground">操作</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={12} className="py-12"><div className="flex items-center justify-center"><div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" /></div></td></tr>
              ) : list.length === 0 ? (
                <tr><td colSpan={12} className="py-8 text-center text-sm text-muted-foreground">暂无商品数据</td></tr>
              ) : (
                list.map((p) => {
                  const effective = p.custom_rate != null ? p.custom_rate : p.default_rate
                  const linkCell = (label: string, title: string) => (
                    <button
                      type="button"
                      onClick={() => setPromoterProduct(p)}
                      title={title}
                      className="font-semibold text-primary underline decoration-primary/30 underline-offset-2 transition-colors hover:text-primary/80"
                    >
                      {label}
                    </button>
                  )
                  return (
                    <tr key={p.product_id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-3">
                          {p.cover_url ? (
                            <img
                              src={p.cover_url}
                              alt={p.product_title}
                              className="h-10 w-10 shrink-0 rounded-md border border-border object-cover"
                              loading="lazy"
                              onError={(e) => { (e.currentTarget.style.display = "none") }}
                            />
                          ) : (
                            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-muted">
                              <Package className="h-5 w-5 text-muted-foreground" />
                            </div>
                          )}
                          <div className="flex flex-col">
                            <span className="max-w-[200px] truncate font-medium text-foreground">{p.product_title}</span>
                            <span className="text-xs text-muted-foreground">ID: {p.product_id}</span>
                          </div>
                        </div>
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{fmtMoney(p.base_price)}</td>
                      <td className="whitespace-nowrap px-4 py-3 text-muted-foreground">{Number(p.default_rate ?? 0).toFixed(2)}%</td>
                      <td className="whitespace-nowrap px-4 py-3">
                        {p.custom_rate != null ? (
                          <span className="inline-flex items-center gap-1.5 font-medium text-primary">
                            <Percent className="h-3 w-3" />
                            {p.custom_rate.toFixed(2)}%
                          </span>
                        ) : (
                          <span className="text-xs text-muted-foreground">— 使用默认</span>
                        )}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3">
                        {!p.commission_set ? (
                          <span className="inline-flex items-center rounded-full bg-muted px-2.5 py-0.5 text-xs font-medium text-muted-foreground">
                            <Package className="mr-1 h-3 w-3" />
                            未分销
                          </span>
                        ) : p.excluded ? (
                          <span className="inline-flex items-center rounded-full bg-red-500/10 px-2.5 py-0.5 text-xs font-medium text-red-500">
                            <Ban className="mr-1 h-3 w-3" />
                            已排除
                          </span>
                        ) : (
                          <span className="inline-flex items-center rounded-full bg-emerald-500/10 px-2.5 py-0.5 text-xs font-medium text-emerald-600">
                            <Check className="mr-1 h-3 w-3" />
                            可分销
                          </span>
                        )}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-center">{linkCell(fmtMoney(p.promotion_sales ?? 0), "查看该商品推广员销售额排行")}</td>
                      <td className="whitespace-nowrap px-4 py-3 text-center">{linkCell(fmtMoney(p.promotion_commission ?? 0), "查看该商品推广员佣金排行")}</td>
                      <td className="whitespace-nowrap px-4 py-3 text-center">{linkCell(String(p.click_count ?? 0), "查看该商品推广员点击排行")}</td>
                      <td className="whitespace-nowrap px-4 py-3 text-center">{linkCell(String(p.paid_count ?? 0), "查看该商品推广员付款排行")}</td>
                      <td className="whitespace-nowrap px-4 py-3 text-center">
                        {p.click_count
                          ? linkCell(`${Number(p.conversion_rate ?? 0).toFixed(2)}%`, "查看该商品推广员转化率排行")
                          : <span className="text-muted-foreground">—</span>}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-center">{linkCell(String(p.promoter_count ?? 0), "查看该商品推广员列表")}</td>
                      <td className="whitespace-nowrap px-4 py-3">
                        <div className="flex items-center justify-end gap-1">
                          <button type="button" onClick={() => setEditModal(p)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground" title="编辑佣金">
                            <Pencil className="h-4 w-4" />
                          </button>
                          <button
                            type="button"
                            onClick={() => handleToggleExcluded(p)}
                            className={cn(
                              "flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium",
                              p.commission_set && !p.excluded
                                ? "text-red-500 hover:bg-red-500/10"
                                : "text-emerald-600 hover:bg-emerald-500/10"
                            )}
                            title={p.commission_set && !p.excluded ? "排除分销" : "纳入分销"}
                          >
                            {p.commission_set && !p.excluded ? <Ban className="h-3.5 w-3.5" /> : <Check className="h-3.5 w-3.5" />}
                            {p.commission_set && !p.excluded ? "排除" : "纳入"}
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
        <div className="flex items-center justify-between border-t border-border px-4 py-3">
          <span className="text-sm text-muted-foreground">共 {total} 条，第 {currentPage}/{totalPages} 页</span>
          <Pager page={currentPage} totalPages={totalPages} onChange={setCurrentPage} />
        </div>
      </div>

      {editModal && (
        <ProductCommissionModal
          product={editModal}
          onClose={() => setEditModal(null)}
          onSaved={() => { setEditModal(null); fetchList() }}
        />
      )}

      {addModalOpen && (
        <AddDistributionProductModal
          onClose={() => setAddModalOpen(false)}
          onSaved={() => { setAddModalOpen(false); fetchList() }}
        />
      )}

      {/* 推广员排行弹窗 */}
      {promoterProduct && (
        <PromoterRankModal
          product={promoterProduct}
          onClose={() => setPromoterProduct(null)}
        />
      )}
    </div>
  )
}

// ═══════════════════════ 推广员排行弹窗 ═══════════════════════

function PromoterRankModal({ product, onClose }: {
  product: DistributionProduct
  onClose: () => void
}) {
  const [rows, setRows] = useState<any[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)

  const fetchRows = useCallback(async () => {
    setLoading(true)
    try {
      const data = await adminDistributionApi.productPromoters(product.product_id, { page, page_size: 10 })
      setRows((data.list || []) as any[])
      setTotal(data.pagination?.total ?? 0)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "推广员列表加载失败")
      setRows([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [product.product_id, page])

  useEffect(() => { fetchRows() }, [fetchRows])

  const totalPages = Math.max(1, Math.ceil(total / 10))

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative flex max-h-[85vh] w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-border bg-card shadow-2xl">
        <div className="flex items-center justify-between border-b border-border px-5 py-3">
          <div className="min-w-0">
            <h2 className="flex items-center gap-2 text-base font-bold text-foreground">
              <Users2 className="h-5 w-5 text-primary" />
              推广员排行
            </h2>
            <p className="mt-0.5 max-w-md truncate text-xs text-muted-foreground">
              {product.product_title}（{fmtMoney(product.base_price)}）· 按推广销售额降序
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-auto p-4">
          {loading ? (
            <div className="flex items-center justify-center py-16">
              <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            </div>
          ) : rows.length === 0 ? (
            <div className="flex flex-col items-center gap-2 py-16 text-sm text-muted-foreground">
              <Users2 className="h-8 w-8 opacity-40" />
              暂无推广员推广该商品
            </div>
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border bg-muted/30">
                  <th className="px-3 py-2.5 text-left font-medium text-muted-foreground">排名</th>
                  <th className="px-3 py-2.5 text-left font-medium text-muted-foreground">推广员</th>
                  <th className="px-3 py-2.5 text-right font-medium text-muted-foreground">销售额</th>
                  <th className="px-3 py-2.5 text-right font-medium text-muted-foreground">佣金</th>
                  <th className="px-3 py-2.5 text-right font-medium text-muted-foreground">点击</th>
                  <th className="px-3 py-2.5 text-right font-medium text-muted-foreground">付款</th>
                  <th className="px-3 py-2.5 text-right font-medium text-muted-foreground">转化率</th>
                  <th className="px-3 py-2.5 text-right font-medium text-muted-foreground">推广时间</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, idx) => (
                  <tr key={r.distributor_id || idx} className="border-b border-border/50 last:border-0 hover:bg-muted/20">
                    <td className="px-3 py-2.5">
                      <span className={cn(
                        "inline-flex h-6 w-6 items-center justify-center rounded-full text-xs font-bold",
                        idx === 0 ? "bg-amber-500/20 text-amber-600"
                          : idx === 1 ? "bg-slate-400/20 text-slate-500"
                            : idx === 2 ? "bg-orange-400/20 text-orange-500"
                              : "bg-muted text-muted-foreground"
                      )}>
                        {(page - 1) * 10 + idx + 1}
                      </span>
                    </td>
                    <td className="px-3 py-2.5">
                      <div className="flex flex-col">
                        <span className="font-medium text-foreground">{r.username || r.email || "匿名用户"}</span>
                        <span className="text-xs text-muted-foreground">{r.distributor_code || ""}{r.link_url ? " · 已生成专属链接" : ""}</span>
                      </div>
                    </td>
                    <td className="px-3 py-2.5 text-right font-semibold text-foreground">{fmtMoney(r.total_sales)}</td>
                    <td className="px-3 py-2.5 text-right text-emerald-600">{fmtMoney(r.total_commission)}</td>
                    <td className="px-3 py-2.5 text-right text-muted-foreground">{r.click_count ?? 0}</td>
                    <td className="px-3 py-2.5 text-right text-muted-foreground">{r.paid_count ?? 0}</td>
                    <td className="px-3 py-2.5 text-right text-muted-foreground">
                      {r.click_count
                        ? `${Number(r.conversion_rate ?? 0).toFixed(2)}%`
                        : "—"}
                    </td>
                    <td className="px-3 py-2.5 text-right text-xs text-muted-foreground">{fmtDate(r.created_at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div className="flex items-center justify-between border-t border-border px-5 py-3">
          <span className="text-sm text-muted-foreground">共 {total} 位推广员，每页 10 条</span>
          <Pager page={page} totalPages={totalPages} onChange={setPage} />
        </div>
      </div>
    </div>
  )
}

function ProductCommissionModal({ product, onClose, onSaved }: {
  product: DistributionProduct
  onClose: () => void
  onSaved: () => void
}) {
  const [customRate, setCustomRate] = useState<string>(
    product.custom_rate != null ? String(product.custom_rate) : ""
  )
  const [excluded, setExcluded] = useState<boolean>(product.excluded)
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    const cr = customRate === "" ? null : parseFloat(customRate)
    if (cr != null && (Number.isNaN(cr) || cr < 0 || cr > 100)) {
      toast.error("佣金比例需在 0-100 之间")
      return
    }
    setSaving(true)
    try {
      await adminDistributionApi.updateProductCommission(product.product_id, cr ?? undefined, excluded)
      toast.success("佣金配置已更新")
      onSaved()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-bold text-foreground">编辑商品佣金</h2>
          <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="mb-4 rounded-lg bg-muted/40 p-3 text-sm">
          <p className="font-medium text-foreground line-clamp-1">{product.product_title}</p>
          <p className="mt-1 text-xs text-muted-foreground">默认佣金比例：{Number(product.default_rate ?? 0).toFixed(2)}% · 售价 {fmtMoney(product.base_price)}</p>
        </div>
        <div className="flex flex-col gap-4">
          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">自定义佣金比例 (%)</label>
            <input
              type="number"
              min={0}
              max={100}
              step="0.01"
              value={customRate}
              onChange={(e) => setCustomRate(e.target.value)}
              placeholder={`默认 ${product.default_rate}`}
              className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
            <p className="mt-1 text-xs text-muted-foreground">留空则使用默认比例</p>
          </div>
          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">分销状态</label>
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                onClick={() => setExcluded(false)}
                className={cn(
                  "rounded-md border px-3 py-1.5 text-sm font-medium transition-colors",
                  !excluded ? "border-primary bg-primary/10 text-primary" : "border-border text-foreground hover:border-primary/30"
                )}
              >
                可分销
              </button>
              <button
                type="button"
                onClick={() => setExcluded(true)}
                className={cn(
                  "rounded-md border px-3 py-1.5 text-sm font-medium transition-colors",
                  excluded ? "border-red-500 bg-red-500/10 text-red-500" : "border-border text-foreground hover:border-red-500/30"
                )}
              >
                排除分销
              </button>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">排除后该商品不参与任何分销推广</p>
          </div>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button type="button" onClick={onClose} className="h-10 rounded-lg border border-input px-4 text-sm font-medium text-foreground hover:bg-accent">取消</button>
          <button
            type="button"
            disabled={saving}
            onClick={handleSave}
            className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
          >
            {saving ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" /> : <Save className="h-4 w-4" />}
            保存
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * 添加分销商品弹窗 — 搜索全站商品，选中后纳入分销
 */
function AddDistributionProductModal({ onClose, onSaved }: {
  onClose: () => void
  onSaved: () => void
}) {
  const [keyword, setKeyword] = useState("")
  const [list, setList] = useState<DistributionProduct[]>([])
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState<string | null>(null)

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const data = await adminDistributionApi.listProducts({
        page: 1,
        page_size: 20,
        keyword: keyword || undefined,
      })
      setList((data.list || []) as DistributionProduct[])
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载失败")
      setList([])
    } finally {
      setLoading(false)
    }
  }, [keyword])

  useEffect(() => {
    const timer = setTimeout(fetchList, 300)
    return () => clearTimeout(timer)
  }, [keyword, fetchList])

  const handleAdd = async (p: DistributionProduct) => {
    setSubmitting(p.product_id)
    try {
      // 默认不分销：只有未开启 / 已排除的商品才需要「添加」（创建/恢复为可分销记录）
      await adminDistributionApi.updateProductCommission(p.product_id, p.custom_rate, false)
      toast.success("已添加为分销商品")
      onSaved()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    } finally {
      setSubmitting(null)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative flex max-h-[82vh] w-full max-w-2xl flex-col overflow-hidden rounded-xl border border-border bg-card shadow-2xl">
        <div className="flex items-center justify-between border-b border-border px-5 py-4">
          <h2 className="text-lg font-bold text-foreground">添加分销商品</h2>
          <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground">
            <X className="h-5 w-5" />
          </button>
        </div>

        <div className="border-b border-border p-4">
          <div className="relative max-w-md">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              placeholder="搜索商品名称"
              className="h-10 w-full rounded-lg border border-input bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
        </div>

        <div className="flex-1 overflow-y-auto p-4">
          {loading ? (
            <div className="flex items-center justify-center py-16">
              <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            </div>
          ) : list.length === 0 ? (
            <div className="py-16 text-center text-sm text-muted-foreground">未找到商品</div>
          ) : (
            <div className="flex flex-col gap-2">
              {list.map(p => (
                <div key={p.product_id} className="flex items-center justify-between rounded-lg border border-border p-3 transition-colors hover:bg-muted/20">
                  <div className="flex min-w-0 items-center gap-3">
                    {p.cover_url ? (
                      <img
                        src={p.cover_url}
                        alt={p.product_title}
                        className="h-10 w-10 shrink-0 rounded-md border border-border object-cover"
                        loading="lazy"
                        onError={(e) => { (e.currentTarget.style.display = "none") }}
                      />
                    ) : (
                      <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-md bg-muted">
                        <Package className="h-5 w-5 text-muted-foreground" />
                      </div>
                    )}
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium text-foreground">{p.product_title}</p>
                      <p className="text-xs text-muted-foreground">
                        {fmtMoney(p.base_price)} · 默认比例 {Number(p.default_rate ?? 0).toFixed(2)}%
                        {p.commission_set && p.excluded && (
                          <span className="ml-1.5 rounded bg-red-500/10 px-1.5 py-0.5 text-[11px] font-medium text-red-500">已排除</span>
                        )}
                      </p>
                    </div>
                  </div>
                  <button
                    type="button"
                    disabled={submitting === p.product_id || (p.commission_set && !p.excluded)}
                    onClick={() => handleAdd(p)}
                    className={cn(
                      "ml-3 inline-flex h-9 shrink-0 items-center gap-1.5 rounded-lg px-3 text-sm font-medium transition-colors",
                      p.commission_set && !p.excluded
                        ? "bg-muted text-muted-foreground cursor-default"
                        : "bg-primary text-primary-foreground hover:brightness-110 disabled:opacity-50"
                    )}
                  >
                    {submitting === p.product_id ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
                    {p.commission_set && !p.excluded ? "已添加" : "添加"}
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

// ═══════════════════════ 佣金记录 TAB ═══════════════════════

const commissionStatusMap: Record<CommissionStatus, { label: string; cls: string }> = {
  PENDING: { label: "待结算", cls: "bg-amber-500/10 text-amber-600" },
  SETTLED: { label: "已结算", cls: "bg-emerald-500/10 text-emerald-600" },
  WITHDRAWING: { label: "申请中", cls: "bg-blue-500/10 text-blue-600" },
  WITHDRAWN: { label: "已提现", cls: "bg-cyan-500/10 text-cyan-600" },
  REJECTED: { label: "结算拒绝", cls: "bg-orange-500/10 text-orange-600" },
  CANCELLED: { label: "已取消", cls: "bg-red-500/10 text-red-500" },
}

/** 佣金状态徽标（含"可结算"：待结算中订单已完成且超过结算延迟期，可直接勾选提现） */
function getCommissionBadge(c: any): { label: string; cls: string; tip?: string } {
  if (c?.status === "PENDING" && c?.settlable) {
    const n = Number(c?.settle_delay_days ?? 0)
    return {
      label: "可结算",
      cls: "bg-purple-500/10 text-purple-600",
      tip: n > 0 ? `订单支付完成并确认收货后 ${n} 天可申请结算提现` : "订单完成超过结算延迟期后可申请结算提现",
    }
  }
  return commissionStatusMap[c?.status as CommissionStatus] || { label: c?.status || "—", cls: "bg-muted text-muted-foreground" }
}

function CommissionBadge({ c }: { c: any }) {
  const b = getCommissionBadge(c)
  return (
    <span
      className={cn("inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium", b.cls)}
      title={b.tip}
    >
      {b.label}
      {b.tip && <AlertCircle className="h-3 w-3 shrink-0" />}
    </span>
  )
}

function CommissionsTab({ dateVersion }: { dateVersion: number }) {
  const [list, setList] = useState<CommissionRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [distributorId, setDistributorId] = useState("")
  const [statusFilter, setStatusFilter] = useState<CommissionStatus | "">("")
  const [currentPage, setCurrentPage] = useState(1)
  const [stats, setStats] = useState<{ total_commission: number; pending_commission: number; settlable_commission: number; settled_commission: number; cancelled_commission: number } | null>(null)

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const data = await adminDistributionApi.listCommissions({
        page: currentPage,
        page_size: ITEMS_PER_PAGE,
        distributor_id: distributorId || undefined,
        status: statusFilter || undefined,
      })
      setList((data.list || []) as CommissionRecord[])
      setTotal(data.pagination?.total ?? 0)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载失败")
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [currentPage, distributorId, statusFilter])

  const fetchStats = useCallback(async () => {
    try {
      const res = await adminDistributionApi.commissionStats()
      setStats(res)
    } catch {
      setStats(null)
    }
  }, [])

  useEffect(() => {
    const timer = setTimeout(() => setCurrentPage(1), 300)
    return () => clearTimeout(timer)
  }, [distributorId, statusFilter])

  useEffect(() => { fetchList() }, [fetchList, dateVersion])
  useEffect(() => { fetchStats() }, [fetchStats])

  const totalPages = Math.max(1, Math.ceil(total / ITEMS_PER_PAGE))

  return (
    <div className="flex flex-col gap-4">
      {stats && (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-5">
          <div className="rounded-lg border border-border bg-card p-4 shadow-sm">
            <p className="text-xs text-muted-foreground">佣金总额</p>
            <p className="mt-1 text-xl font-bold text-foreground">{fmtMoney(stats.total_commission)}</p>
          </div>
          <div className="rounded-lg border border-border bg-card p-4 shadow-sm">
            <p className="text-xs text-purple-600">可结算</p>
            <p className="mt-1 text-xl font-bold text-purple-600">{fmtMoney(stats.settlable_commission)}</p>
          </div>
          <div className="rounded-lg border border-border bg-card p-4 shadow-sm">
            <p className="text-xs text-amber-600">待结算</p>
            <p className="mt-1 text-xl font-bold text-amber-600">{fmtMoney(stats.pending_commission)}</p>
          </div>
          <div className="rounded-lg border border-border bg-card p-4 shadow-sm">
            <p className="text-xs text-emerald-600">已结算</p>
            <p className="mt-1 text-xl font-bold text-emerald-600">{fmtMoney(stats.settled_commission)}</p>
          </div>
          <div className="rounded-lg border border-border bg-card p-4 shadow-sm">
            <p className="text-xs text-red-500">已取消</p>
            <p className="mt-1 text-xl font-bold text-red-500">{fmtMoney(stats.cancelled_commission)}</p>
          </div>
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative max-w-sm">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              placeholder="按分销员 ID 筛选"
              className="h-10 w-full rounded-lg border border-input bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              value={distributorId}
              onChange={(e) => setDistributorId(e.target.value)}
            />
          </div>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as CommissionStatus | "")}
            className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="">全部状态</option>
            <option value="PENDING">待结算</option>
            <option value="SETTLED">已结算</option>
            <option value="WITHDRAWING">申请中</option>
            <option value="WITHDRAWN">已提现</option>
            <option value="REJECTED">结算拒绝</option>
            <option value="CANCELLED">已取消</option>
          </select>
        </div>
        <button
          type="button"
          onClick={fetchList}
          className="inline-flex h-9 items-center gap-2 rounded-lg border border-input px-3 text-sm text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <RefreshCw className="h-4 w-4" />
          刷新
        </button>
      </div>

      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">分销员</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">订单编号</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">商品</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">佣金金额</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">比例</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">状态</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">创建时间</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">结算时间</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={8} className="py-12"><div className="flex items-center justify-center"><div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" /></div></td></tr>
              ) : list.length === 0 ? (
                <tr><td colSpan={8} className="py-8 text-center text-sm text-muted-foreground">暂无佣金记录</td></tr>
              ) : (
                list.map((c) => {
                  return (
                    <tr key={c.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                      <td className="px-4 py-3">
                        <div className="flex flex-col">
                          <span className="font-medium text-foreground">{c.distributor_name || c.distributor_code || "—"}</span>
                          <span className="text-xs text-muted-foreground">{c.distributor_code || c.distributor_id}</span>
                        </div>
                      </td>
                      <td className="px-4 py-3 font-mono text-xs text-foreground">{c.order_no || c.order_id}</td>
                      <td className="max-w-[200px] truncate px-4 py-3 text-xs text-muted-foreground" title={c.product_title}>
                        <ShoppingBag className="mr-1 inline h-3 w-3" />
                        {c.product_title || "—"}
                      </td>
                      <td className="px-4 py-3 font-medium text-emerald-600">{fmtMoney(c.commission_amount)}</td>
                      <td className="px-4 py-3 text-muted-foreground">{(Number(c.commission_rate) || 0).toFixed(2)}%</td>
                      <td className="px-4 py-3">
                        <CommissionBadge c={c} />
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{fmtDate(c.created_at)}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{fmtDate(c.settled_at)}</td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
        <div className="flex items-center justify-between border-t border-border px-4 py-3">
          <span className="text-sm text-muted-foreground">共 {total} 条，第 {currentPage}/{totalPages} 页</span>
          <Pager page={currentPage} totalPages={totalPages} onChange={setCurrentPage} />
        </div>
      </div>
    </div>
  )
}

// ═══════════════════════ 提现管理 TAB ═══════════════════════

const withdrawalStatusMap: Record<WithdrawalStatus, { label: string; cls: string }> = {
  PENDING: { label: "待审核", cls: "bg-amber-500/10 text-amber-600" },
  APPROVED: { label: "已通过", cls: "bg-blue-500/10 text-blue-600" },
  REJECTED: { label: "已拒绝", cls: "bg-red-500/10 text-red-500" },
  PROCESSING: { label: "转账中", cls: "bg-cyan-500/10 text-cyan-600" },
  SUCCESS: { label: "已结算", cls: "bg-emerald-500/10 text-emerald-600" },
  FAILED: { label: "已失败", cls: "bg-red-500/10 text-red-500" },
}

function WithdrawalsTab({ dateFrom, dateTo, dateVersion }: { dateFrom?: string; dateTo?: string; dateVersion: number }) {
  const [list, setList] = useState<Withdrawal[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [statusFilter, setStatusFilter] = useState<WithdrawalStatus | "">("")
  const [currentPage, setCurrentPage] = useState(1)
  const [rejectModal, setRejectModal] = useState<Withdrawal | null>(null)
  const [settleModal, setSettleModal] = useState<Withdrawal | null>(null)
  const [detailModal, setDetailModal] = useState<Withdrawal | null>(null)

  // 汇总卡片：总销售额/总佣金/待结算/已结算（随日期筛选动态变化）
  const [stats, setStats] = useState<{ total_sales: number; total_commission: number; pending_commission: number; settled_commission: number } | null>(null)
  const [statsLoading, setStatsLoading] = useState(true)

  const fetchStats = useCallback(async () => {
    setStatsLoading(true)
    try {
      const data = await adminDistributionApi.withdrawalStats({ from: dateFrom, to: dateTo })
      setStats(data)
    } catch {
      setStats(null)
    } finally {
      setStatsLoading(false)
    }
  }, [dateFrom, dateTo])

  useEffect(() => { fetchStats() }, [fetchStats, dateVersion])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const data = await adminDistributionApi.listWithdrawals({
        page: currentPage,
        page_size: ITEMS_PER_PAGE,
        status: statusFilter || undefined,
        from: dateFrom,
        to: dateTo,
      })
      setList((data.list || []) as Withdrawal[])
      setTotal(data.pagination?.total ?? 0)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载失败")
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [currentPage, statusFilter, dateFrom, dateTo])

  useEffect(() => {
    const timer = setTimeout(() => setCurrentPage(1), 300)
    return () => clearTimeout(timer)
  }, [statusFilter, dateFrom, dateTo])

  useEffect(() => { fetchList() }, [fetchList, dateVersion])

  const totalPages = Math.max(1, Math.ceil(total / ITEMS_PER_PAGE))

  const handleApprove = async (w: Withdrawal) => {
    if (!window.confirm(`确认通过该提现申请？金额 ${fmtMoney(w.amount)}`)) return
    try {
      await adminDistributionApi.approveWithdrawal(w.id)
      toast.success("已通过提现申请")
      fetchList()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value as WithdrawalStatus | "")}
          className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        >
          <option value="">全部状态</option>
          <option value="PENDING">待审核</option>
          <option value="APPROVED">已通过</option>
          <option value="REJECTED">已拒绝</option>
          <option value="PROCESSING">转账中</option>
          <option value="SUCCESS">已结算</option>
          <option value="FAILED">已失败</option>
        </select>
        <button
          type="button"
          onClick={fetchList}
          className="inline-flex h-9 items-center gap-2 rounded-lg border border-input px-3 text-sm text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          <RefreshCw className="h-4 w-4" />
          刷新
        </button>
      </div>

      {/* 汇总卡片：总销售额 / 总佣金 / 待结算 / 已结算（随日期筛选动态变化） */}
      {statsLoading && !stats ? (
        <div className="flex items-center justify-center rounded-xl border border-border bg-card py-8 shadow-sm">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {[
            { key: "total_sales", label: "总销售额", icon: <HandCoins className="h-4 w-4" />, cls: "text-primary" },
            { key: "total_commission", label: "总佣金", icon: <Coins className="h-4 w-4" />, cls: "text-emerald-600" },
            { key: "pending_commission", label: "待结算", icon: <Clock className="h-4 w-4" />, cls: "text-amber-600" },
            { key: "settled_commission", label: "已结算", icon: <Check className="h-4 w-4" />, cls: "text-blue-600" },
          ].map(({ key, label, icon, cls }) => (
            <div key={key} className="rounded-lg border border-border bg-card p-4 shadow-sm">
              <div className="flex items-center gap-2">
                <span className={cn("inline-flex h-7 w-7 items-center justify-center rounded-full bg-muted", cls)}>{icon}</span>
                <p className="text-sm text-muted-foreground">{label}</p>
              </div>
              <p className="mt-2 text-2xl font-bold text-foreground">{fmtMoney(Number(stats?.[key as keyof typeof stats] || 0))}</p>
              <p className="mt-1 text-xs text-muted-foreground">
                {dateFrom || dateTo
                  ? dateFrom && dateTo
                    ? `${dateFrom} ~ ${dateTo}`
                    : dateFrom
                      ? `${dateFrom} 起`
                      : `截至 ${dateTo}`
                  : "全部时间"}
              </p>
            </div>
          ))}
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">分销员</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">提现金额</th>
                <th className="px-4 py-3 text-center font-medium text-muted-foreground">订单数</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">收款账户</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">状态</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">申请时间</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">处理时间</th>
                <th className="px-4 py-3 text-right font-medium text-muted-foreground">操作</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={8} className="py-12"><div className="flex items-center justify-center"><div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" /></div></td></tr>
              ) : list.length === 0 ? (
                <tr><td colSpan={8} className="py-8 text-center text-sm text-muted-foreground">暂无提现申请</td></tr>
              ) : (
                list.map((w) => {
                  const st = withdrawalStatusMap[w.status] || { label: w.status, cls: "bg-muted text-muted-foreground" }
                  return (
                    <tr key={w.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                      <td className="px-4 py-3">
                        <div className="flex flex-col">
                          <span className="font-medium text-foreground">{w.distributor_name || "—"}</span>
                          <span className="text-xs text-muted-foreground">ID: {w.distributor_id}</span>
                        </div>
                      </td>
                      <td className="px-4 py-3 font-semibold text-foreground">{fmtMoney(w.amount)}</td>
                      <td className="px-4 py-3 text-center">
                        <button
                          type="button"
                          onClick={() => setDetailModal(w)}
                          className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
                          title="查看关联订单明细"
                        >
                          <ShoppingBag className="h-3.5 w-3.5" />
                          {w.item_count ?? 0}
                        </button>
                      </td>
                      <td className="max-w-[240px] truncate px-4 py-3 text-xs text-muted-foreground" title={w.account_info}>
                        {w.account_info || "—"}
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium", st.cls)}>
                          {st.label}
                        </span>
                        {w.reason && (
                          <p className="mt-1 text-xs text-red-500" title={w.reason}>原因：{w.reason}</p>
                        )}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{fmtDate(w.created_at)}</td>
                      <td className="px-4 py-3 text-xs text-muted-foreground">{fmtDate(w.completed_at || w.transferred_at || w.approved_at)}</td>
                      <td className="px-4 py-3">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            type="button"
                            onClick={() => setDetailModal(w)}
                            className="flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium text-muted-foreground hover:bg-accent hover:text-foreground"
                            title="查看关联订单明细"
                          >
                            <Eye className="h-3.5 w-3.5" />
                            明细
                          </button>
                          {w.status === "PENDING" && (
                            <>
                              <button
                                type="button"
                                onClick={() => handleApprove(w)}
                                className="flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium text-emerald-600 hover:bg-emerald-500/10"
                                title="审核通过"
                              >
                                <Check className="h-3.5 w-3.5" />
                                通过
                              </button>
                              <button
                                type="button"
                                onClick={() => setRejectModal(w)}
                                className="flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium text-red-500 hover:bg-red-500/10"
                                title="拒绝"
                              >
                                <X className="h-3.5 w-3.5" />
                                拒绝
                              </button>
                            </>
                          )}
                          {(w.status === "APPROVED" || w.status === "PROCESSING") && (
                            <button
                              type="button"
                              onClick={() => setSettleModal(w)}
                              className="flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium text-cyan-600 hover:bg-cyan-500/10"
                              title="手动结算（确认已线下支付）"
                            >
                              <HandCoins className="h-3.5 w-3.5" />
                              手动结算
                            </button>
                          )}
                          {(w.status === "REJECTED" || w.status === "SUCCESS" || w.status === "FAILED") && (
                            <span className="text-xs text-muted-foreground">已处理</span>
                          )}
                        </div>
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
        <div className="flex items-center justify-between border-t border-border px-4 py-3">
          <span className="text-sm text-muted-foreground">共 {total} 条，第 {currentPage}/{totalPages} 页</span>
          <Pager page={currentPage} totalPages={totalPages} onChange={setCurrentPage} />
        </div>
      </div>

      {rejectModal && (
        <RejectWithdrawalModal
          withdrawal={rejectModal}
          onClose={() => setRejectModal(null)}
          onSaved={() => { setRejectModal(null); fetchList() }}
        />
      )}

      {settleModal && (
        <SettleWithdrawalModal
          withdrawal={settleModal}
          onClose={() => setSettleModal(null)}
          onSaved={() => { setSettleModal(null); fetchList() }}
        />
      )}

      {detailModal && (
        <WithdrawalDetailModal
          withdrawal={detailModal}
          onClose={() => setDetailModal(null)}
        />
      )}
    </div>
  )
}

function SettleWithdrawalModal({ withdrawal, onClose, onSaved }: {
  withdrawal: Withdrawal
  onClose: () => void
  onSaved: () => void
}) {
  const defaultAmount = withdrawal.actual_amount ?? withdrawal.amount
  const [amount, setAmount] = useState(String(defaultAmount.toFixed(2)))
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    const num = parseFloat(amount)
    if (Number.isNaN(num) || num <= 0) {
      toast.error("请输入有效的结算金额")
      return
    }
    setSaving(true)
    try {
      await adminDistributionApi.settleWithdrawal(withdrawal.id, num)
      toast.success("手动结算成功")
      onSaved()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-bold text-foreground">手动结算提现</h2>
          <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="mb-4 rounded-lg bg-cyan-500/5 p-3 text-sm">
          <p className="font-medium text-foreground">{withdrawal.distributor_name}</p>
          <p className="mt-1 text-xs text-muted-foreground">提现金额：{fmtMoney(withdrawal.amount)}</p>
          {withdrawal.transfer_bill_no && (
            <p className="mt-1 text-xs text-cyan-600">微信转账单号：{withdrawal.transfer_bill_no}</p>
          )}
        </div>
        <div className="mb-4 rounded-lg bg-amber-500/5 p-3 text-xs text-amber-700 dark:text-amber-400">
          确认已通过线下方式（微信/支付宝/银行转账等）将佣金支付给分销员。结算后将从分销员冻结佣金余额扣减。
        </div>
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">实际结算金额</label>
          <input
            type="number"
            step="0.01"
            min="0"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            placeholder="请输入实际支付金额"
            className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
          <p className="mt-1.5 text-xs text-muted-foreground">如实际支付金额与提现金额不同（如扣手续费），请填写实际支付金额</p>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button type="button" onClick={onClose} className="h-10 rounded-lg border border-input px-4 text-sm font-medium text-foreground hover:bg-accent">取消</button>
          <button
            type="button"
            disabled={saving}
            onClick={handleSave}
            className="inline-flex h-10 items-center gap-2 rounded-lg bg-cyan-600 px-4 text-sm font-semibold text-white transition-all hover:brightness-110 disabled:opacity-50"
          >
            {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <HandCoins className="h-4 w-4" />}
            确认结算
          </button>
        </div>
      </div>
    </div>
  )
}

function RejectWithdrawalModal({ withdrawal, onClose, onSaved }: {
  withdrawal: Withdrawal
  onClose: () => void
  onSaved: () => void
}) {
  const [reason, setReason] = useState("")
  const [saving, setSaving] = useState(false)

  const handleSave = async () => {
    if (!reason.trim()) {
      toast.error("请填写拒绝原因")
      return
    }
    setSaving(true)
    try {
      await adminDistributionApi.rejectWithdrawal(withdrawal.id, reason.trim())
      toast.success("已拒绝提现申请")
      onSaved()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-bold text-foreground">拒绝提现申请</h2>
          <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground">
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="mb-4 rounded-lg bg-muted/40 p-3 text-sm">
          <p className="font-medium text-foreground">{withdrawal.distributor_name}</p>
          <p className="mt-1 text-xs text-muted-foreground">提现金额：{fmtMoney(withdrawal.amount)}</p>
        </div>
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">拒绝原因</label>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="请填写拒绝原因，将通知分销员"
            rows={4}
            className="w-full rounded-lg border border-input bg-background p-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button type="button" onClick={onClose} className="h-10 rounded-lg border border-input px-4 text-sm font-medium text-foreground hover:bg-accent">取消</button>
          <button
            type="button"
            disabled={saving}
            onClick={handleSave}
            className="inline-flex h-10 items-center gap-2 rounded-lg bg-red-500 px-4 text-sm font-semibold text-white transition-all hover:brightness-110 disabled:opacity-50"
          >
            {saving ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" /> : <X className="h-4 w-4" />}
            确认拒绝
          </button>
        </div>
      </div>
    </div>
  )
}

// ═══════════════════════ 提现单关联订单明细弹窗 ═══════════════════════

function WithdrawalDetailModal({ withdrawal, onClose }: {
  withdrawal: Withdrawal
  onClose: () => void
}) {
  const [items, setItems] = useState<any[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    adminDistributionApi.withdrawalItems(withdrawal.id)
      .then((data) => { if (!cancelled) setItems(data || []) })
      .catch((err) => {
        if (!cancelled) toast.error(err instanceof Error ? err.message : "加载明细失败")
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [withdrawal.id])

  const totalCommission = items.reduce((s, it) => s + (Number(it.commission_amount) || 0), 0)

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative flex max-h-[85vh] w-full max-w-2xl flex-col rounded-xl border border-border bg-card shadow-2xl">
        <div className="flex items-center justify-between border-b border-border px-5 py-4">
          <div>
            <h2 className="text-lg font-bold text-foreground">提现明细</h2>
            <p className="mt-0.5 text-xs text-muted-foreground">
              {withdrawal.distributor_name || "—"} · 提现金额 {fmtMoney(withdrawal.amount)}
              {withdrawal.actual_amount != null && Number(withdrawal.actual_amount) !== Number(withdrawal.amount)
                ? ` · 实到 ${fmtMoney(withdrawal.actual_amount)}` : ""}
            </p>
          </div>
          <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground">
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading ? (
            <div className="flex items-center justify-center py-12">
              <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            </div>
          ) : items.length === 0 ? (
            <div className="flex flex-col items-center gap-2 py-12 text-sm text-muted-foreground">
              <ShoppingBag className="h-8 w-8 opacity-40" />
              暂无关联订单
            </div>
          ) : (
            <div className="divide-y divide-border rounded-lg border border-border">
              {items.map((it) => {
                const st = commissionStatusMap[it.withdrawal_status as CommissionStatus] || { label: it.withdrawal_status || it.status || "—", cls: "bg-muted text-muted-foreground" }
                return (
                  <div key={it.id} className="flex items-center justify-between gap-3 p-3">
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <ShoppingBag className="h-4 w-4 shrink-0 text-muted-foreground" />
                        <p className="truncate text-sm font-medium text-foreground">{it.product_title || "—"}</p>
                        <span className={cn("inline-flex shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium", st.cls)}>
                          {st.label}
                        </span>
                      </div>
                      <p className="mt-1 font-mono text-xs text-muted-foreground">
                        订单 {it.order_no || String(it.order_id || "").slice(0, 8)}
                        {it.settled_at ? ` · 结算于 ${fmtDate(it.settled_at)}` : ""}
                      </p>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        商品金额 {fmtMoney(it.order_amount)} · 佣金比例 {Number(it.commission_rate ?? 0).toFixed(2)}%
                      </p>
                    </div>
                    <div className="shrink-0 text-right">
                      <p className="text-sm font-semibold text-emerald-600">{fmtMoney(it.commission_amount)}</p>
                      <p className="mt-0.5 text-[11px] text-muted-foreground">{fmtDate(it.created_at)}</p>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        <div className="flex items-center justify-between border-t border-border px-5 py-4">
          <span className="text-sm text-muted-foreground">共 {items.length} 笔订单明细</span>
          <span className="text-sm font-semibold text-foreground">佣金合计 {fmtMoney(totalCommission)}</span>
        </div>
      </div>
    </div>
  )
}

// ═══════════════════════ 规则设置 TAB ═══════════════════════

function RulesTab() {
  const [rules, setRules] = useState<DistributionRules>(defaultRules)
  const [tiers, setTiers] = useState<Tier[]>([])
  const [loading, setLoading] = useState(true)
  const [savingRules, setSavingRules] = useState(false)
  const [savingTiers, setSavingTiers] = useState(false)

  useEffect(() => {
    (async () => {
      setLoading(true)
      try {
        const [rulesRes, tiersRes] = await Promise.all([
          adminDistributionApi.getRules(),
          adminDistributionApi.getTiers(),
        ])
        if (rulesRes && typeof rulesRes === "object") {
          const r = rulesRes as any
          setRules({
            enabled: !!r.enabled,
            auto_approve: !!r.auto_approve,
            default_rate: Math.round(Number(r.default_rate ?? 0) * 10000) / 100,
            default_sub_rate: Math.round(Number(r.default_sub_rate ?? 0) * 10000) / 100,
            min_withdraw_amount: Number(r.min_withdraw_amount ?? 10),
            settle_delay_days: Number(r.settle_delay_days ?? 7),
            withdraw_fee_rate: Math.round(Number(r.withdraw_fee_rate ?? 0) * 10000) / 100,
            binding_protection_days: Number(r.binding_protection_days ?? 30),
            tier_enabled: !!r.tier_enabled,
            sub_distribution_enabled: r.sub_distribution_enabled !== false,
          })
        }
        if (Array.isArray(tiersRes)) {
          // 后端系数(0-1) → 前端百分比(0-100)
          setTiers((tiersRes as any[]).map((t, i) => ({
            id: String(t.id ?? i),
            tier_order: Number(t.tier_order ?? i + 1),
            rate: Math.round(Number(t.rate ?? 0) * 10000) / 100,
            enabled: t.enabled !== false,
          })))
        }
      } catch (err) {
        toast.error(err instanceof Error ? err.message : "加载失败")
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  const updRule = <K extends keyof DistributionRules>(k: K, v: DistributionRules[K]) => {
    setRules(prev => ({ ...prev, [k]: v }))
  }

  const handleSaveRules = async () => {
    if (rules.default_rate < 0 || rules.default_rate > 100) {
      toast.error("默认佣金比例需在 0-100 之间")
      return
    }
    if (rules.default_sub_rate < 0 || rules.default_sub_rate > 100) {
      toast.error("下级抽成比例需在 0-100 之间")
      return
    }
    if (rules.min_withdraw_amount < 0) {
      toast.error("最低提现金额不能为负数")
      return
    }
    if (rules.settle_delay_days < 0) {
      toast.error("佣金结算延迟天数不能为负数")
      return
    }
    setSavingRules(true)
    try {
      // 百分比 → 系数（与后端 ruleToMap/updateRules 一致）
      await adminDistributionApi.updateRules({
        enabled: rules.enabled,
        auto_approve: rules.auto_approve,
        default_rate: rules.default_rate / 100,
        default_sub_rate: rules.default_sub_rate / 100,
        min_withdraw_amount: rules.min_withdraw_amount,
        settle_delay_days: rules.settle_delay_days,
        withdraw_fee_rate: rules.withdraw_fee_rate / 100,
        binding_protection_days: rules.binding_protection_days,
        tier_enabled: rules.tier_enabled,
        sub_distribution_enabled: rules.sub_distribution_enabled,
      })
      toast.success("分销规则已保存")
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSavingRules(false)
    }
  }

  const handleSaveTiers = async () => {
    const orders = new Set<number>()
    for (const t of tiers) {
      if (t.rate < 0 || t.rate > 100) {
        toast.error(`第 ${t.tier_order} 次购买的佣金比例需在 0-100 之间`)
        return
      }
      if (orders.has(t.tier_order)) {
        toast.error(`购买次序 ${t.tier_order} 重复，请检查`)
        return
      }
      orders.add(t.tier_order)
    }
    setSavingTiers(true)
    try {
      // 百分比 → 系数（与后端 CommissionTier.rate 语义一致：1.0=100%）
      const payload = tiers
        .slice()
        .sort((a, b) => a.tier_order - b.tier_order)
        .map(t => ({
          tier_order: t.tier_order,
          rate: t.rate / 100,
          enabled: true,
        }))
      await adminDistributionApi.updateTiers(payload)
      toast.success("阶梯佣金配置已保存")
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSavingTiers(false)
    }
  }

  const addTier = () => {
    const nextOrder = tiers.length ? Math.max(...tiers.map(t => t.tier_order)) + 1 : 1
    setTiers(prev => [
      ...prev,
      {
        id: `new_${Date.now()}`,
        tier_order: nextOrder,
        rate: 100,
        enabled: true,
      },
    ])
  }

  const updateTier = (id: string, field: keyof Tier, value: string | number) => {
    setTiers(prev => prev.map(t => (t.id === id ? { ...t, [field]: value } : t)))
  }

  const removeTier = (id: string) => {
    setTiers(prev => prev.filter(t => t.id !== id))
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      {/* 基础规则 */}
      <div className="rounded-xl border border-border bg-card shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-6 py-4">
          <div>
            <h2 className="flex items-center gap-2 text-base font-semibold text-foreground">
              <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary/10 text-primary">
                <Settings className="h-4 w-4" />
              </span>
              基础规则
            </h2>
            <p className="mt-1 pl-10 text-xs text-muted-foreground">分销功能总开关、佣金比例与结算提现相关规则</p>
          </div>
          <button
            type="button"
            disabled={savingRules}
            onClick={handleSaveRules}
            className="inline-flex h-9 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
          >
            {savingRules ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" /> : <Save className="h-4 w-4" />}
            保存规则
          </button>
        </div>

        {/* 1. 功能开关（4 个一行） */}
        <div className="border-b border-border px-6 py-5">
          <div className="mb-4 flex items-center gap-2">
            <ShieldCheck className="h-4 w-4 text-primary" />
            <h3 className="text-sm font-semibold text-foreground">功能开关</h3>
          </div>
          <div className="grid grid-cols-2 gap-4 xl:grid-cols-4">
            <div className="flex items-center justify-between rounded-lg border border-border bg-muted/20 p-4 transition-colors hover:bg-muted/30">
              <div>
                <p className="text-sm font-medium text-foreground">启用分销</p>
                <p className="mt-1 text-xs text-muted-foreground">关闭后所有分销链接将失效</p>
              </div>
              <button
                type="button"
                onClick={() => updRule("enabled", !rules.enabled)}
                className={cn(
                  "relative h-6 w-11 shrink-0 rounded-full transition-colors",
                  rules.enabled ? "bg-primary" : "bg-muted-foreground/30"
                )}
              >
                <span className={cn(
                  "absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  rules.enabled ? "translate-x-5" : "translate-x-0.5"
                )} />
              </button>
            </div>

            <div className="flex items-center justify-between rounded-lg border border-border bg-muted/20 p-4 transition-colors hover:bg-muted/30">
              <div>
                <p className="text-sm font-medium text-foreground">自动审核</p>
                <p className="mt-1 text-xs text-muted-foreground">开启后新申请自动通过</p>
              </div>
              <button
                type="button"
                onClick={() => updRule("auto_approve", !rules.auto_approve)}
                className={cn(
                  "relative h-6 w-11 shrink-0 rounded-full transition-colors",
                  rules.auto_approve ? "bg-primary" : "bg-muted-foreground/30"
                )}
              >
                <span className={cn(
                  "absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  rules.auto_approve ? "translate-x-5" : "translate-x-0.5"
                )} />
              </button>
            </div>

            <div className="flex items-center justify-between rounded-lg border border-border bg-muted/20 p-4 transition-colors hover:bg-muted/30">
              <div>
                <p className="text-sm font-medium text-foreground">开启二级分销</p>
                <p className="mt-1 text-xs text-muted-foreground">一级分销员可从下级佣金中抽成</p>
              </div>
              <button
                type="button"
                onClick={() => updRule("sub_distribution_enabled", !rules.sub_distribution_enabled)}
                className={cn(
                  "relative h-6 w-11 shrink-0 rounded-full transition-colors",
                  rules.sub_distribution_enabled ? "bg-primary" : "bg-muted-foreground/30"
                )}
              >
                <span className={cn(
                  "absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  rules.sub_distribution_enabled ? "translate-x-5" : "translate-x-0.5"
                )} />
              </button>
            </div>

            <div className="flex items-center justify-between rounded-lg border border-border bg-muted/20 p-4 transition-colors hover:bg-muted/30">
              <div>
                <p className="text-sm font-medium text-foreground">开启阶梯佣金</p>
                <p className="mt-1 text-xs text-muted-foreground">同一客户多次购买，佣金比例按阶梯递减</p>
              </div>
              <button
                type="button"
                onClick={() => updRule("tier_enabled", !rules.tier_enabled)}
                className={cn(
                  "relative h-6 w-11 shrink-0 rounded-full transition-colors",
                  rules.tier_enabled ? "bg-primary" : "bg-muted-foreground/30"
                )}
              >
                <span className={cn(
                  "absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  rules.tier_enabled ? "translate-x-5" : "translate-x-0.5"
                )} />
              </button>
            </div>
          </div>
        </div>

        {/* 2. 结算与提现 */}
        <div className="px-6 py-5">
          <div className="mb-4 flex items-center gap-2">
            <Coins className="h-4 w-4 text-primary" />
            <h3 className="text-sm font-semibold text-foreground">结算与提现</h3>
          </div>
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">佣金结算延迟 (天)</label>
              <input
                type="number"
                min={0}
                value={rules.settle_delay_days}
                onChange={(e) => updRule("settle_delay_days", parseInt(e.target.value) || 0)}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <p className="mt-1.5 text-xs text-muted-foreground">订单支付完成后 N 天佣金才可提现</p>
            </div>

            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">最低提现金额 (元)</label>
              <input
                type="number"
                min={0}
                step="0.01"
                value={rules.min_withdraw_amount}
                onChange={(e) => updRule("min_withdraw_amount", parseFloat(e.target.value) || 0)}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <p className="mt-1.5 text-xs text-muted-foreground">单笔提现申请的最低金额</p>
            </div>

            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">提现手续费率 (%)</label>
              <input
                type="number"
                min={0}
                max={100}
                step="0.01"
                value={rules.withdraw_fee_rate}
                onChange={(e) => updRule("withdraw_fee_rate", parseFloat(e.target.value) || 0)}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <p className="mt-1.5 text-xs text-muted-foreground">0 = 免费提现</p>
            </div>

            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">客户保护期 (天)</label>
              <input
                type="number"
                min={0}
                value={rules.binding_protection_days}
                onChange={(e) => updRule("binding_protection_days", parseInt(e.target.value) || 0)}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <p className="mt-1.5 text-xs text-muted-foreground">客户绑定推广员后 N 天内不可被其他推广员抢走</p>
            </div>
          </div>
        </div>
      </div>

      {/* 佣金比例 + 佣金阶梯配置（同一行两个等高卡片） */}
      <div className="grid gap-6 xl:grid-cols-2">
        {/* 佣金比例（竖排） */}
        <div className="flex flex-col rounded-lg border border-border bg-card p-6 shadow-sm">
          <div className="mb-5 flex items-center gap-2">
            <Percent className="h-5 w-5 text-primary" />
            <h2 className="text-base font-semibold text-foreground">佣金比例</h2>
          </div>
          <div className="flex flex-col gap-5">
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">默认佣金比例 (%)</label>
              <input
                type="number"
                min={0}
                max={100}
                step="0.01"
                value={rules.default_rate}
                onChange={(e) => updRule("default_rate", parseFloat(e.target.value) || 0)}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <p className="mt-1.5 text-xs text-muted-foreground">未单独配置的商品 / 分销员使用此比例</p>
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">下级抽成比例 (%)</label>
              <input
                type="number"
                min={0}
                max={100}
                step="0.01"
                value={rules.default_sub_rate}
                onChange={(e) => updRule("default_sub_rate", parseFloat(e.target.value) || 0)}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <p className="mt-1.5 text-xs text-muted-foreground">一级分销员从下级佣金中抽成的默认比例</p>
            </div>
          </div>
        </div>

        {/* 佣金阶梯配置 */}
        <div className="flex flex-col rounded-lg border border-border bg-card p-6 shadow-sm">
          <div className="mb-5 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <TrendingUp className="h-5 w-5 text-primary" />
              <div>
                <h2 className="text-base font-semibold text-foreground">佣金阶梯配置</h2>
                <p className="text-xs text-muted-foreground">同一客户多次购买，佣金按购买次序递减，超出最后档位不再返佣（0%）</p>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={addTier}
                className="inline-flex h-9 items-center gap-2 rounded-lg border border-input px-3 text-sm font-medium text-foreground hover:bg-accent"
              >
                <Plus className="h-4 w-4" />
                新增阶梯
              </button>
              <button
                type="button"
                disabled={savingTiers}
                onClick={handleSaveTiers}
                className="inline-flex h-9 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
              >
                {savingTiers ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" /> : <Save className="h-4 w-4" />}
                保存阶梯
              </button>
            </div>
          </div>

          {tiers.length === 0 ? (
            <div className="rounded-lg border border-dashed border-border bg-muted/20 py-12 text-center">
              <TrendingUp className="mx-auto h-8 w-8 text-muted-foreground/50" />
              <p className="mt-3 text-sm text-muted-foreground">暂无阶梯配置，点击「新增阶梯」开始配置</p>
            </div>
          ) : (
            <div className="overflow-hidden rounded-lg border border-border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border bg-muted/30">
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">购买次序</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">佣金比例（占基础佣金 %）</th>
                    <th className="px-4 py-3 text-right font-medium text-muted-foreground">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {tiers.map((t) => (
                    <tr key={t.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                      <td className="px-4 py-3">
                        <input
                          type="number"
                          min={1}
                          step={1}
                          value={t.tier_order}
                          onChange={(e) => updateTier(t.id, "tier_order", Math.max(1, parseInt(e.target.value, 10) || 1))}
                          placeholder="第几次购买"
                          className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                        />
                      </td>
                      <td className="px-4 py-3">
                        <input
                          type="number"
                          min={0}
                          max={100}
                          step="0.01"
                          value={t.rate}
                          onChange={(e) => updateTier(t.id, "rate", parseFloat(e.target.value) || 0)}
                          className="h-9 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                        />
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex justify-end">
                          <button
                            type="button"
                            onClick={() => removeTier(t.id)}
                            className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-red-500/10 hover:text-red-500"
                            title="删除"
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

// ═══════════════════════ 分页器 ═══════════════════════

function Pager({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  const pages = useMemo(() => {
    const arr: (number | "…")[] = []
    if (totalPages <= 7) {
      for (let i = 1; i <= totalPages; i++) arr.push(i)
    } else {
      arr.push(1)
      if (page > 3) arr.push("…")
      for (let i = Math.max(2, page - 1); i <= Math.min(totalPages - 1, page + 1); i++) arr.push(i)
      if (page < totalPages - 2) arr.push("…")
      arr.push(totalPages)
    }
    return arr
  }, [page, totalPages])

  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        disabled={page <= 1}
        onClick={() => onChange(page - 1)}
        className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-40"
      >
        <ChevronLeft className="h-4 w-4" />
      </button>
      {pages.map((p, idx) =>
        p === "…" ? (
          <span key={`e${idx}`} className="px-1 text-sm text-muted-foreground">…</span>
        ) : (
          <button
            key={p}
            type="button"
            onClick={() => onChange(p)}
            className={cn(
              "h-8 min-w-8 rounded-md px-2 text-sm font-medium transition-colors",
              p === page ? "bg-primary text-primary-foreground" : "border border-input text-muted-foreground hover:bg-accent hover:text-foreground"
            )}
          >
            {p}
          </button>
        )
      )}
      <button
        type="button"
        disabled={page >= totalPages}
        onClick={() => onChange(page + 1)}
        className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-40"
      >
        <ChevronRight className="h-4 w-4" />
      </button>
    </div>
  )
}

// ═══════════════════════ 管理员版分销规则说明 ═══════════════════════

function AdminRulesModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  if (!open) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative flex max-h-[86vh] w-full max-w-3xl flex-col overflow-hidden rounded-xl border border-border bg-card shadow-2xl">
        {/* 头部 */}
        <div className="flex items-center justify-between border-b border-border px-6 py-4">
          <h2 className="flex items-center gap-2 text-lg font-bold text-foreground">
            <ScrollText className="h-5 w-5 text-primary" />
            分销推广规则（管理员版）
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* 内容 */}
        <div className="flex-1 overflow-y-auto px-6 py-5">
          <div className="flex flex-col gap-5">
            <AdminSection icon={<TrendingUp className="h-4 w-4" />} title="一、分销体系概览">
              <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                <li>分销模型：一级分销（推广赚佣）+ 二级分销（下级佣金抽成），层级最多两级</li>
                <li>参与角色：管理员 / 一级分销员（可邀请下级并抽成）/ 二级分销员 / 普通购买用户</li>
                <li>佣金基数 = 订单实际付款金额（扣除优惠券、积分抵扣后的实付金额）</li>
                <li>推广链路：推广链接 → 点击追踪 → 客户绑定 → 下单付款 → 佣金记录 → 结算 → 提现</li>
              </ul>
            </AdminSection>

            <AdminSection icon={<Percent className="h-4 w-4" />} title="二、佣金比例体系（优先级）">
              <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm text-muted-foreground">
                <li>分销员自定义比例（custom_rate）</li>
                <li>商品专属配置（product_commission.custom_rate，未配置则跳过）</li>
                <li>全局默认比例（default_rate）</li>
              </ol>
              <p className="mt-3 rounded-lg bg-muted/40 px-3 py-2 text-sm text-muted-foreground">
                二级抽成：一级分销员按 sub_rate 从下级每笔佣金中抽取，上级抽成金额单列一条佣金记录。
                下级实得 = 佣金 − 上级抽成，平台总支出不超过原佣金金额。
              </p>
            </AdminSection>

            <AdminSection icon={<Coins className="h-4 w-4" />} title="三、佣金记录与状态">
              <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                <li>订单付款（PAID）时按订单项逐条创建佣金记录；上级抽成单独创建记录</li>
                <li>状态机：PENDING（待结算）→ SETTLED（已结算）→ CANCELED（退款/违规取消）</li>
                <li>记录含：订单号、商品、购买次数（阶梯用）、比例、佣金金额、上下级关系</li>
                <li>佣金明细可在「佣金记录」Tab 中按分销员 / 状态 / 时间筛选查看</li>
              </ul>
            </AdminSection>

            <AdminSection icon={<Clock className="h-4 w-4" />} title="四、佣金结算机制">
              <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                <li>订单状态机：PAID → DELIVERED（发货）→ COMPLETED（已完成）</li>
                <li>发货后 24 小时无其他操作的订单由定时任务自动置为 COMPLETED（每 5 分钟）</li>
                <li>结算条件：订单 COMPLETED 且 completedAt 早于当前时间 − settle_delay_days（定时任务每小时）</li>
                <li>结算动作：佣金 PENDING → SETTLED，金额计入分销员可提现余额</li>
              </ul>
            </AdminSection>

            <AdminSection icon={<X className="h-4 w-4" />} title="五、退款与佣金取消">
              <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                <li>管理后台可对已支付 / 已发货 / 已完成的微信支付订单发起全额或部分退款（原路退回）</li>
                <li>退款金额不超过订单实付金额，可多次部分退款，累计不超过实付金额</li>
                <li>退款时自动取消该订单全部佣金：PENDING 直接置为 CANCELED</li>
                <li>已 SETTLED 的佣金从分销员余额扣回；已提现的标记待追回，从后续佣金中扣除</li>
                <li>三道防线防退款套佣：24h 自动完成 + 结算延迟期 + 退款扣佣金</li>
              </ul>
            </AdminSection>

            <AdminSection icon={<Wallet className="h-4 w-4" />} title="六、提现流程">
              <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm text-muted-foreground">
                <li>分销员申请提现 → 余额冻结（available → frozen）</li>
                <li>管理员审核通过（「提现管理」Tab）→ 发起微信商家转账到零钱</li>
                <li>用户在微信内确认收款 → 状态置为 SUCCESS，frozen → withdrawn</li>
                <li>审核拒绝 / 转账失败 → 冻结余额退回 available</li>
              </ol>
              <p className="mt-3 rounded-lg bg-muted/40 px-3 py-2 text-sm text-muted-foreground">
                兜底机制：定时查询 PROCESSING 状态的转账（每 5 分钟）；超 24 小时未确认收款自动撤销并退回余额。
                单笔金额受微信渠道限制（最低 / 最高），提现需分销员绑定本人微信。
              </p>
            </AdminSection>

            <AdminSection icon={<ShieldCheck className="h-4 w-4" />} title="七、资金安全">
              <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                <li>余额对账（每日）：校验 SUM(佣金) = available + frozen + withdrawn，不一致报警日志</li>
                <li>提现幂等：以 out_bill_no 保证不重复转账</li>
                <li>余额扣减使用数据库行锁，防止并发超提</li>
                <li>佣金取消导致余额为负时标记待追回，从后续佣金中优先扣除</li>
              </ul>
            </AdminSection>

            <AdminSection icon={<Users2 className="h-4 w-4" />} title="八、客户绑定与保护期">
              <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                <li>客户首次通过推广链接进入即绑定到对应分销员（匿名用户按邮箱绑定）</li>
                <li>保护期（binding_protection_days）内该客户所有订单归属原分销员</li>
                <li>保护期过后客户通过其他推广链接进入可重新绑定</li>
                <li>客户绑定关系可在「客户管理」中查看</li>
              </ul>
            </AdminSection>

            <AdminSection icon={<Layers className="h-4 w-4" />} title="九、阶梯佣金">
              <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                <li>开启（tier_enabled）后，同一客户第 N 次购买按对应档位比例计算佣金</li>
                <li>档位 rate 为占标准佣金的比例（如 100% / 60% / 30%）</li>
                <li>购买次数超过最高档位后不再返佣；档位在「规则」Tab 中配置</li>
                <li>购买次数按客户绑定（purchase_count）累计，跨商品通用</li>
              </ul>
            </AdminSection>

            <AdminSection icon={<Settings className="h-4 w-4" />} title="十、管理操作指引">
              <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                <li>推广员管理：审核 / 拒绝 / 禁用 / 解禁分销员，设置自定义佣金比例与下级抽成比例</li>
                <li>商品佣金：默认全部参与分销，可排除指定商品或设置商品专属比例</li>
                <li>规则配置：在「规则」Tab 中设置总开关、默认比例、结算延迟、提现门槛、保护期、阶梯佣金等</li>
                <li>提现管理：审核提现申请、发起微信转账、查询转账状态、手动标记完成</li>
                <li>所有分销操作均记录操作日志，便于追溯</li>
              </ul>
            </AdminSection>
          </div>
        </div>
      </div>
    </div>
  )
}

function AdminSection({
  icon, title, children,
}: {
  icon: React.ReactNode
  title: string
  children: React.ReactNode
}) {
  return (
    <section className="rounded-xl border border-border bg-background p-5">
      <h3 className="mb-3 flex items-center gap-2 text-sm font-bold text-foreground">
        <span className="flex h-7 w-7 items-center justify-center rounded-md bg-primary/10 text-primary">
          {icon}
        </span>
        {title}
      </h3>
      {children}
    </section>
  )
}

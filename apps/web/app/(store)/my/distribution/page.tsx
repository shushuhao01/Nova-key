"use client"

import React, { useState, useEffect, useCallback, useRef } from "react"
import {
  TrendingUp, Wallet, Coins, BarChart3, ArrowDownToLine, Share2,
  Link2, Copy, Check, X, Clock, Ban, Users2, Package, RefreshCw,
  ChevronLeft, ChevronRight, AlertCircle, UserPlus, Store, QrCode,
  ShoppingBag, ChevronRight as ChevronRightIcon, ScrollText, Percent,
  Layers, Users, HandCoins, ShieldCheck, Shield, Download,
  Image as ImageIcon,
} from "lucide-react"
import { toast } from "sonner"
import { useLocale } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"
import { distributorApi, getApiErrorMessage } from "@/services/api"
import { cn } from "@/lib/utils"

const PROMOTION_BASE_URL = "https://noepay.cn/p"
const PAGE_SIZE = 10

type Tab = "overview" | "products" | "commissions" | "withdrawals" | "subordinates"
type DistributorStatus = "PENDING" | "APPROVED" | "REJECTED" | "DISABLED"
type CommissionStatus = "PENDING" | "SETTLED" | "CANCELED"
type WithdrawalStatus = "PENDING" | "APPROVED" | "REJECTED" | "PROCESSING" | "SUCCESS" | "FAILED"

const fmtMoney = (n: number | null | undefined) => `¥${(Number(n) || 0).toFixed(2)}`
const fmtDate = (s: string | null | undefined) => (s ? new Date(s).toLocaleString() : "—")

const distributorStatusMap: Record<DistributorStatus, { label: string; cls: string }> = {
  PENDING: { label: "审核中", cls: "bg-amber-500/10 text-amber-600" },
  APPROVED: { label: "已通过", cls: "bg-emerald-500/10 text-emerald-600" },
  REJECTED: { label: "已拒绝", cls: "bg-red-500/10 text-red-500" },
  DISABLED: { label: "已禁用", cls: "bg-muted text-muted-foreground" },
}

const commissionStatusMap: Record<CommissionStatus, { label: string; cls: string }> = {
  PENDING: { label: "待结算", cls: "bg-amber-500/10 text-amber-600" },
  SETTLED: { label: "已结算", cls: "bg-emerald-500/10 text-emerald-600" },
  CANCELED: { label: "已取消", cls: "bg-red-500/10 text-red-500" },
}

const withdrawalStatusMap: Record<WithdrawalStatus, { label: string; cls: string }> = {
  PENDING: { label: "审核中", cls: "bg-amber-500/10 text-amber-600" },
  APPROVED: { label: "待打款", cls: "bg-blue-500/10 text-blue-600" },
  REJECTED: { label: "已拒绝", cls: "bg-red-500/10 text-red-500" },
  PROCESSING: { label: "转账中", cls: "bg-blue-500/10 text-blue-600" },
  SUCCESS: { label: "已到账", cls: "bg-emerald-500/10 text-emerald-600" },
  FAILED: { label: "已失败", cls: "bg-red-500/10 text-red-500" },
}

export default function DistributionPage() {
  const { t } = useLocale()
  const user = useRequireAuth()
  const [profile, setProfile] = useState<any>(null)
  const [loading, setLoading] = useState(true)
  const [notDistributor, setNotDistributor] = useState(false)
  const [activeTab, setActiveTab] = useState<Tab>("overview")
  const [rulesOpen, setRulesOpen] = useState(false)

  const fetchProfile = useCallback(async () => {
    setLoading(true)
    try {
      const data = await distributorApi.getProfile()
      setProfile(data)
      setNotDistributor(false)
    } catch (err) {
      // 非 404 类业务错误才提示；not found 视为未成为分销员
      const code = (err as any)?.code
      if (code === 40400 || code === 404 || err instanceof Error && /not found|未找到|不存在/i.test(err.message)) {
        setNotDistributor(true)
      } else {
        toast.error(getApiErrorMessage(err, t))
        setNotDistributor(true)
      }
      setProfile(null)
    } finally {
      setLoading(false)
    }
  }, [t])

  useEffect(() => {
    if (!user) return
    fetchProfile()
  }, [user, fetchProfile])

  if (!user) return null

  if (loading) {
    return (
      <div className="mx-auto max-w-4xl">
        <div className="flex items-center justify-center py-24">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      </div>
    )
  }

  // 未成为分销员 → 申请页
  if (notDistributor || !profile) {
    return <ApplyDistributorForm onApplied={fetchProfile} />
  }

  const status: DistributorStatus = profile.status || "PENDING"

  // 审核中
  if (status === "PENDING") {
    return (
      <div className="mx-auto max-w-2xl">
        <StatusNotice
          icon={<Clock className="h-10 w-10 text-amber-500" />}
          title="申请审核中"
          desc="您的分销员申请已提交，平台正在审核，请耐心等待。审核通过后即可使用分销中心所有功能。"
          tone="amber"
        >
          <div className="mt-4 rounded-lg bg-muted/40 p-4 text-left text-sm">
            <p className="text-muted-foreground">申请时间</p>
            <p className="mt-1 font-medium text-foreground">{fmtDate(profile.applied_at)}</p>
          </div>
        </StatusNotice>
      </div>
    )
  }

  // 已拒绝
  if (status === "REJECTED") {
    return (
      <div className="mx-auto max-w-2xl">
        <StatusNotice
          icon={<AlertCircle className="h-10 w-10 text-red-500" />}
          title="申请未通过"
          desc="很抱歉，您的分销员申请未通过审核。您可以重新提交申请。"
          tone="red"
        >
          {profile.reject_reason && (
            <div className="mt-4 rounded-lg bg-red-500/5 p-4 text-left text-sm">
              <p className="text-muted-foreground">拒绝原因</p>
              <p className="mt-1 font-medium text-red-600">{profile.reject_reason}</p>
            </div>
          )}
          <div className="mt-4">
            <ApplyDistributorForm onApplied={fetchProfile} compact />
          </div>
        </StatusNotice>
      </div>
    )
  }

  // 已禁用
  if (status === "DISABLED") {
    return (
      <div className="mx-auto max-w-2xl">
        <StatusNotice
          icon={<Ban className="h-10 w-10 text-muted-foreground" />}
          title="账号已被禁用"
          desc="您的分销员账号已被平台禁用，如有疑问请联系客服。"
          tone="muted"
        />
      </div>
    )
  }

  // 已通过 → 主界面
  const tabs: { key: Tab; label: string; icon: typeof TrendingUp }[] = [
    { key: "overview", label: "概览", icon: TrendingUp },
    { key: "products", label: "推广商品", icon: Share2 },
    { key: "commissions", label: "佣金明细", icon: Coins },
    { key: "withdrawals", label: "提现记录", icon: Wallet },
    { key: "subordinates", label: "我的下级", icon: Users2 },
  ]

  return (
    <div className="mx-auto max-w-4xl">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-foreground">分销中心</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            欢迎回来，{profile.username || user.username} · 佣金比例
            <span className="ml-1 font-medium text-primary">
              {((profile.custom_rate ?? profile.default_rate) || 0).toFixed(2)}%
            </span>
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => setRulesOpen(true)}
            className="inline-flex h-9 items-center gap-2 rounded-lg border border-input px-3 text-sm text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          >
            <ScrollText className="h-4 w-4" />
            规则
          </button>
          <button
            type="button"
            onClick={fetchProfile}
            className="inline-flex h-9 items-center gap-2 rounded-lg border border-input px-3 text-sm text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
          >
            <RefreshCw className="h-4 w-4" />
            刷新
          </button>
        </div>
      </div>

      {/* Tab 导航 */}
      <div className="mb-6 flex flex-wrap rounded-lg bg-muted p-1">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            type="button"
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              "flex flex-1 items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors",
              activeTab === tab.key
                ? "bg-background text-foreground shadow-sm"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            <tab.icon className="h-4 w-4" />
            <span className="hidden sm:inline">{tab.label}</span>
          </button>
        ))}
      </div>

      {activeTab === "overview" && <OverviewTab profile={profile} onSwitchTab={setActiveTab} />}
      {activeTab === "products" && <ProductsTab profile={profile} />}
      {activeTab === "commissions" && <CommissionsTab />}
      {activeTab === "withdrawals" && <WithdrawalsTab balance={profile.available_balance} />}
      {activeTab === "subordinates" && <SubordinatesTab />}

      {/* 分销规则弹窗 */}
      <RulesModal open={rulesOpen} onClose={() => setRulesOpen(false)} />
    </div>
  )
}

// ═══════════════════════ 状态提示卡片 ═══════════════════════

function StatusNotice({
  icon, title, desc, tone, children,
}: {
  icon: React.ReactNode
  title: string
  desc: string
  tone: "amber" | "red" | "muted"
  children?: React.ReactNode
}) {
  const toneCls = {
    amber: "border-amber-500/20 bg-amber-500/5",
    red: "border-red-500/20 bg-red-500/5",
    muted: "border-border bg-muted/30",
  }[tone]
  return (
    <div className={cn("rounded-xl border p-8 text-center", toneCls)}>
      <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-background shadow-sm">
        {icon}
      </div>
      <h1 className="text-lg font-bold text-foreground">{title}</h1>
      <p className="mx-auto mt-2 max-w-md text-sm text-muted-foreground">{desc}</p>
      {children}
    </div>
  )
}

// ═══════════════════════ 申请成为分销员 ═══════════════════════

function ApplyDistributorForm({ onApplied, compact }: { onApplied: () => void; compact?: boolean }) {
  const { t } = useLocale()
  const [inviteCode, setInviteCode] = useState("")
  const [submitting, setSubmitting] = useState(false)

  const handleApply = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    try {
      await distributorApi.apply(inviteCode.trim() || undefined)
      toast.success("申请已提交，请等待审核")
      onApplied()
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setSubmitting(false)
    }
  }

  if (compact) {
    return (
      <form onSubmit={handleApply} className="flex flex-wrap items-end justify-center gap-3">
        <div className="text-left">
          <label className="mb-1.5 block text-xs text-muted-foreground">邀请码（选填）</label>
          <input
            type="text"
            value={inviteCode}
            onChange={(e) => setInviteCode(e.target.value)}
            placeholder="输入邀请码"
            className="h-10 w-48 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          />
        </div>
        <button
          type="submit"
          disabled={submitting}
          className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-5 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
        >
          {submitting ? (
            <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
          ) : (
            <UserPlus className="h-4 w-4" />
          )}
          重新申请
        </button>
      </form>
    )
  }

  return (
    <div className="mx-auto max-w-2xl">
      <div className="rounded-xl border border-border bg-card p-8 shadow-sm">
        <div className="mb-6 flex flex-col items-center text-center">
          <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
            <Share2 className="h-8 w-8 text-primary" />
          </div>
          <h1 className="text-xl font-bold text-foreground">成为分销员</h1>
          <p className="mt-2 max-w-md text-sm text-muted-foreground">
            加入分销计划，推广商品赚取佣金。填写邀请码（如有）提交申请，审核通过后即可开始推广。
          </p>
        </div>

        <form onSubmit={handleApply} className="flex flex-col gap-4">
          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">邀请码（选填）</label>
            <input
              type="text"
              value={inviteCode}
              onChange={(e) => setInviteCode(e.target.value)}
              placeholder="请输入邀请码，无则留空"
              className="h-11 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <button
            type="submit"
            disabled={submitting}
            className="inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
          >
            {submitting ? (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
            ) : (
              <UserPlus className="h-4 w-4" />
            )}
            提交申请
          </button>
        </form>
      </div>
    </div>
  )
}

// ═══════════════════════ 概览 TAB ═══════════════════════

interface OverviewStats {
  available_balance?: number
  pending_settlement?: number
  total_commission?: number
  month_sales?: number
  frozen_balance?: number
  withdrawn_amount?: number
}

function OverviewTab({
  profile, onSwitchTab,
}: {
  profile: any
  onSwitchTab: (t: Tab) => void
}) {
  const { t } = useLocale()
  const [stats, setStats] = useState<OverviewStats | null>(null)
  const [recentCommissions, setRecentCommissions] = useState<any[]>([])
  const [recentWithdrawals, setRecentWithdrawals] = useState<any[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    async function fetchAll() {
      setLoading(true)
      try {
        const [s, c, w] = await Promise.all([
          distributorApi.getStats().catch(() => null),
          distributorApi.listCommissions({ page: 1, page_size: 5 }).catch(() => ({ list: [] })),
          distributorApi.listWithdrawals({ page: 1, page_size: 5 }).catch(() => ({ list: [] })),
        ])
        if (cancelled) return
        setStats(s as OverviewStats)
        setRecentCommissions((c as any)?.list || [])
        setRecentWithdrawals((w as any)?.list || [])
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    fetchAll()
    return () => { cancelled = true }
  }, [])

  const s = stats || {}
  const available = s.available_balance ?? profile.available_balance ?? 0
  const pending = s.pending_settlement ?? profile.pending_settlement ?? 0
  const total = s.total_commission ?? profile.total_commission ?? 0
  const monthSales = s.month_sales ?? 0

  const cards = [
    { label: "可提现余额", value: fmtMoney(available), icon: Wallet, color: "text-cyan-600 bg-cyan-500/10" },
    { label: "待结算佣金", value: fmtMoney(pending), icon: Clock, color: "text-amber-600 bg-amber-500/10" },
    { label: "累计佣金", value: fmtMoney(total), icon: Coins, color: "text-emerald-600 bg-emerald-500/10" },
    { label: "本月成交额", value: fmtMoney(monthSales), icon: BarChart3, color: "text-blue-600 bg-blue-500/10" },
  ]

  if (loading) {
    return (
      <div className="flex items-center justify-center py-20">
        <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      {/* 统计卡片 */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {cards.map((c) => (
          <div key={c.label} className="rounded-xl border border-border bg-card p-5 shadow-sm">
            <div className="flex items-center justify-between">
              <span className="text-sm text-muted-foreground">{c.label}</span>
              <span className={cn("flex h-8 w-8 items-center justify-center rounded-md", c.color)}>
                <c.icon className="h-4 w-4" />
              </span>
            </div>
            <p className="mt-3 text-2xl font-bold text-foreground">{c.value}</p>
          </div>
        ))}
      </div>

      {/* 快捷操作 */}
      <div className="grid grid-cols-2 gap-4">
        <button
          type="button"
          onClick={() => onSwitchTab("withdrawals")}
          className="flex items-center gap-3 rounded-xl border border-border bg-card p-5 text-left shadow-sm transition-colors hover:border-primary/30"
        >
          <span className="flex h-11 w-11 items-center justify-center rounded-lg bg-primary/10">
            <ArrowDownToLine className="h-5 w-5 text-primary" />
          </span>
          <div>
            <p className="font-semibold text-foreground">申请提现</p>
            <p className="text-xs text-muted-foreground">将可提现余额提现到账户</p>
          </div>
          <ChevronRightIcon className="ml-auto h-4 w-4 text-muted-foreground" />
        </button>
        <button
          type="button"
          onClick={() => onSwitchTab("products")}
          className="flex items-center gap-3 rounded-xl border border-border bg-card p-5 text-left shadow-sm transition-colors hover:border-primary/30"
        >
          <span className="flex h-11 w-11 items-center justify-center rounded-lg bg-primary/10">
            <Share2 className="h-5 w-5 text-primary" />
          </span>
          <div>
            <p className="font-semibold text-foreground">推广商品</p>
            <p className="text-xs text-muted-foreground">生成专属推广链接</p>
          </div>
          <ChevronRightIcon className="ml-auto h-4 w-4 text-muted-foreground" />
        </button>
      </div>

      {/* 最近佣金 + 最近提现 */}
      <div className="grid gap-4 lg:grid-cols-2">
        {/* 最近佣金 */}
        <div className="rounded-xl border border-border bg-card shadow-sm">
          <div className="flex items-center justify-between border-b border-border px-5 py-3">
            <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <Coins className="h-4 w-4 text-emerald-600" />
              最近佣金记录
            </h3>
            <button
              type="button"
              onClick={() => onSwitchTab("commissions")}
              className="text-xs text-primary hover:underline"
            >
              查看全部
            </button>
          </div>
          <div className="divide-y divide-border">
            {recentCommissions.length === 0 ? (
              <div className="py-10 text-center text-sm text-muted-foreground">{t("common.noData")}</div>
            ) : (
              recentCommissions.map((c) => {
                const st = commissionStatusMap[c.status as CommissionStatus] || { label: c.status, cls: "bg-muted text-muted-foreground" }
                return (
                  <div key={c.id} className="flex items-center justify-between px-5 py-3">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium text-foreground">{c.product_title || "—"}</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">{fmtDate(c.created_at)}</p>
                    </div>
                    <div className="ml-3 shrink-0 text-right">
                      <p className="text-sm font-semibold text-emerald-600">{fmtMoney(c.amount)}</p>
                      <span className={cn("mt-0.5 inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium", st.cls)}>
                        {st.label}
                      </span>
                    </div>
                  </div>
                )
              })
            )}
          </div>
        </div>

        {/* 最近提现 */}
        <div className="rounded-xl border border-border bg-card shadow-sm">
          <div className="flex items-center justify-between border-b border-border px-5 py-3">
            <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
              <Wallet className="h-4 w-4 text-cyan-600" />
              最近提现记录
            </h3>
            <button
              type="button"
              onClick={() => onSwitchTab("withdrawals")}
              className="text-xs text-primary hover:underline"
            >
              查看全部
            </button>
          </div>
          <div className="divide-y divide-border">
            {recentWithdrawals.length === 0 ? (
              <div className="py-10 text-center text-sm text-muted-foreground">{t("common.noData")}</div>
            ) : (
              recentWithdrawals.map((w) => {
                const st = withdrawalStatusMap[w.status as WithdrawalStatus] || { label: w.status, cls: "bg-muted text-muted-foreground" }
                return (
                  <div key={w.id} className="flex items-center justify-between px-5 py-3">
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-foreground">{fmtMoney(w.amount)}</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">{fmtDate(w.created_at)}</p>
                    </div>
                    <span className={cn("ml-3 inline-flex shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium", st.cls)}>
                      {st.label}
                    </span>
                  </div>
                )
              })
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

// ═══════════════════════ 推广商品 TAB ═══════════════════════

function ProductsTab({ profile }: { profile: any }) {
  const [subTab, setSubTab] = useState<"available" | "mine">("available")
  const [list, setList] = useState<any[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [linkModal, setLinkModal] = useState<{ productName: string; linkUrl: string } | null>(null)
  const [storeLinkModal, setStoreLinkModal] = useState<string | null>(null)
  const [generatingStore, setGeneratingStore] = useState(false)
  const [storePoster, setStorePoster] = useState<any>(null)
  const [productPoster, setProductPoster] = useState<any>(null)
  const [generatingStorePoster, setGeneratingStorePoster] = useState(false)
  const [generatingPosterId, setGeneratingPosterId] = useState<string | null>(null)

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const params = { page, page_size: PAGE_SIZE }
      const data = subTab === "available"
        ? await distributorApi.listProducts(params)
        : await distributorApi.listMyProducts(params)
      setList((data as any)?.list || [])
      setTotal((data as any)?.pagination?.total ?? 0)
    } catch {
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [subTab, page])

  useEffect(() => { fetchList() }, [fetchList])
  useEffect(() => { setPage(1) }, [subTab])

  const handleGenerateLink = async (product: any) => {
    try {
      const res = await distributorApi.generateLink(product.product_id || product.id)
      const linkCode = res?.link_code || res?.code
      const linkUrl = res?.link_url || (linkCode ? `${PROMOTION_BASE_URL}/${linkCode}` : "")
      if (!linkUrl) {
        toast.error("生成推广链接失败")
        return
      }
      setLinkModal({ productName: product.title, linkUrl })
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "生成失败")
    }
  }

  const handleGenerateStoreLink = async () => {
    setGeneratingStore(true)
    try {
      const res = await distributorApi.generateStoreLink()
      const linkCode = res?.link_code || res?.code
      const linkUrl = res?.link_url || (linkCode ? `${PROMOTION_BASE_URL}/${linkCode}` : "")
      if (!linkUrl) {
        toast.error("生成店铺推广链接失败")
        return
      }
      setStoreLinkModal(linkUrl)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "生成失败")
    } finally {
      setGeneratingStore(false)
    }
  }

  const handleGenerateStorePoster = async () => {
    setGeneratingStorePoster(true)
    try {
      const res = await distributorApi.generateStorePoster()
      setStorePoster(res)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "生成海报失败")
    } finally {
      setGeneratingStorePoster(false)
    }
  }

  const handleGeneratePoster = async (p: any) => {
    const pid = p.product_id || p.id
    if (!pid) return
    setGeneratingPosterId(pid)
    try {
      const res = await distributorApi.generateProductPoster(String(pid))
      setProductPoster(res)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "生成海报失败")
    } finally {
      setGeneratingPosterId(null)
    }
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const effectiveRate = (p: any) => ((p.custom_rate ?? p.default_rate) || 0)

  return (
    <div className="flex flex-col gap-4">
      {/* 店铺推广 */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-primary/20 bg-primary/5 p-4">
        <div className="flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary/10">
            <Store className="h-5 w-5 text-primary" />
          </span>
          <div>
            <p className="font-semibold text-foreground">推广整个店铺</p>
            <p className="text-xs text-muted-foreground">生成店铺专属推广链接 / 海报，覆盖全部商品</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={handleGenerateStoreLink}
            disabled={generatingStore}
            className="inline-flex h-9 items-center gap-2 rounded-lg border border-input bg-background px-4 text-sm font-semibold text-foreground transition-all hover:bg-accent disabled:opacity-50"
          >
            {generatingStore ? (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            ) : (
              <Link2 className="h-4 w-4" />
            )}
            生成链接
          </button>
          <button
            type="button"
            onClick={handleGenerateStorePoster}
            disabled={generatingStorePoster}
            className="inline-flex h-9 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
          >
            {generatingStorePoster ? (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
            ) : (
              <ImageIcon className="h-4 w-4" />
            )}
            生成海报
          </button>
        </div>
      </div>

      {/* 子 Tab */}
      <div className="flex rounded-lg bg-muted p-1">
        {[
          { k: "available", label: "可推广商品" },
          { k: "mine", label: "我推广的商品" },
        ].map((s) => (
          <button
            key={s.k}
            type="button"
            onClick={() => setSubTab(s.k as "available" | "mine")}
            className={cn(
              "flex flex-1 items-center justify-center rounded-md px-3 py-2 text-sm font-medium transition-colors",
              subTab === s.k ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
            )}
          >
            {s.label}
          </button>
        ))}
      </div>

      {/* 商品列表 */}
      {loading ? (
        <div className="flex items-center justify-center py-16">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : list.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-border bg-card py-16 text-sm text-muted-foreground">
          <Package className="h-8 w-8 opacity-40" />
          暂无可推广商品
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {list.map((p) => (
            <div key={p.id || p.product_id} className="flex flex-col rounded-xl border border-border bg-card p-4 shadow-sm">
              <div className="flex gap-3">
                {p.cover_url || p.cover_image ? (
                  <img
                    src={p.cover_url || p.cover_image}
                    alt=""
                    loading="lazy"
                    className="h-14 w-14 shrink-0 rounded-md object-cover"
                  />
                ) : (
                  <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-md bg-muted">
                    <Package className="h-6 w-6 text-muted-foreground" />
                  </div>
                )}
                <div className="min-w-0 flex-1">
                  <p className="line-clamp-2 text-sm font-medium text-foreground">{p.product_title || p.title}</p>
                  <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs text-muted-foreground">
                    <span>售价 {fmtMoney(p.base_price ?? p.price)}</span>
                    <span>佣金 {effectiveRate(p).toFixed(2)}%</span>
                  </div>
                  {subTab === "mine" && (
                    <div className="mt-1.5 grid grid-cols-2 gap-x-3 gap-y-0.5 text-[11px] text-muted-foreground">
                      <span>点击 {p.click_count ?? 0} 次</span>
                      <span>成交 {p.paid_count ?? 0} 单</span>
                      <span>销售额 {fmtMoney(p.total_sales)}</span>
                      <span>佣金 {fmtMoney(p.total_commission)}</span>
                    </div>
                  )}
                </div>
              </div>
              <div className="mt-3 flex gap-2">
                <button
                  type="button"
                  onClick={() => handleGenerateLink(p)}
                  className="inline-flex h-9 flex-1 items-center justify-center gap-2 rounded-lg bg-primary/10 text-sm font-medium text-primary transition-colors hover:bg-primary/20"
                >
                  <Link2 className="h-4 w-4" />
                  生成推广链接
                </button>
                <button
                  type="button"
                  onClick={() => handleGeneratePoster(p)}
                  disabled={generatingPosterId === (p.product_id || p.id)}
                  className="inline-flex h-9 flex-1 items-center justify-center gap-2 rounded-lg border border-input text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
                >
                  {generatingPosterId === (p.product_id || p.id) ? (
                    <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                  ) : (
                    <ImageIcon className="h-4 w-4" />
                  )}
                  生成海报
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* 分页 */}
      {total > PAGE_SIZE && (
        <Pager page={page} totalPages={totalPages} onChange={setPage} />
      )}

      {/* 商品推广链接弹窗 */}
      {linkModal && (
        <LinkResultModal
          title={linkModal.productName}
          linkUrl={linkModal.linkUrl}
          onClose={() => setLinkModal(null)}
        />
      )}

      {/* 店铺推广链接弹窗 */}
      {storeLinkModal && (
        <LinkResultModal
          title="店铺推广链接"
          linkUrl={storeLinkModal}
          onClose={() => setStoreLinkModal(null)}
        />
      )}

      {/* 店铺推广海报弹窗 */}
      {storePoster && (
        <PosterModal data={storePoster} type="store" onClose={() => setStorePoster(null)} />
      )}

      {/* 商品推广海报弹窗 */}
      {productPoster && (
        <PosterModal data={productPoster} type="product" onClose={() => setProductPoster(null)} />
      )}
    </div>
  )
}

function LinkResultModal({
  title, linkUrl, onClose,
}: {
  title: string
  linkUrl: string
  onClose: () => void
}) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(linkUrl)
      setCopied(true)
      toast.success("链接已复制")
      setTimeout(() => setCopied(false), 2000)
    } catch {
      toast.error("复制失败，请手动复制")
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative w-full max-w-sm rounded-xl border border-border bg-card p-6 shadow-2xl">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="flex items-center gap-2 text-base font-bold text-foreground">
            <QrCode className="h-5 w-5 text-primary" />
            推广链接
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <p className="mb-3 truncate text-sm font-medium text-foreground" title={title}>{title}</p>

        {/* 二维码 */}
        <div className="flex justify-center rounded-lg bg-white p-3">
          <img
            src={`/qr-image?url=${encodeURIComponent(linkUrl)}`}
            alt="推广二维码"
            className="h-40 w-40"
          />
        </div>

        {/* 链接文本 + 复制 */}
        <div className="mt-4 flex items-center gap-2 rounded-lg bg-muted/60 p-2.5">
          <span className="min-w-0 flex-1 truncate font-mono text-xs text-foreground">{linkUrl}</span>
          <button
            type="button"
            onClick={handleCopy}
            className={cn(
              "inline-flex h-8 shrink-0 items-center gap-1 rounded-md px-2.5 text-xs font-medium transition-colors",
              copied ? "bg-emerald-500/10 text-emerald-600" : "bg-primary text-primary-foreground hover:brightness-110"
            )}
          >
            {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
            {copied ? "已复制" : "复制"}
          </button>
        </div>

        <p className="mt-3 text-center text-xs text-muted-foreground">扫描二维码或复制链接分享给好友</p>
      </div>
    </div>
  )
}

// ═══════════════════════ 推广海报弹窗（Canvas 合成） ═══════════════════════

function PosterModal({
  data, type, onClose,
}: {
  data: any
  type: "product" | "store"
  onClose: () => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [drawing, setDrawing] = useState(true)
  const [fallback, setFallback] = useState(false)

  const linkUrl: string = data?.link_url || ""

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext("2d")
    if (!ctx) return

    let cancelled = false
    const W = 600
    const H = type === "product" ? 880 : 820
    canvas.width = W
    canvas.height = H

    const truncate = (s: string, n: number) => (s && s.length > n ? `${s.slice(0, n)}…` : s || "")

    const paint = (cover: HTMLImageElement | null) => {
      if (cancelled) return
      ctx.fillStyle = "#ffffff"
      ctx.fillRect(0, 0, W, H)

      // 顶部品牌条
      ctx.fillStyle = "#f97316"
      ctx.fillRect(0, 0, W, 118)
      ctx.fillStyle = "#ffffff"
      ctx.textAlign = "center"
      ctx.font = "bold 32px sans-serif"
      ctx.fillText(truncate(data?.store_name || (type === "product" ? "精选好物" : "全店推广"), 14), W / 2, 60)
      ctx.font = "17px sans-serif"
      ctx.fillText(`推广员 ${data?.distributor_code || ""}`, W / 2, 92)

      let y = 160
      if (type === "product") {
        // 商品主图
        if (cover) {
          const iw = cover.naturalWidth || W
          const ih = cover.naturalHeight || W
          const size = 320
          const scale = Math.max(size / iw, size / ih)
          const dw = iw * scale
          const dh = ih * scale
          ctx.drawImage(cover, (W - dw) / 2, y, dw, dh)
          y += size + 22
        } else {
          y += 26
          ctx.fillStyle = "#9ca3af"
          ctx.font = "18px sans-serif"
          ctx.fillText("（商品图片缺失）", W / 2, y)
          y += 44
        }
        // 商品标题
        ctx.fillStyle = "#111827"
        ctx.font = "bold 28px sans-serif"
        ctx.fillText(truncate(data?.product_title || "", 18), W / 2, y)
        y += 52
        // 价格 + 佣金
        ctx.fillStyle = "#ef4444"
        ctx.font = "bold 36px sans-serif"
        ctx.fillText(`¥${Number(data?.base_price || 0).toFixed(2)}`, W / 2, y + 20)
        y += 62
        ctx.fillStyle = "#f97316"
        ctx.font = "19px sans-serif"
        ctx.fillText(
          `推广佣金 ${Number(data?.commission_rate || 0).toFixed(2)}%（约赚 ¥${Number(data?.commission_amount || 0).toFixed(2)}）`,
          W / 2, y
        )
        y += 70
      } else {
        // 全店海报：店铺 logo / 名称
        if (cover) {
          const size = 150
          ctx.drawImage(cover, (W - size) / 2, y, size, size)
          y += size + 28
        } else {
          y += 40
        }
        ctx.fillStyle = "#111827"
        ctx.font = "bold 34px sans-serif"
        ctx.fillText("精选好物 · 全场推广", W / 2, y)
        y += 56
        ctx.fillStyle = "#6b7280"
        ctx.font = "22px sans-serif"
        ctx.fillText(`全场商品佣金比例 ${Number(data?.default_rate || 0).toFixed(2)}%`, W / 2, y)
        y += 64
        ctx.fillStyle = "#111827"
        ctx.font = "bold 26px sans-serif"
        ctx.fillText("扫码进入商城", W / 2, y)
        y += 56
      }

      // 底部二维码
      const qsize = 240
      const qx = (W - qsize) / 2
      const qy = H - qsize - 128
      // 二维码白底
      ctx.fillStyle = "#ffffff"
      ctx.strokeStyle = "#e5e7eb"
      ctx.lineWidth = 2
      ctx.strokeRect(qx - 10, qy - 10, qsize + 20, qsize + 20)

      const qr = new Image()
      qr.crossOrigin = "anonymous"
      qr.onload = () => {
        if (cancelled) return
        ctx.drawImage(qr, qx, qy, qsize, qsize)
        ctx.fillStyle = "#4b5563"
        ctx.font = "18px sans-serif"
        ctx.fillText("长按识别二维码", W / 2, H - 100)
        ctx.fillStyle = "#9ca3af"
        ctx.font = "14px sans-serif"
        ctx.fillText("分享海报给好友，好友扫码下单即可获得佣金", W / 2, H - 72)
        setDrawing(false)
      }
      qr.onerror = () => {
        if (!cancelled) setDrawing(false)
      }
      qr.src = `/qr-image?url=${encodeURIComponent(linkUrl)}&size=400`
    }

    // 加载封面图（跨域失败时降级为纯文字海报）
    const coverUrl = type === "product" ? data?.cover_url : data?.store_logo
    if (coverUrl) {
      const img = new Image()
      img.crossOrigin = "anonymous"
      img.onload = () => { try { paint(img) } catch { setFallback(true); setDrawing(false) } }
      img.onerror = () => paint(null)
      img.src = coverUrl
    } else {
      paint(null)
    }
    return () => { cancelled = true }
  }, [data, type, linkUrl])

  const downloadPoster = () => {
    const canvas = canvasRef.current
    if (!canvas) return
    try {
      const a = document.createElement("a")
      a.href = canvas.toDataURL("image/png")
      a.download = `推广海报_${type === "product" ? (data?.product_title || "商品") : "全店"}.png`
      a.click()
      toast.success("海报已下载")
    } catch {
      toast.error("海报图片跨域受限，导出失败，请使用「复制链接」分享")
    }
  }

  const sharePoster = async () => {
    const canvas = canvasRef.current
    if (!canvas) return
    try {
      const blob: Blob | null = await new Promise((res) => canvas.toBlob(res, "image/png"))
      if (!blob) return
      const file = new File([blob], "poster.png", { type: "image/png" })
      if (navigator.share && navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: "推广海报" })
      } else {
        const a = document.createElement("a")
        a.href = URL.createObjectURL(blob)
        a.download = "推广海报.png"
        a.click()
        toast.success("海报已下载")
      }
    } catch {
      toast.error("分享失败")
    }
  }

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(linkUrl)
      toast.success("推广链接已复制")
    } catch {
      toast.error("复制失败，请手动复制")
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative flex max-h-[92vh] w-full max-w-sm flex-col overflow-hidden rounded-xl border border-border bg-card shadow-2xl">
        <div className="flex items-center justify-between border-b border-border px-5 py-3">
          <h2 className="flex items-center gap-2 text-base font-bold text-foreground">
            <ImageIcon className="h-5 w-5 text-primary" />
            推广海报
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4">
          <div className="relative flex justify-center rounded-lg bg-white">
            {drawing && (
              <div className="absolute inset-0 flex items-center justify-center">
                <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              </div>
            )}
            <canvas ref={canvasRef} className="max-h-[56vh] w-auto max-w-full rounded-lg" />
          </div>
          {fallback && (
            <p className="mt-2 text-center text-xs text-amber-600">部分图片加载失败，海报已降级为文字版</p>
          )}
          <p className="mt-2 truncate text-center font-mono text-[11px] text-muted-foreground" title={linkUrl}>
            {linkUrl}
          </p>
        </div>

        <div className="grid grid-cols-3 gap-2 border-t border-border p-4">
          <button
            type="button"
            onClick={downloadPoster}
            className="inline-flex h-10 items-center justify-center gap-1.5 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
          >
            <Download className="h-4 w-4" />
            下载海报
          </button>
          <button
            type="button"
            onClick={sharePoster}
            className="inline-flex h-10 items-center justify-center gap-1.5 rounded-lg border border-input text-sm font-medium text-foreground transition-colors hover:bg-accent"
          >
            <Share2 className="h-4 w-4" />
            分享
          </button>
          <button
            type="button"
            onClick={copyLink}
            className="inline-flex h-10 items-center justify-center gap-1.5 rounded-lg border border-input text-sm font-medium text-foreground transition-colors hover:bg-accent"
          >
            <Copy className="h-4 w-4" />
            复制链接
          </button>
        </div>
      </div>
    </div>
  )
}

// ═══════════════════════ 佣金明细 TAB ═══════════════════════

function CommissionsTab() {
  const { t } = useLocale()
  const [list, setList] = useState<any[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [statusFilter, setStatusFilter] = useState<CommissionStatus | "ALL">("ALL")

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const params: { page: number; page_size: number; status?: string } = { page, page_size: PAGE_SIZE }
      if (statusFilter !== "ALL") params.status = statusFilter
      const data = await distributorApi.listCommissions(params)
      setList((data as any)?.list || [])
      setTotal((data as any)?.pagination?.total ?? 0)
    } catch {
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [page, statusFilter])

  useEffect(() => { fetchList() }, [fetchList])
  useEffect(() => { setPage(1) }, [statusFilter])

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))
  const filters: { k: CommissionStatus | "ALL"; label: string }[] = [
    { k: "ALL", label: "全部" },
    { k: "PENDING", label: "待结算" },
    { k: "SETTLED", label: "已结算" },
    { k: "CANCELED", label: "已取消" },
  ]

  return (
    <div className="flex flex-col gap-4">
      {/* 状态筛选 */}
      <div className="flex flex-wrap gap-2">
        {filters.map((f) => (
          <button
            key={f.k}
            type="button"
            onClick={() => setStatusFilter(f.k)}
            className={cn(
              "rounded-full px-3 py-1.5 text-sm font-medium transition-colors",
              statusFilter === f.k
                ? "bg-primary text-primary-foreground"
                : "bg-muted text-muted-foreground hover:bg-accent"
            )}
          >
            {f.label}
          </button>
        ))}
      </div>

      {/* 列表 */}
      {loading ? (
        <div className="flex items-center justify-center py-16">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : list.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-border bg-card py-16 text-sm text-muted-foreground">
          <Coins className="h-8 w-8 opacity-40" />
          {t("common.noData")}
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
          <div className="divide-y divide-border">
            {list.map((c) => {
              const st = commissionStatusMap[c.status as CommissionStatus] || { label: c.status, cls: "bg-muted text-muted-foreground" }
              return (
                <div key={c.id} className="flex items-center justify-between gap-3 p-4">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <ShoppingBag className="h-4 w-4 shrink-0 text-muted-foreground" />
                      <p className="truncate text-sm font-medium text-foreground">{c.product_title || "—"}</p>
                    </div>
                    <p className="mt-1 truncate font-mono text-xs text-muted-foreground">
                      订单 {c.order_no || c.order_id}
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      佣金比例 {(Number(c.rate) || 0).toFixed(2)}% · {fmtDate(c.created_at)}
                    </p>
                  </div>
                  <div className="shrink-0 text-right">
                    <p className="text-sm font-semibold text-emerald-600">{fmtMoney(c.amount)}</p>
                    <span className={cn("mt-1 inline-flex rounded-full px-2 py-0.5 text-[11px] font-medium", st.cls)}>
                      {st.label}
                    </span>
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {total > PAGE_SIZE && <Pager page={page} totalPages={totalPages} onChange={setPage} />}
    </div>
  )
}

// ═══════════════════════ 提现记录 TAB ═══════════════════════

function WithdrawalsTab({ balance }: { balance: number }) {
  const { t } = useLocale()
  const [list, setList] = useState<any[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [modalOpen, setModalOpen] = useState(false)
  // 微信绑定状态
  const [wechat, setWechat] = useState<{ wechat_bound?: boolean; openid?: string | null; nickname?: string | null; bound_at?: string | null } | null>(null)
  const [binding, setBinding] = useState(false)

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const data = await distributorApi.listWithdrawals({ page, page_size: PAGE_SIZE })
      setList((data as any)?.list || [])
      setTotal((data as any)?.pagination?.total ?? 0)
    } catch {
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [page])

  const fetchWechat = useCallback(async () => {
    try {
      const w = await distributorApi.getWechatStatus()
      setWechat(w)
    } catch {
      setWechat(null)
    }
  }, [])

  useEffect(() => { fetchList() }, [fetchList])
  useEffect(() => { fetchWechat() }, [fetchWechat])

  const handleBind = async () => {
    setBinding(true)
    try {
      const r = await distributorApi.getWechatBindUrl()
      if (r?.bind_url) {
        window.location.href = r.bind_url
      } else {
        toast.error(r?.message || "获取绑定链接失败，请检查支付渠道配置")
      }
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setBinding(false)
    }
  }

  const handleUnbind = async () => {
    if (!window.confirm("确定解绑微信？解绑后需重新绑定才能申请提现。")) return
    try {
      await distributorApi.unbindWechat()
      toast.success("已解绑微信")
      fetchWechat()
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    }
  }

  // 微信转账中：拉起确认收款（仅微信内有效）
  const confirmWithdrawal = (w: any) => {
    if (!w.package_info) {
      toast.error("缺少收款确认信息，请稍后重试")
      return
    }
    const bridge = (window as any).WeixinJSBridge
    if (bridge && typeof bridge.invoke === "function") {
      bridge.invoke(
        "requestPayment",
        { package: w.package_info },
        () => {
          toast.success("收款确认已提交，等待到账")
          setTimeout(fetchList, 2000)
        }
      )
    } else {
      toast.error("请在微信内打开此页面确认收款")
    }
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div className="flex flex-col gap-4">
      {/* 余额条 + 申请按钮 */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-card p-5 shadow-sm">
        <div>
          <p className="text-sm text-muted-foreground">可提现余额</p>
          <p className="mt-1 text-2xl font-bold text-foreground">{fmtMoney(balance)}</p>
        </div>
        <button
          type="button"
          onClick={() => setModalOpen(true)}
          className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-5 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
        >
          <ArrowDownToLine className="h-4 w-4" />
          申请提现
        </button>
      </div>

      {/* 微信绑定状态 */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-card p-4 shadow-sm">
        <div className="flex items-center gap-3">
          <span
            className={cn(
              "flex h-10 w-10 items-center justify-center rounded-lg",
              wechat?.wechat_bound ? "bg-emerald-500/10 text-emerald-600" : "bg-muted text-muted-foreground"
            )}
          >
            {wechat?.wechat_bound ? <ShieldCheck className="h-5 w-5" /> : <Shield className="h-5 w-5" />}
          </span>
          <div>
            <p className="text-sm font-semibold text-foreground">
              {wechat?.wechat_bound ? "微信已绑定" : "未绑定微信"}
            </p>
            <p className="mt-0.5 text-xs text-muted-foreground">
              {wechat?.wechat_bound
                ? `提现收款账号 ${wechat.nickname || wechat.openid || "已绑定"}${wechat.bound_at ? ` · ${fmtDate(wechat.bound_at)}` : ""}`
                : "绑定微信后，审核通过的提现将由平台转账至您的微信零钱"}
            </p>
          </div>
        </div>
        {wechat?.wechat_bound ? (
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={handleUnbind}
              className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-input px-3 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
            >
              <Ban className="h-4 w-4" />
              解绑
            </button>
            <button
              type="button"
              onClick={handleBind}
              className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-input px-3 text-sm font-medium text-foreground transition-colors hover:bg-accent"
            >
              <RefreshCw className="h-4 w-4" />
              重新绑定
            </button>
          </div>
        ) : (
          <button
            type="button"
            onClick={handleBind}
            disabled={binding}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
          >
            {binding ? (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
            ) : (
              <ShieldCheck className="h-4 w-4" />
            )}
            绑定微信
          </button>
        )}
      </div>

      {/* 列表 */}
      {loading ? (
        <div className="flex items-center justify-center py-16">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : list.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-border bg-card py-16 text-sm text-muted-foreground">
          <Wallet className="h-8 w-8 opacity-40" />
          {t("common.noData")}
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
          <div className="divide-y divide-border">
            {list.map((w) => {
              const st = withdrawalStatusMap[w.status as WithdrawalStatus] || { label: w.status, cls: "bg-muted text-muted-foreground" }
              return (
                <div key={w.id} className="flex items-center justify-between gap-3 p-4">
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-semibold text-foreground">{fmtMoney(w.amount)}</p>
                    {w.account_info && (
                      <p className="mt-1 truncate text-xs text-muted-foreground" title={w.account_info}>
                        收款 {w.account_info}
                      </p>
                    )}
                    <p className="mt-0.5 text-xs text-muted-foreground">{fmtDate(w.created_at)}</p>
                    {w.reason && (
                      <p className="mt-0.5 text-xs text-red-500">原因：{w.reason}</p>
                    )}
                  </div>
                  <div className="shrink-0 text-right">
                    <span className={cn("inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium", st.cls)}>
                      {st.label}
                    </span>
                    {w.status === "PROCESSING" && w.package_info && (
                      <button
                        type="button"
                        onClick={() => confirmWithdrawal(w)}
                        className="mt-1.5 block w-full rounded-lg bg-emerald-500/10 px-2 py-1 text-xs font-semibold text-emerald-600 transition-colors hover:bg-emerald-500/20"
                      >
                        确认收款
                      </button>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {total > PAGE_SIZE && <Pager page={page} totalPages={totalPages} onChange={setPage} />}

      {modalOpen && (
        <WithdrawalModal
          balance={balance}
          wechatBound={!!wechat?.wechat_bound}
          onClose={() => setModalOpen(false)}
          onSuccess={() => { setModalOpen(false); fetchList() }}
        />
      )}
    </div>
  )
}

function WithdrawalModal({
  balance, wechatBound, onClose, onSuccess,
}: {
  balance: number
  wechatBound: boolean
  onClose: () => void
  onSuccess: () => void
}) {
  const { t } = useLocale()
  const [amount, setAmount] = useState("")
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!wechatBound) {
      toast.error("请先在分销中心绑定微信后再申请提现")
      return
    }
    const v = parseFloat(amount)
    if (Number.isNaN(v) || v <= 0) {
      toast.error("请输入有效的提现金额")
      return
    }
    if (v > balance) {
      toast.error("提现金额不能超过可提现余额")
      return
    }
    setSubmitting(true)
    try {
      await distributorApi.applyWithdrawal(v)
      toast.success("提现申请已提交")
      onSuccess()
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-bold text-foreground">申请提现</h2>
          <button
            type="button"
            onClick={onClose}
            className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="mb-4 rounded-lg bg-muted/40 p-3 text-sm">
          <p className="text-muted-foreground">可提现余额</p>
          <p className="mt-0.5 text-xl font-bold text-foreground">{fmtMoney(balance)}</p>
        </div>

        <form onSubmit={handleSubmit}>
          <label className="mb-1.5 block text-sm font-medium text-foreground">提现金额</label>
          <div className="relative">
            <span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">¥</span>
            <input
              type="number"
              min={0}
              step="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="请输入提现金额"
              className="h-11 w-full rounded-lg border border-input bg-background pl-8 pr-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              required
            />
          </div>
          {!wechatBound && (
            <div className="mt-3 flex items-start gap-2 rounded-lg bg-amber-500/10 p-3 text-xs text-amber-700">
              <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <p>尚未绑定微信。提现需转账至微信零钱，请先关闭弹窗并在页面中完成绑定微信。</p>
            </div>
          )}
          <p className="mt-1.5 text-xs text-muted-foreground">提现申请提交后，平台将审核并转账至您绑定的微信零钱</p>

          <div className="mt-6 flex justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              className="h-10 rounded-lg border border-input px-4 text-sm font-medium text-foreground hover:bg-accent"
            >
              {t("common.cancel")}
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
            >
              {submitting ? (
                <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
              ) : (
                <Check className="h-4 w-4" />
              )}
              确认提现
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ═══════════════════════ 我的下级 TAB ═══════════════════════

function SubordinatesTab() {
  const { t } = useLocale()
  const [list, setList] = useState<any[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const data = await distributorApi.listSubordinates({ page, page_size: PAGE_SIZE })
      setList((data as any)?.list || [])
      setTotal((data as any)?.pagination?.total ?? 0)
    } catch {
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [page])

  useEffect(() => { fetchList() }, [fetchList])

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted-foreground">共 {total} 位下级分销员</p>
      </div>

      {loading ? (
        <div className="flex items-center justify-center py-16">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : list.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-border bg-card py-16 text-sm text-muted-foreground">
          <Users2 className="h-8 w-8 opacity-40" />
          {t("common.noData")}
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
          <div className="divide-y divide-border">
            {list.map((s) => {
              const st = distributorStatusMap[s.status as DistributorStatus] || { label: s.status, cls: "bg-muted text-muted-foreground" }
              return (
                <div key={s.id} className="flex items-center justify-between gap-3 p-4">
                  <div className="flex min-w-0 flex-1 items-center gap-3">
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary/10">
                      <Users2 className="h-5 w-5 text-primary" />
                    </div>
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium text-foreground">{s.username || "—"}</p>
                      <p className="mt-0.5 text-xs text-muted-foreground">
                        累计佣金 {fmtMoney(s.total_commission)} · 加入于 {fmtDate(s.applied_at)}
                      </p>
                    </div>
                  </div>
                  <span className={cn("inline-flex shrink-0 rounded-full px-2.5 py-0.5 text-xs font-medium", st.cls)}>
                    {st.label}
                  </span>
                </div>
              )
            })}
          </div>
        </div>
      )}

      {total > PAGE_SIZE && <Pager page={page} totalPages={totalPages} onChange={setPage} />}
    </div>
  )
}

// ═══════════════════════ 分销规则弹窗 ═══════════════════════

interface RulesData {
  default_rate?: number
  default_sub_rate?: number
  settle_delay_days?: number
  min_withdraw_amount?: number
  withdraw_fee_rate?: number
  binding_protection_days?: number
  tier_enabled?: boolean
  sub_distribution_enabled?: boolean
  tiers?: { tier_order: number; rate: number; enabled?: boolean }[]
}

/** 系数（0.10）→ 百分比文案（10%） */
const fmtRate = (n: number | null | undefined) => {
  const v = ((Number(n) || 0) * 100)
  return `${Number.isInteger(v) ? v : v.toFixed(1)}%`
}

function RulesModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [rules, setRules] = useState<RulesData | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!open) return
    setLoading(true)
    distributorApi
      .getRules()
      .then((r) => setRules(r as RulesData))
      .catch(() => setRules(null))
      .finally(() => setLoading(false))
  }, [open])

  if (!open) return null

  const enabledTiers = (rules?.tiers || []).filter((t) => t.enabled !== false)
  const tierText = enabledTiers.length > 0
    ? enabledTiers.map((t) => ({ order: t.tier_order, pct: `${t.rate}%` }))
    : null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative flex max-h-[86vh] w-full max-w-2xl flex-col overflow-hidden rounded-xl border border-border bg-card shadow-2xl">
        {/* 头部 */}
        <div className="flex items-center justify-between border-b border-border px-6 py-4">
          <h2 className="flex items-center gap-2 text-lg font-bold text-foreground">
            <ScrollText className="h-5 w-5 text-primary" />
            分销推广规则
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
          {loading ? (
            <div className="flex items-center justify-center py-20">
              <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
            </div>
          ) : !rules ? (
            <div className="py-16 text-center text-sm text-muted-foreground">
              规则加载失败，请关闭后重试
            </div>
          ) : (
            <div className="flex flex-col gap-5">
              {/* 介绍 */}
              <div className="rounded-xl border border-primary/20 bg-primary/5 px-5 py-4">
                <p className="text-sm leading-relaxed text-foreground">
                  分享好物，赚取佣金。成为分销员后，通过专属推广链接分享商品，好友下单付款即可获得佣金。
                  佣金可在订单完成后结算提现至微信零钱。
                </p>
              </div>

              {/* 一、如何赚取佣金 */}
              <RuleSection icon={<Share2 className="h-4 w-4" />} title="一、如何赚取佣金">
                <ol className="flex list-decimal flex-col gap-2 pl-5">
                  <Step text="申请成为分销员，审核通过后获得专属推广员ID" />
                  <Step text="在「推广商品」页为单个商品或整个店铺生成专属推广链接 / 二维码" />
                  <Step text="将链接分享给好友，好友通过链接进入店铺并下单付款" />
                  <Step text="好友付款后产生佣金记录；订单完成并经过结算期后，佣金进入可提现余额" />
                  <Step text="在「提现记录」中申请提现，审核通过后打款至微信零钱" />
                </ol>
              </RuleSection>

              {/* 二、佣金计算 */}
              <RuleSection icon={<Percent className="h-4 w-4" />} title="二、佣金比例与计算">
                <ul className="flex flex-col gap-2 text-sm text-foreground">
                  <li>
                    <span className="font-medium">佣金 = 实际付款金额 × 佣金比例</span>
                  </li>
                  <li className="text-muted-foreground">
                    默认佣金比例为 <span className="font-semibold text-primary">{fmtRate(rules.default_rate)}</span>
                    ，部分商品或分销员可配置更高的专属比例
                  </li>
                  <li className="text-muted-foreground">
                    实际付款金额指扣除优惠券、积分抵扣后的实付金额
                  </li>
                  <li className="text-muted-foreground">
                    仅<span className="font-medium text-foreground">已付款</span>订单产生佣金，未付款订单不产生佣金
                  </li>
                </ul>
              </RuleSection>

              {/* 三、二级分销 */}
              <RuleSection icon={<Users className="h-4 w-4" />} title="三、二级分销（邀请下级）">
                <ul className="flex flex-col gap-2 text-sm">
                  <li className="text-foreground">
                    通过邀请码 / 邀请链接邀请好友加入分销，好友审核通过后成为你的下级分销员
                  </li>
                  {rules.sub_distribution_enabled ? (
                    <>
                      <li className="text-muted-foreground">
                        下级分销员每笔佣金，你将获得
                        <span className="font-semibold text-primary"> {fmtRate(rules.default_sub_rate)} </span>
                        的抽成
                      </li>
                      <li className="text-muted-foreground">
                        层级最多两级：下级佣金抽成给上级，不存在三级及以上抽成
                      </li>
                    </>
                  ) : (
                    <li className="text-muted-foreground">当前未开放二级分销（不邀请下级）</li>
                  )}
                </ul>
              </RuleSection>

              {/* 四、佣金结算 */}
              <RuleSection icon={<Coins className="h-4 w-4" />} title="四、佣金结算">
                <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                  <li>
                    订单付款后即生成佣金记录，状态为「待结算」
                  </li>
                  <li>
                    订单发货后 <span className="font-medium text-foreground">24 小时</span>
                    无其他操作自动完成
                  </li>
                  <li>
                    订单完成 <span className="font-medium text-primary">{rules.settle_delay_days} 天</span>
                    后，佣金自动结算进入可提现余额，无需手动操作
                  </li>
                  <li>
                    订单发生退款时，对应佣金自动取消，已结算的将从余额中扣回
                  </li>
                </ul>
              </RuleSection>

              {/* 五、提现规则 */}
              <RuleSection icon={<Wallet className="h-4 w-4" />} title="五、佣金提现">
                <ul className="flex flex-col gap-2 text-sm">
                  <li className="text-foreground">
                    结算后可提现余额达到
                    <span className="font-semibold text-primary"> ¥{Number(rules.min_withdraw_amount || 0).toFixed(2)}</span>
                    ，即可在「提现记录」中申请提现
                    {Number(rules.withdraw_fee_rate || 0) > 0 && (
                      <>，提现手续费 {fmtRate(rules.withdraw_fee_rate)}</>
                    )}
                    {Number(rules.withdraw_fee_rate || 0) === 0 && <span>，提现免手续费</span>}
                  </li>
                  <li className="text-muted-foreground">
                    提现前需先绑定本人微信（用于收款），提交申请后由平台审核，审核通过后打款至微信零钱
                  </li>
                  <li className="text-muted-foreground">
                    单笔提现上限 ¥20,000.00，如需大额可分批申请
                  </li>
                </ul>
              </RuleSection>

              {/* 六、客户绑定与保护期 */}
              <RuleSection icon={<HandCoins className="h-4 w-4" />} title="六、客户绑定与保护期">
                <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                  <li>
                    客户首次通过你的推广链接进入，即绑定为你名下客户
                  </li>
                  <li>
                    保护期 <span className="font-medium text-primary">{rules.binding_protection_days} 天</span>
                    内，该客户的所有订单均计入你的佣金
                  </li>
                  <li>
                    保护期过后，客户若通过其他推广员链接进入，可重新绑定归属
                  </li>
                </ul>
              </RuleSection>

              {/* 七、阶梯佣金 */}
              <RuleSection icon={<Layers className="h-4 w-4" />} title="七、阶梯佣金">
                {rules.tier_enabled && tierText && tierText.length > 0 ? (
                  <div className="flex flex-col gap-2">
                    <p className="text-sm text-muted-foreground">
                      同一客户在你名下多次购买时，按购买次数匹配对应的佣金比例：
                    </p>
                    <div className="overflow-hidden rounded-lg border border-border">
                      <table className="w-full text-sm">
                        <thead>
                          <tr className="border-b border-border bg-muted/30 text-left text-xs text-muted-foreground">
                            <th className="px-4 py-2 font-medium">购买次数</th>
                            <th className="px-4 py-2 font-medium">佣金比例</th>
                          </tr>
                        </thead>
                        <tbody>
                          {tierText.map((t) => (
                            <tr key={t.order} className="border-b border-border/50 last:border-0">
                              <td className="px-4 py-2 text-foreground">第 {t.order} 次</td>
                              <td className="px-4 py-2 font-medium text-primary">{t.pct}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    <p className="text-xs text-muted-foreground">
                      超过最高档次的购买不再返佣（以平台配置为准）
                    </p>
                  </div>
                ) : (
                  <p className="text-sm text-muted-foreground">
                    当前未启用阶梯佣金，同一客户每次购买均按标准比例计算佣金
                  </p>
                )}
              </RuleSection>

              {/* 七、推广注意 */}
              <RuleSection icon={<ShieldCheck className="h-4 w-4" />} title="七、推广注意">
                <ul className="flex flex-col gap-2 text-sm text-muted-foreground">
                  <li>
                    请通过正规渠道推广，严禁恶意刷单、自买自卖、违规推广等行为，一经发现将取消佣金并可能禁用分销账号
                  </li>
                  <li>
                    推广内容需真实合规，不得虚假宣传、夸大收益
                  </li>
                  <li>
                    规则如有调整，以分销中心最新公示为准
                  </li>
                </ul>
              </RuleSection>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function RuleSection({
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

function Step({ text }: { text: string }) {
  return <li className="text-sm text-foreground">{text}</li>
}

// ═══════════════════════ 分页器 ═══════════════════════

function Pager({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  const { t } = useLocale()
  return (
    <div className="flex items-center justify-center gap-2 pt-2">
      <button
        type="button"
        disabled={page <= 1}
        onClick={() => onChange(page - 1)}
        className="inline-flex h-9 items-center gap-1 rounded-md border border-input px-3 text-sm text-foreground transition-colors hover:bg-accent disabled:opacity-50"
      >
        <ChevronLeft className="h-4 w-4" />
        {t("common.prev")}
      </button>
      <span className="flex items-center px-3 text-sm text-muted-foreground">
        {page} / {totalPages}
      </span>
      <button
        type="button"
        disabled={page >= totalPages}
        onClick={() => onChange(page + 1)}
        className="inline-flex h-9 items-center gap-1 rounded-md border border-input px-3 text-sm text-foreground transition-colors hover:bg-accent disabled:opacity-50"
      >
        {t("common.next")}
        <ChevronRight className="h-4 w-4" />
      </button>
    </div>
  )
}

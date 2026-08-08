"use client"

import React from "react"
import { useState, useEffect } from "react"
import { useSearchParams } from "next/navigation"
import { User, Lock, Star, Eye, EyeOff, Save, Ticket, Copy } from "lucide-react"
import { toast } from "sonner"
import { useLocale } from "@/lib/context"
import { useAuth } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"
import { userApi, marketingApi, withMockFallback, getApiErrorMessage } from "@/services/api"
import { mockPointsData } from "@/lib/mock-data"
import type { PointRecord, MyCouponItem } from "@/types"
import { cn } from "@/lib/utils"

type Tab = "info" | "password" | "points" | "coupons"

export default function ProfilePage() {
  const { t } = useLocale()
  const currentUser = useRequireAuth()
  const { user } = useAuth()
  const searchParams = useSearchParams()
  const [activeTab, setActiveTab] = useState<Tab>("info")

  // 支持 ?tab=coupons 直达优惠券中心（如领取成功跳转）
  useEffect(() => {
    const tabParam = searchParams.get("tab")
    if (tabParam === "coupons" || tabParam === "points" || tabParam === "password" || tabParam === "info") {
      setActiveTab(tabParam as Tab)
    }
  }, [searchParams])

  const tabs: { key: Tab; label: string; icon: React.ReactNode }[] = [
    { key: "info", label: t("profile.info"), icon: <User className="h-4 w-4" /> },
    { key: "password", label: t("profile.changePassword"), icon: <Lock className="h-4 w-4" /> },
    { key: "points", label: t("profile.points"), icon: <Star className="h-4 w-4" /> },
    { key: "coupons", label: t("profile.couponTab"), icon: <Ticket className="h-4 w-4" /> },
  ]

  if (!currentUser) return null

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="mb-6 text-xl font-bold text-foreground">{t("profile.title")}</h1>

      {/* Tab Navigation */}
      <div className="mb-6 flex rounded-lg bg-muted p-1">
        {tabs.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            className={cn(
              "flex flex-1 items-center justify-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors",
              activeTab === tab.key
                ? "bg-background text-foreground shadow-sm"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            {tab.icon}
            <span className="hidden sm:inline">{tab.label}</span>
          </button>
        ))}
      </div>

      {/* Info Tab */}
      {activeTab === "info" && (
        <div className="rounded-lg border border-border bg-card p-5">
          <div className="flex flex-col gap-4">
            <div className="flex items-center gap-4">
              <div className="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
                <User className="h-8 w-8 text-primary" />
              </div>
              <div>
                <h2 className="text-lg font-semibold text-card-foreground">
                  {user?.username || "Guest"}
                </h2>
                <p className="text-sm text-muted-foreground">
                  {user?.email || "Not logged in"}
                </p>
              </div>
            </div>
            <hr className="border-border" />
            <div className="grid grid-cols-2 gap-4">
              <div className="rounded-lg bg-muted/50 p-3">
                <p className="text-xs text-muted-foreground">{t("profile.pointsBalance")}</p>
                <p className="text-2xl font-bold text-foreground">{user?.points || 0}</p>
              </div>
              <div className="rounded-lg bg-muted/50 p-3">
                <p className="text-xs text-muted-foreground">{t("profile.role")}</p>
                <p className="text-sm font-medium text-foreground">
                  {user?.role || "-"}
                </p>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Password Tab */}
      {activeTab === "password" && <ChangePasswordForm />}

      {/* Points Tab */}
      {activeTab === "points" && <PointsHistory />}

      {/* Coupons Tab */}
      {activeTab === "coupons" && <MyCoupons />}
    </div>
  )
}

function ChangePasswordForm() {
  const { t } = useLocale()
  const [form, setForm] = useState({
    oldPassword: "",
    newPassword: "",
    confirmNew: "",
  })
  const [showOld, setShowOld] = useState(false)
  const [showNew, setShowNew] = useState(false)
  const [isLoading, setIsLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (form.newPassword !== form.confirmNew) {
      toast.error(t("profile.passwordMismatch"))
      return
    }
    setIsLoading(true)
    try {
      await withMockFallback(
        () => userApi.updatePassword({
          old_password: form.oldPassword,
          new_password: form.newPassword,
        }),
        () => null
      )
      toast.success(t("common.success"))
      setForm({ oldPassword: "", newPassword: "", confirmNew: "" })
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("profile.oldPassword")}
          </label>
          <div className="relative">
            <input
              type={showOld ? "text" : "password"}
              value={form.oldPassword}
              onChange={(e) => setForm({ ...form, oldPassword: e.target.value })}
              className="h-10 w-full rounded-lg border border-input bg-background px-3 pr-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              required
            />
            <button
              type="button"
              onClick={() => setShowOld(!showOld)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            >
              {showOld ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </div>
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("profile.newPassword")}
          </label>
          <div className="relative">
            <input
              type={showNew ? "text" : "password"}
              value={form.newPassword}
              onChange={(e) => setForm({ ...form, newPassword: e.target.value })}
              className="h-10 w-full rounded-lg border border-input bg-background px-3 pr-10 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              required
            />
            <button
              type="button"
              onClick={() => setShowNew(!showNew)}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            >
              {showNew ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
            </button>
          </div>
        </div>
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("profile.confirmNew")}
          </label>
          <input
            type="password"
            value={form.confirmNew}
            onChange={(e) => setForm({ ...form, confirmNew: e.target.value })}
            className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            required
          />
        </div>
        <button
          type="submit"
          disabled={isLoading}
          className="inline-flex h-10 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
        >
          {isLoading ? (
            <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
          ) : (
            <Save className="h-4 w-4" />
          )}
          {t("profile.save")}
        </button>
      </form>
    </div>
  )
}

function PointsHistory() {
  const { t } = useLocale()
  const [records, setRecords] = useState<PointRecord[]>([])
  const [totalPoints, setTotalPoints] = useState(0)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    async function fetchPoints() {
      setLoading(true)
      try {
        const data = await withMockFallback(
          () => userApi.getPoints({ page: 1, page_size: 50 }),
          () => mockPointsData({ page: 1, page_size: 50 })
        )
        if (!cancelled) {
          setRecords(data.list)
          setTotalPoints(data.total_points)
        }
      } catch {
        if (!cancelled) {
          setRecords([])
          setTotalPoints(0)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    fetchPoints()
    return () => { cancelled = true }
  }, [])

  if (loading) {
    return (
      <div className="rounded-lg border border-border bg-card">
        <div className="flex items-center justify-center py-12">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      </div>
    )
  }

  return (
    <div className="rounded-lg border border-border bg-card">
      {/* Points summary */}
      <div className="border-b border-border p-4">
        <p className="text-xs text-muted-foreground">{t("profile.pointsBalance")}</p>
        <p className="text-2xl font-bold text-foreground">{totalPoints}</p>
      </div>

      <div className="divide-y divide-border">
        {records.map((record, idx) => (
          <div key={idx} className="flex items-center justify-between p-4">
            <div>
              <p className="text-sm text-card-foreground">{record.reason}</p>
              <p className="text-xs text-muted-foreground">
                {new Date(record.created_at).toLocaleString()}
              </p>
            </div>
            <div className="text-right">
              <p
                className={cn(
                  "text-sm font-semibold",
                  record.change_amount > 0
                    ? "text-emerald-600 dark:text-emerald-400"
                    : "text-foreground"
                )}
              >
                {record.change_amount > 0 ? "+" : ""}
                {record.change_amount}
              </p>
              <p className="text-xs text-muted-foreground">
                {t("profile.pointsBalance")}: {record.balance_after}
              </p>
            </div>
          </div>
        ))}
        {records.length === 0 && (
          <div className="py-12 text-center text-sm text-muted-foreground">
            {t("common.noData")}
          </div>
        )}
      </div>
    </div>
  )
}

// ═══════════════════════ 优惠券中心 ═══════════════════════
type CouponFilter = "ALL" | "CLAIMED" | "USED"

function MyCoupons() {
  const { t } = useLocale()
  const [filter, setFilter] = useState<CouponFilter>("ALL")
  const [list, setList] = useState<MyCouponItem[]>([])
  const [loading, setLoading] = useState(true)

  const fetchCoupons = async (f: CouponFilter) => {
    setLoading(true)
    try {
      const data = await marketingApi.myCoupons({
        status: f === "ALL" ? undefined : f,
        page: 1,
        page_size: 100,
      })
      setList(data.list)
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
      setList([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchCoupons(filter)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter])

  const copyCode = async (code: string) => {
    try {
      await navigator.clipboard.writeText(code)
      toast.success(t("profile.couponCopied"))
    } catch {
      toast.error(t("common.error"))
    }
  }

  const filters: { key: CouponFilter; label: string }[] = [
    { key: "ALL", label: t("profile.couponAll") },
    { key: "CLAIMED", label: t("profile.couponUnused") },
    { key: "USED", label: t("profile.couponUsed") },
  ]

  return (
    <div className="flex flex-col gap-4">
      <div className="flex rounded-lg bg-muted p-1">
        {filters.map(f => (
          <button
            key={f.key}
            type="button"
            onClick={() => setFilter(f.key)}
            className={cn(
              "flex flex-1 items-center justify-center rounded-md px-3 py-2 text-sm font-medium transition-colors",
              filter === f.key ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
            )}
          >
            {f.label}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="flex items-center justify-center rounded-lg border border-border bg-card py-16">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : list.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-border bg-card py-16 text-sm text-muted-foreground">
          <Ticket className="h-8 w-8 opacity-40" />
          {t("profile.couponNoData")}
        </div>
      ) : (
        <div className="grid gap-3 sm:grid-cols-2">
          {list.map(coupon => <CouponCard key={coupon.id} coupon={coupon} onCopy={copyCode} />)}
        </div>
      )}
    </div>
  )
}

function CouponCard({ coupon, onCopy }: { coupon: MyCouponItem; onCopy: (code: string) => void }) {
  const { t } = useLocale()
  const isAmount = coupon.type === "AMOUNT"
  const expired = coupon.status === "EXPIRED"
  const used = coupon.status === "USED"

  return (
    <div className={cn(
      "relative overflow-hidden rounded-xl border bg-card p-4",
      used ? "border-muted opacity-90" : expired ? "border-border opacity-60" : "border-primary/30"
    )}>
      {/* 已核销 / 已过期 印章 */}
      {(used || expired) && (
        <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <div className={cn(
            "rotate-[-18deg] rounded border-4 px-6 py-1 text-2xl font-black uppercase tracking-widest",
            used ? "border-emerald-500 text-emerald-500" : "border-muted-foreground/40 text-muted-foreground/60"
          )}>
            {used ? t("profile.couponStamped") : t("profile.couponExpired")}
          </div>
        </div>
      )}

      {/* 头部：标题 + 状态 */}
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-semibold text-foreground">{coupon.campaign_title}</h3>
          <p className="mt-0.5 text-xs text-muted-foreground">{t("profile.couponClaimedAt")}: {coupon.claimed_at ? new Date(coupon.claimed_at).toLocaleString() : "—"}</p>
        </div>
        <span className={cn(
          "shrink-0 rounded-full px-2 py-0.5 text-xs font-medium",
          used ? "bg-emerald-500/10 text-emerald-600" : expired ? "bg-muted text-muted-foreground" : "bg-primary/10 text-primary"
        )}>
          {used ? t("profile.couponUsed") : expired ? t("profile.couponExpired") : t("profile.couponUnused")}
        </span>
      </div>

      {/* 金额 */}
      <div className="mt-3 flex items-baseline gap-1">
        <span className="text-3xl font-bold text-foreground">
          {isAmount ? <><span className="text-xl">¥</span>{coupon.value}</> : <>{coupon.value}%</>}
        </span>
        <span className="text-sm text-muted-foreground">
          {isAmount ? t("profile.couponValue") : t("claim.valuePercent")}
        </span>
      </div>

      {/* 信息 */}
      <div className="mt-3 space-y-1.5 text-xs text-muted-foreground">
        <p className="flex items-center gap-1.5">
          <span className="inline-block w-16 shrink-0">{t("profile.couponScope")}</span>
          {coupon.scope === "SPECIFIC"
            ? <span className="text-foreground">{t("profile.couponScopeSpecific")}</span>
            : <span className="text-foreground">{t("profile.couponScopeAll")}</span>}
        </p>
        {coupon.coupon_min_amount > 0 && (
          <p className="flex items-center gap-1.5">
            <span className="inline-block w-16 shrink-0">{t("profile.couponMinAmount")}</span>
            <span className="text-foreground">¥{coupon.coupon_min_amount}</span>
          </p>
        )}
        <p className="flex items-center gap-1.5">
          <span className="inline-block w-16 shrink-0">{t("profile.couponValidFrom")}</span>
          <span className="text-foreground">{coupon.valid_from ? new Date(coupon.valid_from).toLocaleDateString() : "—"}</span>
        </p>
        <p className="flex items-center gap-1.5">
          <span className="inline-block w-16 shrink-0">{t("profile.couponValidTo")}</span>
          <span className="text-foreground">{coupon.valid_to ? new Date(coupon.valid_to).toLocaleDateString() : "—"}</span>
        </p>
        {used && coupon.used_at && (
          <p className="flex items-center gap-1.5">
            <span className="inline-block w-16 shrink-0">{t("profile.couponUsedAt")}</span>
            <span className="text-foreground">{new Date(coupon.used_at).toLocaleString()}</span>
          </p>
        )}
        {used && coupon.order_id && (
          <p className="flex items-center gap-1.5">
            <span className="inline-block w-16 shrink-0">{t("profile.couponOrder")}</span>
            <span className="truncate font-mono text-foreground">{coupon.order_id}</span>
          </p>
        )}
      </div>

      {/* 核销码 + 复制 */}
      <div className="mt-3 flex items-center justify-between gap-2 rounded-lg bg-muted/60 px-3 py-2">
        <div className="min-w-0">
          <p className="text-[11px] text-muted-foreground">{t("profile.couponCode")}</p>
          <p className="truncate font-mono text-sm font-semibold text-foreground">{coupon.code}</p>
        </div>
        {!used && (
          <button
            type="button"
            onClick={() => onCopy(coupon.code)}
            className="inline-flex h-8 shrink-0 items-center gap-1.5 rounded-md border border-input bg-background px-2.5 text-xs font-medium text-foreground transition-colors hover:bg-accent"
          >
            <Copy className="h-3.5 w-3.5" />
            {t("profile.couponCopy")}
          </button>
        )}
      </div>
    </div>
  )
}

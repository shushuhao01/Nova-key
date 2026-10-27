"use client"

import React from "react"
import { useState, useEffect, useCallback } from "react"
import Link from "next/link"
import { useSearchParams } from "next/navigation"
import { User, Lock, Star, Eye, EyeOff, Save, Ticket, Copy, UserPlus, Gift, Send, Phone, Mail, ChevronRight, Package } from "lucide-react"
import { toast } from "sonner"
import { useLocale } from "@/lib/context"
import { useAuth } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"
import { userApi, marketingApi, distributorApi, authApi, withMockFallback, getApiErrorMessage } from "@/services/api"
import { mockPointsData } from "@/lib/mock-data"
import type { PointRecord, MyCouponItem, OrderBrief } from "@/types"
import { cn } from "@/lib/utils"
import { Modal } from "@/components/ui/modal"
import { OrderCard } from "@/components/shared/order-card"

type Tab = "info" | "password" | "points" | "coupons" | "invite"
type BindType = "phone" | "email"

const PHONE_PATTERN = /^1[3-9]\d{9}$/
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export default function ProfilePage() {
  const { t } = useLocale()
  const currentUser = useRequireAuth()
  const { user, setUser } = useAuth()
  const searchParams = useSearchParams()
  const [activeTab, setActiveTab] = useState<Tab>("info")
  const [bindType, setBindType] = useState<BindType | null>(null)

  // 绑定成功后重新拉取个人资料（刷新手机号/邮箱与绑定状态）
  const refreshProfile = useCallback(async () => {
    try {
      const profile = await userApi.getProfile()
      setUser(profile)
    } catch {
      // 忽略刷新失败，弹窗内已提示绑定结果
    }
  }, [setUser])

  // 支持 ?tab=coupons 直达优惠券中心（如领取成功跳转）
  useEffect(() => {
    const tabParam = searchParams.get("tab")
    if (tabParam === "coupons" || tabParam === "points" || tabParam === "password" || tabParam === "info" || tabParam === "invite") {
      setActiveTab(tabParam as Tab)
    }
  }, [searchParams])

  // 绑定状态（兼容旧版本缓存中缺失 has_phone / has_email 的情况）
  const hasPhone = user?.has_phone ?? !!user?.phone
  const hasEmail = user?.has_email ?? !!user?.email

  const tabs: { key: Tab; label: string; icon: React.ReactNode }[] = [
    { key: "info", label: t("profile.info"), icon: <User className="h-4 w-4" /> },
    { key: "password", label: t("profile.changePassword"), icon: <Lock className="h-4 w-4" /> },
    { key: "points", label: t("profile.points"), icon: <Star className="h-4 w-4" /> },
    { key: "coupons", label: t("profile.couponTab"), icon: <Ticket className="h-4 w-4" /> },
    { key: "invite", label: t("profile.inviteTab"), icon: <UserPlus className="h-4 w-4" /> },
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
        <div className="space-y-4">
          {/* 未绑邮箱提示条 */}
          {user && !hasEmail && (
            <div className="flex flex-col gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 p-3 sm:flex-row sm:items-center sm:justify-between">
              <p className="flex items-start gap-2 text-sm text-amber-700 dark:text-amber-400">
                <Mail className="mt-0.5 h-4 w-4 shrink-0" />
                <span>{t("profile.emailUnboundTip")}</span>
              </p>
              <button
                onClick={() => setBindType("email")}
                className="shrink-0 rounded-md bg-amber-600 px-3 py-1.5 text-xs font-medium text-white transition-colors hover:bg-amber-700"
              >
                {t("profile.bindEmail")}
              </button>
            </div>
          )}

          <div className="rounded-lg border border-border bg-card p-5">
            <div className="flex flex-col gap-4">
              <div className="flex items-center gap-4">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-primary/10">
                  <User className="h-8 w-8 text-primary" />
                </div>
                <div>
                  <h2 className="text-lg font-semibold text-card-foreground">
                    {user?.username || "-"}
                  </h2>
                  {(user?.email || user?.phone) && (
                    <p className="text-sm text-muted-foreground">
                      {user?.email || user?.phone}
                    </p>
                  )}
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
              <hr className="border-border" />

              {/* 账号绑定：手机号 / 邮箱（已绑定不可解绑） */}
              <div className="flex flex-col gap-4">
                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-start gap-3">
                    <Phone className="mt-0.5 h-4 w-4 text-muted-foreground" />
                    <div>
                      <p className="text-sm font-medium text-foreground">{t("profile.bindPhoneTitle")}</p>
                      <p className="text-sm text-muted-foreground">
                        {hasPhone ? user?.phone : t("profile.notBound")}
                      </p>
                    </div>
                  </div>
                  {hasPhone ? (
                    <span className="shrink-0 rounded-full bg-emerald-500/10 px-2.5 py-1 text-xs font-medium text-emerald-600 dark:text-emerald-400">
                      {t("profile.boundNoUnbind")}
                    </span>
                  ) : (
                    <button
                      onClick={() => setBindType("phone")}
                      className="shrink-0 rounded-md border border-primary/40 px-3 py-1.5 text-xs font-medium text-primary transition-colors hover:bg-primary/10"
                    >
                      {t("profile.bindPhone")}
                    </button>
                  )}
                </div>

                <div className="flex items-center justify-between gap-3">
                  <div className="flex items-start gap-3">
                    <Mail className="mt-0.5 h-4 w-4 text-muted-foreground" />
                    <div>
                      <p className="text-sm font-medium text-foreground">{t("profile.bindEmailTitle")}</p>
                      <p className="text-sm text-muted-foreground">
                        {hasEmail ? user?.email : t("profile.notBound")}
                      </p>
                    </div>
                  </div>
                  {hasEmail ? (
                    <span className="shrink-0 rounded-full bg-emerald-500/10 px-2.5 py-1 text-xs font-medium text-emerald-600 dark:text-emerald-400">
                      {t("profile.boundNoUnbind")}
                    </span>
                  ) : (
                    <button
                      onClick={() => setBindType("email")}
                      className="shrink-0 rounded-md border border-primary/40 px-3 py-1.5 text-xs font-medium text-primary transition-colors hover:bg-primary/10"
                    >
                      {t("profile.bindEmail")}
                    </button>
                  )}
                </div>

                <p className="text-xs text-muted-foreground">{t("profile.bindHint")}</p>
              </div>
            </div>
          </div>

          {/* 最近购买记录：默认最新 5 条，可加载更多，右上角进入完整购买记录 */}
          <RecentOrders />
        </div>
      )}

      {/* Password Tab */}
      {activeTab === "password" && <ChangePasswordForm />}

      {/* Points Tab */}
      {activeTab === "points" && <PointsHistory />}

      {/* Coupons Tab */}
      {activeTab === "coupons" && <MyCoupons />}

      {/* Invite Binding Tab */}
      {activeTab === "invite" && <InviteBinding />}

      {/* 绑定手机号 / 邮箱弹窗 */}
      <BindAccountModal
        type={bindType}
        onClose={() => setBindType(null)}
        onSuccess={refreshProfile}
      />
    </div>
  )
}

function BindAccountModal({
  type,
  onClose,
  onSuccess,
}: {
  type: BindType | null
  onClose: () => void
  onSuccess: () => Promise<void> | void
}) {
  const { t } = useLocale()
  const [value, setValue] = useState("")
  const [password, setPassword] = useState("")
  const [showPassword, setShowPassword] = useState(false)
  const [isLoading, setIsLoading] = useState(false)

  // 每次打开 / 切换绑定类型时重置表单
  useEffect(() => {
    if (type) {
      setValue("")
      setPassword("")
      setShowPassword(false)
    }
  }, [type])

  // 格式校验：手机号 / 邮箱格式正确且填写了密码才允许点击绑定
  const trimmedValue = value.trim()
  const valueInvalid =
    trimmedValue.length > 0 &&
    (type === "phone" ? !PHONE_PATTERN.test(trimmedValue) : !EMAIL_PATTERN.test(trimmedValue))
  const canSubmit = !!type && trimmedValue.length > 0 && !valueInvalid && password.length > 0 && !isLoading

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!type) return
    const trimmed = value.trim()
    if (type === "phone" && !PHONE_PATTERN.test(trimmed)) {
      toast.error(t("profile.bindInvalidPhone"))
      return
    }
    if (type === "email" && !EMAIL_PATTERN.test(trimmed)) {
      toast.error(t("profile.bindInvalidEmail"))
      return
    }
    if (!password) {
      toast.error(t("profile.bindPasswordRequired"))
      return
    }
    setIsLoading(true)
    try {
      const res =
        type === "phone"
          ? await userApi.bindPhone({ phone: trimmed, password })
          : await userApi.bindEmail({ email: trimmed, password })
      if (res.idempotent) {
        toast.success(t("profile.bindIdempotent"))
      } else if (res.merged) {
        toast.success(t("profile.bindMergeSuccess"))
      } else {
        toast.success(t("profile.bindSuccess"))
      }
      await onSuccess()
      onClose()
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <Modal open={type !== null} onClose={onClose} className="max-w-md">
      <form onSubmit={handleSubmit} className="p-5">
        <h3 className="mb-1 text-base font-semibold text-foreground">
          {type === "email" ? t("profile.bindEmail") : t("profile.bindPhone")}
        </h3>
        <p className="mb-4 text-xs text-muted-foreground">{t("profile.bindMergeHint")}</p>

        <div className="space-y-3">
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {type === "email" ? t("profile.bindEmailTitle") : t("profile.bindPhoneTitle")}
            </label>
            <input
              type={type === "email" ? "email" : "tel"}
              inputMode={type === "email" ? "email" : "numeric"}
              maxLength={type === "phone" ? 11 : undefined}
              value={value}
              onChange={(e) =>
                setValue(type === "phone" ? e.target.value.replace(/\D/g, "") : e.target.value)
              }
              placeholder={
                type === "email" ? t("profile.bindEmailPlaceholder") : t("profile.bindPhonePlaceholder")
              }
              autoComplete={type === "email" ? "email" : "tel"}
              className={cn(
                "w-full rounded-md border bg-background px-3 py-2 text-sm text-foreground outline-none focus:border-primary",
                valueInvalid ? "border-destructive" : "border-border"
              )}
            />
            {valueInvalid && (
              <p className="mt-1 text-xs text-destructive">
                {type === "phone" ? t("profile.bindInvalidPhone") : t("profile.bindInvalidEmail")}
              </p>
            )}
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-muted-foreground">
              {t("profile.bindPassword")}
            </label>
            <div className="relative">
              <input
                type={showPassword ? "text" : "password"}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder={t("profile.bindPasswordPlaceholder")}
                autoComplete="current-password"
                className="w-full rounded-md border border-border bg-background px-3 py-2 pr-10 text-sm text-foreground outline-none focus:border-primary"
              />
              <button
                type="button"
                onClick={() => setShowPassword((v) => !v)}
                className="absolute right-2 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                tabIndex={-1}
              >
                {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>
        </div>

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-border px-3 py-2 text-sm text-foreground transition-colors hover:bg-muted"
          >
            {t("common.cancel")}
          </button>
          <button
            type="submit"
            disabled={!canSubmit}
            className="rounded-md bg-primary px-3 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
          >
            {isLoading ? t("common.loading") : t("profile.bindSubmit")}
          </button>
        </div>
      </form>
    </Modal>
  )
}

function ChangePasswordForm() {
  const { t } = useLocale()
  const { user } = useAuth()
  const [mode, setMode] = useState<"old" | "code">("old")
  const [form, setForm] = useState({
    oldPassword: "",
    newPassword: "",
    confirmNew: "",
  })
  const [code, setCode] = useState("")
  const [showOld, setShowOld] = useState(false)
  const [showNew, setShowNew] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const [codeSending, setCodeSending] = useState(false)
  const [countdown, setCountdown] = useState(0)
  const [expireMinutes, setExpireMinutes] = useState(10)

  const email = user?.email || ""

  // 重发倒计时
  useEffect(() => {
    if (countdown <= 0) return
    const timer = setTimeout(() => setCountdown((v) => v - 1), 1000)
    return () => clearTimeout(timer)
  }, [countdown])

  const handleSendCode = async () => {
    if (!email) {
      toast.error(t("auth.emailRequired"))
      return
    }
    setCodeSending(true)
    try {
      const res = await authApi.sendEmailCode({ email, scene: "PASSWORD_RESET" })
      setCountdown(res.resend_after_seconds || 60)
      setExpireMinutes(res.expire_minutes || 10)
      toast.success(t("auth.codeSent"))
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setCodeSending(false)
    }
  }

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (form.newPassword !== form.confirmNew) {
      toast.error(t("profile.passwordMismatch"))
      return
    }
    if (mode === "code") {
      if (!code.trim()) {
        toast.error(t("auth.codeRequired"))
        return
      }
      if (form.newPassword.length < 6) {
        toast.error(t("auth.passwordHint"))
        return
      }
    }
    setIsLoading(true)
    try {
      if (mode === "old") {
        await withMockFallback(
          () => userApi.updatePassword({
            old_password: form.oldPassword,
            new_password: form.newPassword,
          }),
          () => null
        )
      } else {
        await authApi.resetPassword({ email, code: code.trim(), new_password: form.newPassword })
      }
      toast.success(t("common.success"))
      setForm({ oldPassword: "", newPassword: "", confirmNew: "" })
      setCode("")
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      {/* 修改方式切换 */}
      <div className="mb-4 flex rounded-lg bg-muted p-1">
        <button
          type="button"
          onClick={() => setMode("old")}
          className={cn(
            "flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
            mode === "old" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
          )}
        >
          {t("profile.pwdByOld")}
        </button>
        <button
          type="button"
          onClick={() => setMode("code")}
          className={cn(
            "flex-1 rounded-md px-3 py-1.5 text-sm font-medium transition-colors",
            mode === "code" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
          )}
        >
          {t("profile.pwdByCode")}
        </button>
      </div>

      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        {mode === "old" ? (
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
        ) : (
          <>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.email")}
              </label>
              <input
                type="email"
                value={email}
                readOnly
                className="h-10 w-full rounded-lg border border-input bg-muted px-3 text-sm text-muted-foreground"
              />
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">
                {t("auth.emailCode")}
              </label>
              <div className="flex gap-2">
                <input
                  type="text"
                  inputMode="numeric"
                  maxLength={6}
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                  placeholder={t("auth.codePlaceholder")}
                  className="h-10 flex-1 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
                <button
                  type="button"
                  onClick={handleSendCode}
                  disabled={codeSending || countdown > 0}
                  className="inline-flex h-10 shrink-0 items-center justify-center gap-1.5 rounded-lg border border-input px-3 text-sm font-medium text-foreground hover:bg-muted disabled:opacity-50"
                >
                  <Send className="h-3.5 w-3.5" />
                  {countdown > 0 ? `${countdown}s` : t("auth.resend")}
                </button>
              </div>
              {countdown > 0 && email && (
                <p className="mt-1.5 text-xs text-muted-foreground">
                  {`${t("auth.codeSentTip")} ${email}（${expireMinutes} ${t("auth.codeValidTip")}）`}
                </p>
              )}
            </div>
          </>
        )}
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

// ═══════════════════════ 邀请码绑定推广员 ═══════════════════════

function InviteBinding() {
  const { t } = useLocale()
  const [binding, setBinding] = useState<any>(null)
  const [history, setHistory] = useState<any[]>([])
  const [code, setCode] = useState("")
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)

  const fetchBinding = useCallback(async () => {
    setLoading(true)
    try {
      const [data, hist] = await Promise.all([
        distributorApi.getCustomerBinding(),
        distributorApi.getCustomerBindingHistory(),
      ])
      setBinding(data)
      setHistory(hist?.bindings || [])
    } catch {
      setBinding(null)
      setHistory([])
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { fetchBinding() }, [fetchBinding])

  const handleBind = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!code.trim()) {
      toast.error(t("profile.inviteCodePlaceholder"))
      return
    }
    setSubmitting(true)
    try {
      await distributorApi.bindInvite(code.trim())
      toast.success(t("profile.inviteBindSuccess"))
      setCode("")
      fetchBinding()
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      {loading ? (
        <div className="flex items-center justify-center py-12">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : binding?.bound ? (
        <div className="flex flex-col gap-4">
          <div className="flex items-start gap-3 rounded-lg bg-emerald-500/10 p-4">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-emerald-500/15">
              <Gift className="h-5 w-5 text-emerald-600" />
            </span>
            <div className="min-w-0">
              <p className="text-sm font-semibold text-foreground">
                {t("profile.inviteBound")} · {binding.distributor_name || "—"}
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                {t("profile.inviteCode")}：
                <span className="font-mono font-semibold text-primary">{binding.invite_code || "—"}</span>
              </p>
              <p className="mt-0.5 text-xs text-muted-foreground">
                {t("profile.inviteBoundAt")}：{binding.bound_at ? new Date(binding.bound_at).toLocaleString() : "—"}
                {binding.protection_expires_at && (
                  <> · {t("profile.inviteProtectionUntil")}：{new Date(binding.protection_expires_at).toLocaleDateString()}</>
                )}
              </p>
            </div>
          </div>
          <p className="text-xs text-muted-foreground">{t("profile.inviteCodeHint")}</p>
        </div>
      ) : (
        <form onSubmit={handleBind} className="flex flex-col gap-4">
          <div className="flex items-start gap-3 rounded-lg bg-muted/50 p-4">
            <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10">
              <UserPlus className="h-5 w-5 text-primary" />
            </span>
            <div>
              <p className="text-sm font-semibold text-foreground">{t("profile.inviteUnbound")}</p>
              <p className="mt-0.5 text-xs text-muted-foreground">{t("profile.inviteCodeHint")}</p>
            </div>
          </div>
          <div>
            <label htmlFor="invite-code" className="mb-1.5 block text-sm font-medium text-foreground">
              {t("profile.inviteCode")}
            </label>
            <div className="flex gap-2">
              <input
                id="invite-code"
                type="text"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                placeholder={t("profile.inviteCodePlaceholder")}
                className="h-10 flex-1 rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <button
                type="submit"
                disabled={submitting}
                className="inline-flex h-10 shrink-0 items-center gap-2 rounded-lg bg-primary px-5 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
              >
                {submitting ? (
                  <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
                ) : (
                  <UserPlus className="h-4 w-4" />
                )}
                {t("profile.inviteBind")}
              </button>
            </div>
          </div>
        </form>
      )}

      {/* 绑定记录（含历史绑定与解绑时间） */}
      {history.length > 0 && (
        <div className="mt-4 flex flex-col gap-2">
          <div className="flex items-center justify-between">
            <p className="text-sm font-semibold text-foreground">{t("profile.bindingHistory")}</p>
            <p className="text-xs text-muted-foreground">{t("profile.bindingHistoryHint")}</p>
          </div>
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full text-xs">
              <thead className="bg-muted/50 text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 text-left font-medium">{t("profile.inviteBound")}</th>
                  <th className="px-3 py-2 text-left font-medium">{t("profile.inviteCode")}</th>
                  <th className="px-3 py-2 text-left font-medium">{t("profile.inviteBoundAt")}</th>
                  <th className="px-3 py-2 text-left font-medium">{t("profile.inviteUnboundAt")}</th>
                  <th className="px-3 py-2 text-left font-medium">状态</th>
                </tr>
              </thead>
              <tbody>
                {history.map((h, idx) => (
                  <tr key={idx} className="border-t border-border/50">
                    <td className="px-3 py-2 font-medium text-foreground">{h.distributor_name || "—"}</td>
                    <td className="px-3 py-2 font-mono text-primary">{h.invite_code || "—"}</td>
                    <td className="px-3 py-2 text-muted-foreground">{h.bound_at ? new Date(h.bound_at).toLocaleString() : "—"}</td>
                    <td className="px-3 py-2 text-muted-foreground">{h.unbound_at ? new Date(h.unbound_at).toLocaleString() : "—"}</td>
                    <td className="px-3 py-2">
                      {h.active ? (
                        <span className="inline-flex items-center rounded-full bg-emerald-500/10 px-2 py-0.5 font-medium text-emerald-600">
                          {t("profile.bindingStatusActive")}
                        </span>
                      ) : (
                        <span className="inline-flex items-center rounded-full bg-muted px-2 py-0.5 font-medium text-muted-foreground">
                          {t("profile.bindingStatusUnbound")}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}

/** 最近购买记录：默认展示最新 5 条，支持加载更多，右上角进入完整购买记录页 */
function RecentOrders() {
  const { t } = useLocale()
  const [orders, setOrders] = useState<OrderBrief[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const pageSize = 5

  useEffect(() => {
    let cancelled = false
    async function fetchFirstPage() {
      setLoading(true)
      try {
        const data = await userApi.getOrders({ page: 1, page_size: pageSize })
        if (!cancelled) {
          setOrders(data.list)
          setTotal(data.pagination.total)
          setPage(1)
        }
      } catch {
        if (!cancelled) {
          setOrders([])
          setTotal(0)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    fetchFirstPage()
    return () => {
      cancelled = true
    }
  }, [])

  const loadMore = async () => {
    const nextPage = page + 1
    setLoadingMore(true)
    try {
      const data = await userApi.getOrders({ page: nextPage, page_size: pageSize })
      setOrders((prev) => [...prev, ...data.list])
      setTotal(data.pagination.total)
      setPage(nextPage)
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setLoadingMore(false)
    }
  }

  const remaining = Math.max(0, total - orders.length)

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <div className="mb-4 flex items-start justify-between gap-3">
        <div>
          <h2 className="text-sm font-semibold text-card-foreground">{t("profile.recentOrders")}</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {t("profile.ordersTotal").replace("{count}", String(total))}
          </p>
        </div>
        <Link
          href="/my/orders"
          className="inline-flex shrink-0 items-center gap-0.5 text-xs font-medium text-primary hover:underline"
        >
          {t("profile.viewAll")}
          <ChevronRight className="h-3.5 w-3.5" />
        </Link>
      </div>

      {loading ? (
        <div className="flex items-center justify-center gap-2 py-8 text-sm text-muted-foreground">
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          {t("common.loading")}
        </div>
      ) : orders.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-2 py-8 text-muted-foreground">
          <Package className="h-8 w-8 opacity-40" />
          <p className="text-sm">{t("common.noData")}</p>
        </div>
      ) : (
        <div className="flex flex-col gap-3">
          {orders.map((order) => (
            <OrderCard key={order.id} order={order} />
          ))}
          {remaining > 0 && (
            <button
              onClick={loadMore}
              disabled={loadingMore}
              className="mt-1 inline-flex h-9 items-center justify-center gap-2 rounded-md border border-border text-xs font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
            >
              {loadingMore ? (
                <div className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              ) : null}
              {t("profile.loadMoreRemaining").replace("{count}", String(remaining))}
            </button>
          )}
        </div>
      )}
    </div>
  )
}

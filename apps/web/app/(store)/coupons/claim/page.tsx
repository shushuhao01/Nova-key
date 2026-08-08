"use client"

import { useState, useEffect, Suspense } from "react"
import Link from "next/link"
import { useSearchParams } from "next/navigation"
import { Ticket, CheckCircle2, LogIn, CalendarDays, Tag, Ban } from "lucide-react"
import { toast } from "sonner"
import { useLocale, useAuth } from "@/lib/context"
import { marketingApi, getApiErrorMessage } from "@/services/api"
import type { CouponInfo } from "@/types"
import { cn } from "@/lib/utils"

export default function CouponClaimPage() {
  return (
    <Suspense fallback={<ClaimSkeleton />}>
      <ClaimContent />
    </Suspense>
  )
}

function ClaimSkeleton() {
  return (
    <div className="mx-auto flex min-h-[60vh] w-full max-w-md items-center justify-center">
      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
    </div>
  )
}

function ClaimContent() {
  const { t } = useLocale()
  const { isLoggedIn } = useAuth()
  const searchParams = useSearchParams()
  const code = searchParams.get("code") || ""

  const [info, setInfo] = useState<CouponInfo | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState("")
  const [claiming, setClaiming] = useState(false)
  const [claimed, setClaimed] = useState(false)

  useEffect(() => {
    if (!code) {
      setLoadError(t("claim.notFound"))
      setLoading(false)
      return
    }
    let cancelled = false
    marketingApi.info(code)
      .then(data => { if (!cancelled) setInfo(data) })
      .catch(err => { if (!cancelled) setLoadError(getApiErrorMessage(err, t)) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [code, t])

  const handleClaim = async () => {
    if (!code || claiming) return
    setClaiming(true)
    try {
      await marketingApi.claim({ code })
      setClaimed(true)
      toast.success(t("claim.claimed"))
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setClaiming(false)
    }
  }

  const expired = info ? (info.coupon_valid_to && new Date(info.coupon_valid_to).getTime() < Date.now()) : false
  const notStarted = info ? (info.coupon_valid_from && new Date(info.coupon_valid_from).getTime() > Date.now()) : false
  const soldOut = info ? (info.coupon_quantity > 0 && info.coupon_claimed >= info.coupon_quantity) : false
  const blocked = !!info && (info.is_canceled === 1 || expired || notStarted || soldOut)

  const loginRedirect = `/login?redirect=${encodeURIComponent(`/coupons/claim?code=${encodeURIComponent(code)}`)}`

  if (loading) return <ClaimSkeleton />

  return (
    <div className="mx-auto flex min-h-[60vh] w-full max-w-md items-center justify-center">
      <div className="w-full">
        <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
          {/* 头部色带 */}
          <div className="flex items-center justify-between bg-primary px-5 py-4">
            <h1 className="flex items-center gap-2 text-lg font-bold text-primary-foreground">
              <Ticket className="h-5 w-5" />
              {t("claim.title")}
            </h1>
            <span className="rounded-full bg-primary-foreground/20 px-2.5 py-0.5 font-mono text-xs text-primary-foreground">
              {code}
            </span>
          </div>

          <div className="flex flex-col gap-4 p-5">
            {loadError || !info ? (
              <div className="flex flex-col items-center gap-3 py-10 text-center">
                <Ban className="h-10 w-10 text-muted-foreground/40" />
                <p className="text-sm text-muted-foreground">{loadError || t("claim.notFound")}</p>
              </div>
            ) : (
              <>
                {/* 优惠券信息 */}
                <div>
                  <h2 className="text-lg font-bold text-foreground">{info.title}</h2>
                  <div className="mt-2 flex items-baseline gap-1">
                    <span className="text-4xl font-black text-primary">
                      {info.coupon_type === "AMOUNT" ? (
                        <><span className="text-2xl">¥</span>{info.coupon_value}</>
                      ) : (
                        <>{info.coupon_value}%</>
                      )}
                    </span>
                    <span className="text-sm text-muted-foreground">
                      {info.coupon_type === "AMOUNT" ? t("claim.valueAmount") : t("claim.valuePercent")}
                    </span>
                  </div>
                </div>

                <div className="space-y-2 rounded-lg bg-muted/40 p-3 text-sm">
                  {info.coupon_min_amount > 0 && (
                    <p className="flex items-center gap-2 text-muted-foreground">
                      <Tag className="h-4 w-4 shrink-0" />
                      {t("claim.minAmount")}: <span className="text-foreground">¥{info.coupon_min_amount}</span>
                    </p>
                  )}
                  <p className="flex items-center gap-2 text-muted-foreground">
                    <Tag className="h-4 w-4 shrink-0" />
                    {t("claim.scope")}:{" "}
                    <span className="text-foreground">
                      {info.coupon_scope === "SPECIFIC" ? t("profile.couponScopeSpecific") : t("profile.couponScopeAll")}
                    </span>
                  </p>
                  <p className="flex items-center gap-2 text-muted-foreground">
                    <CalendarDays className="h-4 w-4 shrink-0" />
                    {t("claim.validPeriod")}:{" "}
                    <span className="text-foreground">
                      {info.coupon_valid_from ? new Date(info.coupon_valid_from).toLocaleDateString() : "—"}
                      {" "}{t("claim.to")}{" "}
                      {info.coupon_valid_to ? new Date(info.coupon_valid_to).toLocaleDateString() : "—"}
                    </span>
                  </p>
                  {info.coupon_quantity > 0 && (
                    <p className="flex items-center gap-2 text-muted-foreground">
                      <Ticket className="h-4 w-4 shrink-0" />
                      {t("admin.couponQuantity")}:{" "}
                      <span className="text-foreground">{info.coupon_claimed} / {info.coupon_quantity}</span>
                    </p>
                  )}
                </div>

                {/* 状态提示 */}
                {info.is_canceled === 1 && (
                  <p className="rounded-lg bg-amber-500/10 px-3 py-2 text-center text-sm text-amber-600">{t("claim.canceled")}</p>
                )}
                {info.is_canceled !== 1 && expired && (
                  <p className="rounded-lg bg-muted px-3 py-2 text-center text-sm text-muted-foreground">{t("claim.expired")}</p>
                )}
                {info.is_canceled !== 1 && notStarted && (
                  <p className="rounded-lg bg-muted px-3 py-2 text-center text-sm text-muted-foreground">{t("claim.notStarted")}</p>
                )}
                {info.is_canceled !== 1 && soldOut && (
                  <p className="rounded-lg bg-muted px-3 py-2 text-center text-sm text-muted-foreground">{t("claim.soldOut")}</p>
                )}

                {/* 操作区 */}
                {claimed ? (
                  <Link
                    href="/profile?tab=coupons"
                    className="inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90"
                  >
                    <CheckCircle2 className="h-4 w-4" />
                    {t("claim.viewMyCoupons")}
                  </Link>
                ) : blocked ? (
                  <Link
                    href={loginRedirect}
                    className="inline-flex h-11 items-center justify-center gap-2 rounded-lg border border-input text-sm font-semibold text-foreground transition-colors hover:bg-accent"
                  >
                    <LogIn className="h-4 w-4" />
                    {t("claim.goLogin")}
                  </Link>
                ) : !isLoggedIn ? (
                  <div className="flex flex-col gap-2">
                    <p className="text-center text-xs text-muted-foreground">{t("claim.loginFirst")}</p>
                    <Link
                      href={loginRedirect}
                      className="inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90"
                    >
                      <LogIn className="h-4 w-4" />
                      {t("claim.goLogin")}
                    </Link>
                  </div>
                ) : (
                  <button
                    type="button"
                    disabled={claiming}
                    onClick={handleClaim}
                    className={cn(
                      "inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:pointer-events-none disabled:opacity-50"
                    )}
                  >
                    {claiming ? (
                      <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
                    ) : (
                      <Ticket className="h-4 w-4" />
                    )}
                    {claiming ? t("claim.claiming") : t("claim.claimNow")}
                  </button>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}

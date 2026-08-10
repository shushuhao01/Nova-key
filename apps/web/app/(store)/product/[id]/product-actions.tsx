"use client"

import { useState, useMemo, useRef, useEffect, useCallback } from "react"
import { useRouter } from "next/navigation"
import { Zap, Minus, Plus, ShoppingCart, Package, TrendingUp, Ticket, CheckCircle2, XCircle, Loader2, ChevronDown } from "lucide-react"
import { toast } from "sonner"
import { useLocale, useAuth, useCart } from "@/lib/context"
import { orderApi, marketingApi, withMockFallback, getApiErrorMessage, setTurnstileHeaders } from "@/services/api"
import { mockCreateOrder } from "@/lib/mock-data"
import { Turnstile, useTurnstile } from "@/components/shared/turnstile"
import { cn, validateEmail, generateIdempotencyKey, getCurrencySymbol, detectPaymentDevice, isMobileDevice } from "@/lib/utils"
import { PaymentSelector } from "@/components/shared/payment-selector"
import { ShareCommissionButton } from "@/components/store/share-commission"
import type { ProductDetail, ProductSpec, PaymentChannelItem, MyCouponItem } from "@/types"

interface ProductActionsProps {
  product: ProductDetail
  channels: PaymentChannelItem[]
}

export function ProductActions({ product, channels }: ProductActionsProps) {
  const { t } = useLocale()
  const { isLoggedIn } = useAuth()
  const { addItem } = useCart()
  const router = useRouter()
  const emailInputRef = useRef<HTMLInputElement>(null)

  const enabledChannels = useMemo(() => channels.filter(c => c.is_enabled), [channels])

  const [selectedSpec, setSelectedSpec] = useState<ProductSpec | null>(
    product.specs?.[0] || null
  )
  const [quantity, setQuantity] = useState(1)
  const [email, setEmail] = useState("")
  const [emailError, setEmailError] = useState("")
  const [selectedPayment, setSelectedPayment] = useState(
    enabledChannels.length > 0 ? enabledChannels[0].channel_code : ""
  )
  const [submitting, setSubmitting] = useState(false)
  const { turnstileToken, setTurnstileToken, handleTurnstileReset } = useTurnstile()

  // 优惠券（选填）
  const [couponCode, setCouponCode] = useState("")
  const [couponStatus, setCouponStatus] = useState<"idle" | "checking" | "valid" | "invalid">("idle")
  const [couponDiscount, setCouponDiscount] = useState(0)
  const [couponMessage, setCouponMessage] = useState("")
  // 我的可用优惠券（登录后商品详情页下拉选择，无需手动复制核销码）
  const [myCoupons, setMyCoupons] = useState<MyCouponItem[]>([])
  const [couponsLoading, setCouponsLoading] = useState(false)

  const currentPrice = selectedSpec ? selectedSpec.price : product.base_price
  const totalPrice = currentPrice * quantity
  const couponPayable = Math.max(0, totalPrice - couponDiscount)
  const currentStock = selectedSpec?.stock_available ?? product.stock_available ?? 0
  const isOutOfStock = currentStock === 0
  const deliveryType = product.delivery_type === "MANUAL" ? "manual" : "auto"

  // 登录后加载我的可用优惠券（CLAIMED 未核销）
  useEffect(() => {
    let cancelled = false
    if (!isLoggedIn) {
      setMyCoupons([])
      return
    }
    setCouponsLoading(true)
    marketingApi.myCoupons({ status: "CLAIMED", page_size: 50 })
      .then(res => { if (!cancelled) setMyCoupons(res.list ?? []) })
      .catch(() => { if (!cancelled) setMyCoupons([]) })
      .finally(() => { if (!cancelled) setCouponsLoading(false) })
    return () => { cancelled = true }
  }, [isLoggedIn])

  // 过滤符合当前商品/订单条件的可用券：未过期、满减门槛、适用范围
  const usableCoupons = useMemo(() => {
    const now = Date.now()
    return myCoupons.filter(c => {
      if (c.valid_to && new Date(c.valid_to).getTime() < now) return false
      if (c.coupon_min_amount > 0 && totalPrice < c.coupon_min_amount) return false
      if (c.scope === "SPECIFIC" && !c.product_ids.includes(product.id)) return false
      return true
    })
  }, [myCoupons, totalPrice, product.id])

  const couponLabel = (c: MyCouponItem) => {
    const disc = c.type === "AMOUNT" ? `${getCurrencySymbol(product.currency)}${c.value}` : `减免 ${c.value}%`
    const min = c.coupon_min_amount > 0 ? `（满${getCurrencySymbol(product.currency)}${c.coupon_min_amount}）` : ""
    return `${c.campaign_title || "优惠券"} · ${disc}${min} · ${c.code}`
  }

  const applyCouponCode = useCallback(async (codeToApply: string) => {
    const code = codeToApply.trim()
    if (!code) {
      setCouponStatus("idle")
      setCouponDiscount(0)
      setCouponMessage("")
      return
    }
    setCouponStatus("checking")
    try {
      const result = await withMockFallback(
        () => marketingApi.validate({ code, email: email.trim() || undefined, amount: totalPrice, product_ids: [product.id] }),
        () => ({ valid: true, discount: Math.min(5, totalPrice), coupon_type: "AMOUNT" as const, coupon_value: 5 })
      )
      if (result.valid && (result.discount ?? 0) > 0) {
        setCouponStatus("valid")
        setCouponDiscount(result.discount ?? 0)
        setCouponMessage(result.message || t("product.couponValid"))
      } else {
        setCouponStatus("invalid")
        setCouponDiscount(0)
        setCouponMessage(result.message || t("product.couponInvalid"))
      }
    } catch (err: unknown) {
      setCouponStatus("invalid")
      setCouponDiscount(0)
      setCouponMessage(getApiErrorMessage(err, t) || t("product.couponInvalid"))
    }
  }, [email, totalPrice, product.id, t])

  const handleApplyCoupon = () => applyCouponCode(couponCode)

  const resetCoupon = () => {
    setCouponCode("")
    setCouponStatus("idle")
    setCouponDiscount(0)
    setCouponMessage("")
  }

  const handleEmailChange = (value: string) => {
    setEmail(value)
    if (value && !validateEmail(value)) {
      setEmailError(t("product.emailInvalid"))
    } else {
      setEmailError("")
    }
  }

  const handleBuyNow = async () => {
    if (!email.trim()) {
      toast.error(t("product.emailRequired"))
      emailInputRef.current?.focus()
      emailInputRef.current?.scrollIntoView({ behavior: "smooth", block: "center" })
      return
    }
    if (!validateEmail(email)) {
      toast.error(t("product.emailInvalid"))
      emailInputRef.current?.focus()
      return
    }
    if (!selectedPayment) {
      toast.error(t("product.paymentMethod"))
      return
    }
    if (product.specs.length > 0 && !selectedSpec) return
    if (isOutOfStock) {
      toast.error(t("product.outOfStock"))
      return
    }

    setSubmitting(true)
    try {
      setTurnstileHeaders(turnstileToken)
      const device = detectPaymentDevice()
      const result = await withMockFallback(
        () => orderApi.create({
          product_id: product.id,
          spec_id: selectedSpec?.id ?? null,
          quantity,
          email,
          payment_method: selectedPayment,
          idempotency_key: generateIdempotencyKey(),
          device,
          coupon_code: couponStatus === "valid" ? couponCode.trim() : undefined,
        }),
        () => mockCreateOrder(email, selectedPayment)
      )
      toast.success(t("checkout.processingOrder"))
      // 0 元订单（优惠券全额抵扣后无需支付）：直接跳转支付页，服务端订单状态已为 PAID，会自动展示已发货
      if (!result.payment) {
        router.push(`/pay/${result.order.id}?method=${selectedPayment}`)
        return
      }
      const payUrlH5 = result.payment.pay_url || ""
      const qr = result.payment.qrcode_url || result.payment.payment_url || ""
      let payUrl = `/pay/${result.payment.order_id}?method=${selectedPayment}`
      if (qr) payUrl += `&qr=${encodeURIComponent(qr)}`
      if (payUrlH5) payUrl += `&payurl=${encodeURIComponent(payUrlH5)}`
      // USDT 支付额外参数
      if (result.payment.wallet_address) {
        payUrl += `&wallet=${encodeURIComponent(result.payment.wallet_address)}`
        payUrl += `&crypto_amount=${encodeURIComponent(result.payment.crypto_amount || "")}`
        payUrl += `&chain=${encodeURIComponent(result.payment.chain || "")}`
      }
      // 移动端非 USDT 非微信：直接跳转网关支付页，避免中间经过 pay 页面的延迟
      // 导致支付宝 H5 session token 过期（"会话超时"）
      // 微信支付的 jspay 走 JSAPI（需微信浏览器），普通浏览器不能跳转，只能到 pay 页展示二维码
      const isWechat = ["wechat", "wxpay"].includes(selectedPayment.toLowerCase())
      if (isMobileDevice() && payUrlH5 && !selectedPayment.startsWith("usdt_") && !isWechat) {
        sessionStorage.setItem(`pay_redirected_${result.payment.order_id}`, "1")
        window.location.href = payUrlH5
        return
      }
      router.push(payUrl)
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
      handleTurnstileReset()
    } finally {
      setSubmitting(false)
    }
  }

  const handleAddToCart = async () => {
    if (product.specs.length > 0 && !selectedSpec) return
    if (isOutOfStock) {
      toast.error(t("product.outOfStock"))
      return
    }
    try {
      await addItem({
        product_id: product.id,
        spec_id: selectedSpec?.id ?? null,
        quantity,
      })
      toast.success(t("product.addToCart"))
    } catch (err: unknown) {
      toast.error(getApiErrorMessage(err, t))
    }
  }

  return (
    <div className="lg:sticky lg:top-4 flex flex-col gap-4">
      {/* Title */}
      <div>
        <h1 className="text-xl font-bold text-foreground">
          {product.title}
        </h1>
      </div>

      {/* Price + Specs + Stock */}
      <div className="rounded-lg border border-border p-4 space-y-4">
        {/* Price row + delivery status */}
        <div className="flex flex-wrap items-baseline justify-between gap-y-2">
          <div className="flex items-baseline gap-3">
            <div className="flex items-baseline gap-0.5">
              <span className="text-lg font-extrabold text-primary">{getCurrencySymbol(product.currency)}</span>
              <span className="text-2xl font-extrabold text-primary">
                {currentPrice.toFixed(2)}
              </span>
            </div>
          </div>

          {/* Delivery status indicator */}
          <div className={cn(
            "inline-flex items-center gap-1.5 rounded-full border px-3 py-1",
            deliveryType === "auto"
              ? "border-emerald-200 bg-emerald-50 dark:border-emerald-800 dark:bg-emerald-900/20"
              : "border-amber-200 bg-amber-50 dark:border-amber-800 dark:bg-amber-900/20"
          )}>
            <span className={cn(
              "relative inline-flex h-2 w-2 rounded-full",
              deliveryType === "auto" ? "bg-emerald-500" : "bg-amber-400"
            )}>
              {deliveryType === "auto" && (
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-75" />
              )}
            </span>
            <span className={cn(
              "text-xs font-semibold",
              deliveryType === "auto"
                ? "text-emerald-700 dark:text-emerald-300"
                : "text-amber-600 dark:text-amber-300"
            )}>
              {deliveryType === "auto" ? t("product.deliveryAuto") : t("product.deliveryManual")}
            </span>
          </div>
        </div>

        {/* Stock + Sales */}
        <div className="flex items-center gap-4 text-sm">
          <span className="flex items-center gap-1.5 text-muted-foreground">
            <Package className="h-3.5 w-3.5" />
            {t("product.stock")} {selectedSpec?.stock_available ?? product.stock_available}
          </span>
          {((product.sales_count ?? 0) + (product.initial_sales ?? 0)) > 0 && (
            <span className="flex items-center gap-1.5 text-muted-foreground">
              <TrendingUp className="h-3.5 w-3.5" />
              {t("product.sold")} {(product.sales_count ?? 0) + (product.initial_sales ?? 0)}
            </span>
          )}
        </div>

        {/* Spec selection */}
        {product.specs && product.specs.length > 1 && (
          <div>
            <label className="mb-2 block text-sm font-medium text-foreground">
              {t("product.selectSpec")}
            </label>
            <div className="flex flex-wrap gap-2">
              {product.specs.map((spec) => (
                <button
                  key={spec.id}
                  onClick={() => {
                    setSelectedSpec(spec)
                    setQuantity(1)
                    resetCoupon()
                  }}
                  className={cn(
                    "rounded-md border px-3 py-1.5 text-sm font-medium transition-colors",
                    selectedSpec?.id === spec.id
                      ? "border-primary bg-primary/10 text-primary"
                      : "border-border text-foreground hover:border-primary/30"
                  )}
                  disabled={spec.stock_available === 0}
                >
                  {spec.name}
                  {spec.stock_available === 0 && (
                    <span className="ml-1 text-xs text-muted-foreground">
                      ({t("product.outOfStock")})
                    </span>
                  )}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Action area */}
      <div className="rounded-lg border border-border p-4 space-y-4">
        {/* Quantity */}
        <div>
          <label className="mb-2 block text-sm font-medium text-foreground">
            {t("product.quantity")}
          </label>
          <div className="inline-flex items-center rounded-md border border-border">
            <button
              onClick={() => {
                setQuantity(Math.max(1, quantity - 1))
                resetCoupon()
              }}
              className="inline-flex h-9 w-9 items-center justify-center text-muted-foreground transition-colors hover:bg-accent"
              disabled={quantity <= 1}
            >
              <Minus className="h-4 w-4" />
            </button>
            <input
              type="number"
              min={1}
              max={currentStock || 1}
              value={quantity}
              onChange={(e) => {
                const v = parseInt(e.target.value) || 1
                setQuantity(Math.min(v, currentStock || 1))
                resetCoupon()
              }}
              className="h-9 w-16 border-x border-border bg-background text-center text-sm text-foreground [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
            />
            <button
              onClick={() => {
                setQuantity(Math.min(quantity + 1, currentStock || 1))
                resetCoupon()
              }}
              className="inline-flex h-9 w-9 items-center justify-center text-muted-foreground transition-colors hover:bg-accent"
              disabled={quantity >= currentStock}
            >
              <Plus className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* Email input */}
        <div>
          <label className="mb-1.5 block text-sm font-medium text-foreground">
            {t("product.email")}
          </label>
          <input
            ref={emailInputRef}
            type="email"
            placeholder={t("product.emailPlaceholder")}
            value={email}
            onChange={(e) => handleEmailChange(e.target.value)}
            className={cn(
              "h-10 w-full rounded-lg border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring",
              emailError ? "border-destructive" : "border-input"
            )}
          />
          <div className="mt-1.5">
            <p className="text-xs text-muted-foreground">
              {t("product.emailFullHint")}
            </p>
            {emailError && (
              <p className="mt-1 text-xs text-destructive">{emailError}</p>
            )}
          </div>
        </div>

        {/* Payment method */}
        <div>
          <label className="mb-2 block text-sm font-medium text-foreground">
            {t("product.paymentMethod")}
          </label>
          <PaymentSelector
            channels={enabledChannels}
            selected={selectedPayment}
            onSelect={setSelectedPayment}
          />
        </div>

        {/* Coupon (optional) */}
        <div>
          <label className="mb-2 flex items-center gap-1.5 text-sm font-medium text-foreground">
            <Ticket className="h-4 w-4 text-primary" />
            {t("product.coupon")}
          </label>
          <div className="flex gap-2">
            <input
              type="text"
              placeholder={t("product.couponPlaceholder")}
              value={couponCode}
              onChange={(e) => {
                setCouponCode(e.target.value)
                setCouponStatus("idle")
                setCouponDiscount(0)
                setCouponMessage("")
              }}
              className={cn(
                "h-10 flex-1 rounded-lg border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring",
                couponStatus === "valid" ? "border-emerald-500" : couponStatus === "invalid" ? "border-destructive" : "border-input"
              )}
            />
            <button
              type="button"
              onClick={handleApplyCoupon}
              disabled={couponStatus === "checking" || !couponCode.trim()}
              className="inline-flex h-10 items-center gap-1.5 rounded-lg border border-primary bg-primary/10 px-4 text-sm font-medium text-primary transition-colors hover:bg-primary/20 disabled:pointer-events-none disabled:opacity-50"
            >
              {couponStatus === "checking" ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <CheckCircle2 className="h-4 w-4" />
              )}
              {couponStatus === "checking" ? t("product.couponChecking") : t("product.couponApply")}
            </button>
          </div>
          {/* 我的可用优惠券（登录后可选，无需手动复制核销码） */}
          {isLoggedIn && usableCoupons.length > 0 && (
            <div className="mt-2">
              <select
                value=""
                onChange={(e) => {
                  const code = e.target.value
                  if (!code) return
                  setCouponCode(code)
                  setCouponStatus("idle")
                  applyCouponCode(code)
                }}
                className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              >
                <option value="" disabled>{t("product.myCouponSelect")}</option>
                {usableCoupons.map(c => (
                  <option key={c.id} value={c.code}>{couponLabel(c)}</option>
                ))}
              </select>
              <p className="mt-1 text-xs text-muted-foreground">{t("product.myCouponHint")}</p>
            </div>
          )}
          {couponsLoading && isLoggedIn && (
            <p className="mt-1.5 flex items-center gap-1 text-xs text-muted-foreground">
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
              {t("product.myCouponLoading")}
            </p>
          )}
          {couponStatus === "valid" && (
            <p className="mt-1.5 flex items-center gap-1 text-xs text-emerald-600">
              <CheckCircle2 className="h-3.5 w-3.5" />
              {couponMessage}（{t("product.couponDiscount")} {getCurrencySymbol(product.currency)}{couponDiscount.toFixed(2)}）
            </p>
          )}
          {couponStatus === "invalid" && (
            <p className="mt-1.5 flex items-center gap-1 text-xs text-destructive">
              <XCircle className="h-3.5 w-3.5" />
              {couponMessage || t("product.couponInvalid")}
            </p>
          )}
        </div>

        {/* Total */}
        <div className="space-y-1.5 border-t border-border pt-4">
          <div className="flex items-baseline justify-between">
            <span className="text-sm text-muted-foreground">{t("product.totalPrice")}</span>
            <div className="flex items-baseline gap-0.5">
              <span className="text-lg font-bold text-primary">{getCurrencySymbol(product.currency)}</span>
              <span className="text-2xl font-bold text-primary">
                {totalPrice.toFixed(2)}
              </span>
            </div>
          </div>
          {couponDiscount > 0 && (
            <>
              <div className="flex items-baseline justify-between">
                <span className="text-sm text-muted-foreground">{t("product.couponDiscount")}</span>
                <span className="text-sm font-semibold text-destructive">-{getCurrencySymbol(product.currency)}{couponDiscount.toFixed(2)}</span>
              </div>
              <div className="flex items-baseline justify-between">
                <span className="text-sm font-medium text-foreground">{t("product.couponPayable")}</span>
                <div className="flex items-baseline gap-0.5">
                  <span className="text-lg font-bold text-primary">{getCurrencySymbol(product.currency)}</span>
                  <span className="text-2xl font-bold text-primary">
                    {couponPayable.toFixed(2)}
                  </span>
                </div>
              </div>
            </>
          )}
        </div>

        <Turnstile onSuccess={setTurnstileToken} onError={handleTurnstileReset} className="mb-3" />

        {/* Action Buttons */}
        <div className="flex gap-3">
          <ShareCommissionButton
            productId={product.id}
            productTitle={product.title}
            productPrice={product.base_price}
          />
          <button
            onClick={handleBuyNow}
            disabled={submitting || isOutOfStock}
            className="scheme-glow inline-flex h-11 flex-1 items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:pointer-events-none disabled:opacity-50"
          >
            {submitting ? (
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" />
            ) : (
              <Zap className="h-4 w-4" />
            )}
            {isOutOfStock ? t("product.outOfStock") : t("product.buyNow")}
          </button>
          <button
            onClick={handleAddToCart}
            disabled={isOutOfStock}
            className="inline-flex h-11 items-center justify-center gap-2 rounded-lg border border-border bg-transparent px-5 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:pointer-events-none disabled:opacity-50"
          >
            <ShoppingCart className="h-4 w-4" />
            {t("product.addToCart")}
          </button>
        </div>
      </div>
    </div>
  )
}

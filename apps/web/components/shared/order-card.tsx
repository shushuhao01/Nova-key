"use client"

import Link from "next/link"
import { ExternalLink } from "lucide-react"
import { PaymentIcon, getPaymentLabel } from "@/components/shared/payment-icon"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { useLocale } from "@/lib/context"
import type { OrderBrief } from "@/types"

/**
 * 订单卡片：购买记录列表与个人中心「最近购买」共用，保证两处展示一致
 */
export function OrderCard({ order }: { order: OrderBrief }) {
  const { t } = useLocale()
  const created = new Date(order.created_at)
  const createdAt = Number.isNaN(created.getTime())
    ? order.created_at
    : `${created.getFullYear()}-${String(created.getMonth() + 1).padStart(2, "0")}-${String(created.getDate()).padStart(2, "0")} ${String(created.getHours()).padStart(2, "0")}:${String(created.getMinutes()).padStart(2, "0")}`

  return (
    <div className="rounded-lg border border-border bg-card p-4 transition-colors hover:border-primary/20">
      {/* Order Header：移动端订单号/状态与时间分两行，避免拥挤换行错位 */}
      <div className="mb-3 flex flex-col gap-1.5 sm:flex-row sm:items-center sm:justify-between sm:gap-3">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <span className="min-w-0 truncate font-mono text-xs font-medium text-card-foreground">
            {order.id.length > 20 ? `${order.id.slice(0, 8)}...${order.id.slice(-8)}` : order.id}
          </span>
          <OrderStatusBadge status={order.status} />
        </div>
        <span className="shrink-0 text-xs text-muted-foreground">{createdAt}</span>
      </div>

      {/* Order Summary */}
      <div className="mb-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
        <span>{order.order_type === "CART" ? t("myOrders.cartOrder") : t("myOrders.directOrder")}</span>
        <span>·</span>
        <span className="inline-flex items-center gap-1">
          <PaymentIcon method={order.payment_method} className="h-4 w-4" />
          {getPaymentLabel(order.payment_method, t)}
        </span>
      </div>

      {/* Footer */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border pt-3">
        <span className="text-sm text-muted-foreground">
          {t("order.amount")}:{" "}
          <span className="font-semibold text-foreground">
            {"\u00A5"}{order.actual_amount.toFixed(2)}
          </span>
        </span>
        <div className="flex gap-2">
          {order.status === "PENDING" && order.payment_method && !order.payment_method.startsWith("usdt_") && (
            <Link
              href={`/pay/${order.id}?method=${order.payment_method}`}
              className="inline-flex h-8 items-center gap-1 rounded-md bg-primary px-3 text-xs font-medium text-primary-foreground hover:bg-primary/90"
            >
              {t("payment.continuePay")}
            </Link>
          )}
          {(order.status === "DELIVERED" || order.status === "COMPLETED") && (
            <Link
              href={`/order/query?orderId=${order.id}`}
              className="inline-flex h-8 items-center gap-1 rounded-md border border-border px-3 text-xs font-medium text-foreground hover:bg-accent"
            >
              <ExternalLink className="h-3 w-3" />
              {t("order.cardKeys")}
            </Link>
          )}
        </div>
      </div>
    </div>
  )
}

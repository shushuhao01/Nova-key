"use client"

import { useState } from "react"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { OrderDetailModal } from "@/components/admin/order-detail-modal"
import { Eye } from "lucide-react"
import { useLocale } from "@/lib/context"
import { toast } from "sonner"
import type { AdminOrderItem } from "@/types"

export function RecentOrders({ orders }: { orders: AdminOrderItem[] }) {
  const { t } = useLocale()
  const [detailOrder, setDetailOrder] = useState<AdminOrderItem | null>(null)

  const copyToClipboard = (text: string) => {
    if (navigator.clipboard?.writeText) {
      navigator.clipboard.writeText(text).then(
        () => toast.success(t("order.copied")),
        () => fallbackCopy(text)
      )
    } else {
      fallbackCopy(text)
    }
    function fallbackCopy(val: string) {
      const ta = document.createElement("textarea")
      ta.value = val
      ta.style.position = "fixed"
      ta.style.opacity = "0"
      document.body.appendChild(ta)
      ta.select()
      document.execCommand("copy")
      document.body.removeChild(ta)
      toast.success(t("order.copied"))
    }
  }
  return (
    <div className="rounded-xl border border-border bg-card p-5 shadow-sm">
      <h3 className="mb-4 font-semibold text-foreground">{t("admin.recentOrders")}</h3>
      <div className="overflow-x-auto">
        <table className="w-full text-sm table-fixed">
          <colgroup>
            <col className="w-[16%]" />{/* 订单号 */}
            <col className="w-[22%]" />{/* 商品 */}
            <col className="w-[17%]" />{/* 用户 */}
            <col className="w-[12%]" />{/* 推广员 */}
            <col className="w-[9%]" />{/* 金额 */}
            <col className="w-[9%]" />{/* 状态 */}
            <col className="w-[19%]" />{/* 时间 */}
            <col className="w-[5%]" />{/* 操作 */}
          </colgroup>
          <thead>
            <tr className="border-b border-border text-left">
              <th className="pb-3 font-medium text-muted-foreground">{t("admin.orderNo")}</th>
              <th className="pb-3 font-medium text-muted-foreground">{t("admin.product")}</th>
              <th className="pb-3 font-medium text-muted-foreground">{t("admin.user")}</th>
              <th className="pb-3 font-medium text-muted-foreground">推广员</th>
              <th className="pb-3 font-medium text-muted-foreground">{t("admin.amount")}</th>
              <th className="pb-3 font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
              <th className="pb-3 font-medium text-muted-foreground">{t("admin.time")}</th>
              <th className="pb-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
            </tr>
          </thead>
          <tbody>
            {orders.map((order) => (
              <tr key={order.id} className="border-b border-border/50 last:border-0">
                <td className="py-3 font-mono text-sm text-foreground">
                  <span
                    className="cursor-pointer underline-offset-4 transition-colors hover:underline hover:text-primary"
                    title={order.id}
                    onClick={() => copyToClipboard(order.id)}
                  >
                    {order.id.length > 20 ? `${order.id.slice(0, 8)}...${order.id.slice(-8)}` : order.id}
                  </span>
                </td>
                <td className="py-3 text-sm text-foreground">
                  <div className="max-w-[80%] truncate" title={
                    (order.items?.[0]?.product_title || "-") + (order.items?.[0]?.spec_name ? ` - ${order.items[0].spec_name}` : "") + ((order.items?.length ?? 0) > 1 ? ` 等${order.items.length}件` : "")
                  }>
                    {order.items?.[0]?.product_title || "-"}
                    {order.items?.[0]?.spec_name && (
                      <span className="text-muted-foreground"> - {order.items[0].spec_name}</span>
                    )}
                    {(order.items?.length ?? 0) > 1 && (
                      <span className="ml-1 text-xs text-muted-foreground">等{order.items.length}件</span>
                    )}
                  </div>
                </td>
                <td className="py-3 truncate text-foreground" title={order.username || order.email}>{order.username || order.email}</td>
                <td className="py-3 truncate text-muted-foreground" title={order.promoter || "-"}>{order.promoter || "-"}</td>
                <td className="py-3 font-medium text-foreground">¥{order.actual_amount.toFixed(2)}</td>
                <td className="py-3">
                  <OrderStatusBadge status={order.status} />
                </td>
                <td className="py-3 text-muted-foreground">
                  {new Date(order.created_at).toLocaleString()}
                </td>
                <td className="py-3 text-right">
                  <button
                    type="button"
                    className="rounded-md p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
                    title="查看详情"
                    onClick={() => setDetailOrder(order)}
                  >
                    <Eye className="h-4 w-4" />
                  </button>
                </td>
              </tr>
            ))}
            {orders.length === 0 && (
              <tr>
                <td colSpan={8} className="py-8 text-center text-sm text-muted-foreground">
                  {t("admin.noOrderData")}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <OrderDetailModal order={detailOrder} onClose={() => setDetailOrder(null)} />
    </div>
  )
}

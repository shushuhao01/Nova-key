"use client"

import { useEffect, useState } from "react"
import { Modal } from "@/components/ui/modal"
import { PaymentIcon, getPaymentLabel } from "@/components/shared/payment-icon"
import { stripInvisible } from "@/lib/utils"
import { useLocale } from "@/lib/context"
import { adminCardKeyApi } from "@/services/api"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { X, Copy } from "lucide-react"
import type { AdminOrderItem, OrderCardKey } from "@/types"

/**
 * 订单详情弹窗（可复用）。
 * 订单管理页与仪表盘"最近订单-查看详情"共用；操作按钮通过 showActions / onMarkPaid 控制。
 */
export function OrderDetailModal({
  order,
  onClose,
  showActions = false,
  onMarkPaid,
}: {
  order: AdminOrderItem | null
  onClose: () => void
  showActions?: boolean
  onMarkPaid?: (orderId: string) => void
}) {
  const { t } = useLocale()
  const [cardKeys, setCardKeys] = useState<OrderCardKey[]>([])

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

  useEffect(() => {
    if (!order) { setCardKeys([]); return }
    if (order.status !== "DELIVERED") { setCardKeys([]); return }
    let cancelled = false
    adminCardKeyApi.getByOrder(order.id)
      .then((keys) => { if (!cancelled) setCardKeys(keys) })
      .catch(() => { if (!cancelled) setCardKeys([]) })
    return () => { cancelled = true }
  }, [order])

  if (!order) return null

  return (
    <Modal open={order !== null} onClose={onClose} className="max-w-lg">
      <div className="border-b border-border px-6 py-4 flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-foreground">{t("admin.orderDetail")}</h2>
          <p className="font-mono text-xs text-muted-foreground">{order.id}</p>
        </div>
        <button
          type="button"
          onClick={onClose}
          className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
        >
          <X className="h-5 w-5" />
        </button>
      </div>
      <div className="flex flex-col gap-5 p-6">
        {/* 商品明细 */}
        <div>
          <p className="text-xs text-muted-foreground mb-2">商品明细</p>
          <div className="rounded-lg border border-border overflow-hidden">
            {order.items.length > 0 ? order.items.map((item, idx) => (
              <div key={item.id} className={cn("flex items-center justify-between px-3 py-2 text-sm", idx > 0 && "border-t border-border/50")}>
                <span className="text-foreground">
                  {item.product_title}
                  {item.spec_name && <span className="text-muted-foreground"> - {item.spec_name}</span>}
                  <span className="ml-2 text-muted-foreground">×{item.quantity}</span>
                </span>
                <span className="font-medium text-foreground">¥{item.subtotal.toFixed(2)}</span>
              </div>
            )) : (
              <div className="px-3 py-2 text-sm text-muted-foreground">-</div>
            )}
          </div>
        </div>

        <div className="grid grid-cols-2 gap-x-8 gap-y-4">
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">支付金额</span>
            <span className="text-sm font-medium text-foreground">¥{order.actual_amount.toFixed(2)}</span>
            {(order.refunded_amount ?? 0) > 0 && (
              <span className="text-xs font-medium text-red-500">已退 ¥{order.refunded_amount!.toFixed(2)}</span>
            )}
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">支付方式</span>
            <span className="inline-flex items-center gap-1.5 text-sm text-foreground">
              <PaymentIcon method={order.payment_method} className="h-4 w-4" />
              {getPaymentLabel(order.payment_method)}
            </span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">联系邮箱</span>
            <span className="text-sm text-foreground">{order.email}</span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">创建时间</span>
            <span className="text-sm text-foreground">{new Date(order.created_at).toLocaleString()}</span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">支付时间</span>
            <span className="text-sm text-foreground">{order.paid_at ? new Date(order.paid_at).toLocaleString() : "-"}</span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">设备</span>
            <span className="text-sm text-foreground">{order.device || "-"}</span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">推广员</span>
            <span className="text-sm text-foreground">{order.promoter || "-"}</span>
          </div>
        </div>

        {/* 已发卡密 */}
        {order.status === "DELIVERED" && cardKeys.length > 0 && (
          <div onCopy={(e) => { const t = window.getSelection()?.toString(); if (t) { e.clipboardData.setData("text/plain", stripInvisible(t)); e.preventDefault() } }}>
            <p className="text-xs text-muted-foreground mb-2">已发卡密</p>
            {(() => {
              const groups = new Map<string, OrderCardKey[]>()
              for (const ck of cardKeys) {
                const key = ck.product_title + (ck.spec_name ? ` - ${ck.spec_name}` : "")
                if (!groups.has(key)) groups.set(key, [])
                groups.get(key)!.push(ck)
              }
              const isMultiGroup = groups.size > 1
              if (!isMultiGroup) {
                return (
                  <div className="flex flex-col gap-1.5">
                    {cardKeys.map((ck) => (
                      <div key={ck.card_key_id} className="flex items-center gap-2 rounded-lg border border-border bg-muted/30 px-3 py-2">
                        <code className="flex-1 text-sm text-foreground break-all">{ck.content}</code>
                        <button type="button" className="shrink-0 rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors" title="复制卡密" onClick={() => copyToClipboard(ck.content)}>
                          <Copy className="h-4 w-4" />
                        </button>
                      </div>
                    ))}
                  </div>
                )
              }
              return (
                <div className="flex flex-col gap-3">
                  {Array.from(groups.entries()).map(([groupName, keys]) => (
                    <div key={groupName}>
                      <p className="mb-1.5 text-xs font-medium text-muted-foreground">{groupName}</p>
                      <div className="flex flex-col gap-1.5">
                        {keys.map((ck) => (
                          <div key={ck.card_key_id} className="flex items-center gap-2 rounded-lg border border-border bg-muted/30 px-3 py-2">
                            <code className="flex-1 text-sm text-foreground break-all">{ck.content}</code>
                            <button type="button" className="shrink-0 rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors" title="复制卡密" onClick={() => copyToClipboard(ck.content)}>
                              <Copy className="h-4 w-4" />
                            </button>
                          </div>
                        ))}
                      </div>
                    </div>
                  ))}
                </div>
              )
            })()}
          </div>
        )}
      </div>
      <div className="flex justify-end gap-3 border-t border-border px-6 py-4">
        <button type="button" className="rounded-lg border border-input bg-transparent px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors" onClick={onClose}>
          {t("common.close")}
        </button>
        {showActions && order.status === "PENDING" && onMarkPaid && (
          <button type="button" className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors" onClick={() => onMarkPaid(order.id)}>
            {t("admin.markPaid")}
          </button>
        )}
      </div>
    </Modal>
  )
}

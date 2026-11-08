"use client"

import { useEffect, useState } from "react"
import { Modal } from "@/components/ui/modal"
import { PaymentIcon, getPaymentLabel } from "@/components/shared/payment-icon"
import { stripInvisible } from "@/lib/utils"
import { useLocale } from "@/lib/context"
import { adminCardKeyApi, adminDistributionApi, adminOrderApi } from "@/services/api"
import { toast } from "sonner"
import { cn } from "@/lib/utils"
import { X, Copy, Search, Loader2, UserPlus } from "lucide-react"
import type { AdminOrderItem, OrderCardKey } from "@/types"

/** 已发货及之后的订单才可能已分配卡密（DELIVERED 超 24 小时会自动转为 COMPLETED） */
const KEY_ISSUED_STATUSES = ["DELIVERED", "COMPLETED", "REFUNDED", "PARTIALLY_REFUNDED"]

/** 推广员选择项（仅列出接口用到的字段） */
interface DistributorOption {
  id: string
  username: string | null
  email: string | null
  distributor_code: string | null
}

/**
 * 订单详情弹窗（可复用）。
 * 订单管理页与仪表盘"最近订单-查看详情"共用；操作按钮通过 showActions / onMarkPaid 控制。
 */
export function OrderDetailModal({
  order,
  onClose,
  showActions = false,
  onMarkPaid,
  onOrderUpdated,
}: {
  order: AdminOrderItem | null
  onClose: () => void
  showActions?: boolean
  onMarkPaid?: (orderId: string) => void
  /** 绑定推广员成功后回传最新订单详情，父组件据此刷新 */
  onOrderUpdated?: (order: AdminOrderItem) => void
}) {
  const { t } = useLocale()
  const [cardKeys, setCardKeys] = useState<OrderCardKey[]>([])
  // 推广员选择器
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerKeyword, setPickerKeyword] = useState("")
  const [pickerList, setPickerList] = useState<DistributorOption[]>([])
  const [pickerLoading, setPickerLoading] = useState(false)
  const [binding, setBinding] = useState(false)

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
    if (!KEY_ISSUED_STATUSES.includes(order.status)) { setCardKeys([]); return }
    let cancelled = false
    adminCardKeyApi.getByOrder(order.id)
      .then((keys) => { if (!cancelled) setCardKeys(keys) })
      .catch(() => { if (!cancelled) setCardKeys([]) })
    return () => { cancelled = true }
  }, [order])

  // 打开推广员选择器时加载已审核通过的推广员（支持按用户名/邮箱/编号搜索）
  useEffect(() => {
    if (!pickerOpen) return
    let cancelled = false
    setPickerLoading(true)
    adminDistributionApi.listDistributors({ status: "APPROVED", keyword: pickerKeyword || undefined, page: 1, page_size: 20 })
      .then((res) => { if (!cancelled) setPickerList(res.list || []) })
      .catch(() => { if (!cancelled) setPickerList([]) })
      .finally(() => { if (!cancelled) setPickerLoading(false) })
    return () => { cancelled = true }
  }, [pickerOpen, pickerKeyword])

  /** 绑定选中的推广员：已支付订单由后端按分销规则补算佣金 */
  const handleBindPromoter = async (distributorId: string) => {
    if (!order || binding) return
    setBinding(true)
    try {
      const updated = await adminOrderApi.bindPromoter(order.id, distributorId)
      toast.success("已绑定推广员，权益按分销规则生效")
      setPickerOpen(false)
      onOrderUpdated?.(updated)
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "绑定推广员失败")
    } finally {
      setBinding(false)
    }
  }

  if (!order) return null

  return (
    <>
    {/* 选择器打开时屏蔽外层的关闭（ESC / 点击背景），避免两个弹窗被同时关闭 */}
    <Modal open={order !== null} onClose={() => { if (!pickerOpen) onClose() }} className="max-w-lg">
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
            <span className="text-sm text-foreground">{order.email || "-"}</span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">手机号</span>
            <span className="text-sm text-foreground">{order.phone || "-"}</span>
          </div>
          <div className="flex flex-col gap-1">
            <span className="text-xs text-muted-foreground">联系方式类型</span>
            <span className="text-sm text-foreground">
              {order.phone && order.email ? "手机号 + 邮箱" : order.phone ? "手机号" : order.email ? "邮箱" : "-"}
            </span>
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
            <span className="flex items-center gap-2 text-sm text-foreground">
              {order.promoter || "-"}
              {showActions && (
                <button
                  type="button"
                  className="inline-flex items-center gap-1 rounded-md border border-input px-2 py-0.5 text-xs text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
                  onClick={() => { setPickerKeyword(""); setPickerOpen(true) }}
                >
                  <UserPlus className="h-3.5 w-3.5" />
                  {order.promoter ? "更换推广员" : "选择推广员"}
                </button>
              )}
            </span>
          </div>
        </div>

        {/* 已发卡密 */}
        {KEY_ISSUED_STATUSES.includes(order.status) && cardKeys.length > 0 && (
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

    {/* 推广员选择器：点击行内「选择」即绑定，绑定后佣金/客户归属按分销规则处理 */}
    <Modal open={pickerOpen} onClose={() => setPickerOpen(false)} className="max-w-md">
        <div className="border-b border-border px-6 py-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold text-foreground">选择推广员</h2>
          <button
            type="button"
            onClick={() => setPickerOpen(false)}
            className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="flex flex-col gap-3 p-6">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              value={pickerKeyword}
              onChange={(e) => setPickerKeyword(e.target.value)}
              placeholder="搜索用户名 / 邮箱 / 分销编号"
              className="w-full rounded-lg border border-input bg-background py-2 pl-9 pr-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
          </div>
          <div className="max-h-80 overflow-y-auto rounded-lg border border-border">
            {pickerLoading ? (
              <div className="flex items-center justify-center gap-2 px-3 py-8 text-sm text-muted-foreground">
                <Loader2 className="h-4 w-4 animate-spin" /> 加载中…
              </div>
            ) : pickerList.length === 0 ? (
              <div className="px-3 py-8 text-center text-sm text-muted-foreground">暂无已审核通过的推广员</div>
            ) : (
              pickerList.map((d) => (
                <div key={d.id} className="flex items-center justify-between gap-3 border-b border-border/50 px-3 py-2.5 last:border-b-0">
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-foreground">
                      {d.username || d.email || d.distributor_code || d.id.substring(0, 8)}
                    </p>
                    <p className="truncate text-xs text-muted-foreground">
                      {[d.email, d.distributor_code ? `编号 ${d.distributor_code}` : null].filter(Boolean).join(" · ")}
                    </p>
                  </div>
                  <button
                    type="button"
                    disabled={binding}
                    onClick={() => handleBindPromoter(d.id)}
                    className="shrink-0 rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                  >
                    {binding ? "绑定中…" : "选择"}
                  </button>
                </div>
              ))
            )}
          </div>
          <p className="text-xs text-muted-foreground">
            绑定后该订单佣金、客户归属按分销规则处理；已支付订单会立即补算佣金。已产生佣金记录的订单不可更换推广员。
          </p>
        </div>
      </Modal>
    </>
  )
}

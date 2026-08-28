"use client"

import { useState, useEffect, useRef } from "react"
import { Search, ChevronDown, Eye, Download, ChevronLeft, ChevronRight, X, CheckCircle, Undo2, Loader2 } from "lucide-react"
import { cn } from "@/lib/utils"
import { useLocale } from "@/lib/context"
import { toast } from "sonner"
import { adminOrderApi, withMockFallback } from "@/services/api"
import { mockAdminOrderList } from "@/lib/mock-data"
import { OrderStatusBadge } from "@/components/shared/order-status-badge"
import { PaymentIcon, getPaymentLabel } from "@/components/shared/payment-icon"
import { OrderDetailModal } from "@/components/admin/order-detail-modal"
import { Modal } from "@/components/ui/modal"
import type { AdminOrderItem } from "@/types"

const ITEMS_PER_PAGE = 10

export default function AdminOrdersPage() {
  const { t } = useLocale()

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

  const [orders, setOrders] = useState<AdminOrderItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState("")
  const [statusFilter, setStatusFilter] = useState<string[]>([])
  const [paymentFilter, setPaymentFilter] = useState("")
  const [orderTypeFilter, setOrderTypeFilter] = useState("")
  const [currentPage, setCurrentPage] = useState(1)
  const [showDetail, setShowDetail] = useState<AdminOrderItem | null>(null)

  const [debouncedSearch, setDebouncedSearch] = useState("")
  const [markPaidConfirm, setMarkPaidConfirm] = useState<string | null>(null)

  // ── 退款状态 ──
  const [refundTarget, setRefundTarget] = useState<AdminOrderItem | null>(null)
  const [refundMode, setRefundMode] = useState<"full" | "partial">("full")
  const [refundAmount, setRefundAmount] = useState("")
  const [refundReason, setRefundReason] = useState("")
  const [refundSubmitting, setRefundSubmitting] = useState(false)

  const openRefund = (order: AdminOrderItem) => {
    setRefundTarget(order)
    setRefundMode("full")
    setRefundAmount(order.actual_amount.toFixed(2))
    setRefundReason("")
  }

  const closeRefund = () => {
    if (refundSubmitting) return
    setRefundTarget(null)
    setRefundMode("full")
    setRefundAmount("")
    setRefundReason("")
  }

  const handleRefund = async () => {
    if (!refundTarget) return
    const maxAmount = refundTarget.actual_amount
    const amount = parseFloat(refundAmount)
    if (isNaN(amount) || amount <= 0) {
      toast.error("请输入有效的退款金额")
      return
    }
    if (amount > maxAmount) {
      toast.error(`退款金额不能超过订单实付金额 ¥${maxAmount.toFixed(2)}`)
      return
    }
    if (!refundReason.trim()) {
      toast.error("请填写退款原因")
      return
    }
    setRefundSubmitting(true)
    try {
      const res = await withMockFallback(
        () => adminOrderApi.refund(refundTarget.id, { amount, reason: refundReason.trim() }),
        () => null
      )
      toast.success(res ? `退款成功，已退 ¥${res.refunded_amount.toFixed(2)}` : "退款成功")
      closeRefund()
      await fetchOrders()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "退款失败")
    } finally {
      setRefundSubmitting(false)
    }
  }

  const fetchOrders = async () => {
    setLoading(true)
    try {
      const data = await withMockFallback(
        () => adminOrderApi.getList({
          page: currentPage,
          page_size: ITEMS_PER_PAGE,
          status: statusFilter.length > 0 ? statusFilter.join(",") : undefined,
          order_type: orderTypeFilter || undefined,
          payment_method: paymentFilter || undefined,
          keyword: debouncedSearch || undefined,
        }),
        () => mockAdminOrderList({
          status: statusFilter.length > 0 ? statusFilter.join(",") : undefined,
          page: currentPage,
          page_size: ITEMS_PER_PAGE,
        })
      )
      setOrders(data.list)
      setTotal(data.pagination.total)
    } catch {
      setOrders([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }

  // Debounce search input → reset page + commit debounced value
  useEffect(() => {
    if (search === debouncedSearch) return
    const timer = setTimeout(() => {
      setCurrentPage(1)
      setDebouncedSearch(search)
    }, 300)
    return () => clearTimeout(timer)
  }, [search])

  // Single fetch effect for all filter/page/search dependencies
  useEffect(() => { fetchOrders() }, [currentPage, statusFilter, paymentFilter, orderTypeFilter, debouncedSearch])

  const totalPages = Math.ceil(total / ITEMS_PER_PAGE)

  const handleViewDetail = (order: AdminOrderItem) => {
    setShowDetail(order)
  }

  const handleMarkPaid = async (orderId: string) => {
    try {
      await withMockFallback(
        () => adminOrderApi.markPaid(orderId),
        () => null
      )
      toast.success("已标记为已支付")
      setMarkPaidConfirm(null)
      setShowDetail(null)
      await fetchOrders()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.orders")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.ordersDesc")}</p>
        </div>
        <button
          type="button"
          className="flex items-center gap-2 rounded-lg border border-input bg-transparent px-4 py-2.5 text-sm font-medium text-foreground hover:bg-accent transition-colors"
          onClick={() => toast.info("导出功能开发中")}
        >
          <Download className="h-4 w-4" />
          导出
        </button>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap gap-3">
        <div className="relative flex-1 min-w-[200px] max-w-sm">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            placeholder={t("admin.searchOrder")}
            className="h-10 w-full rounded-lg border border-input bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>

        <StatusMultiSelect
          value={statusFilter}
          onChange={(v) => { setStatusFilter(v); setCurrentPage(1) }}
        />

        <div className="relative">
          <select
            className="h-10 appearance-none rounded-lg border border-input bg-background pl-3 pr-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            value={paymentFilter}
            onChange={(e) => { setPaymentFilter(e.target.value); setCurrentPage(1) }}
          >
            <option value="">{t("admin.allPayment")}</option>
            <option value="alipay">支付宝</option>
            <option value="wechat">微信支付</option>
            <option value="usdt_trc20">USDT</option>
          </select>
          <ChevronDown className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        </div>

        <div className="relative">
          <select
            className="h-10 appearance-none rounded-lg border border-input bg-background pl-3 pr-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            value={orderTypeFilter}
            onChange={(e) => { setOrderTypeFilter(e.target.value); setCurrentPage(1) }}
          >
            <option value="">{t("admin.allOrderType")}</option>
            <option value="DIRECT">{t("admin.directOrder")}</option>
            <option value="CART">{t("admin.cartOrder")}</option>
          </select>
          <ChevronDown className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        </div>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderNo")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">商品</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">数量</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.user")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">推广员</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.amount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.orderSource")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.paymentMethod")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">设备</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th>
                <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={12} className="py-12">
                    <div className="flex items-center justify-center">
                      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    </div>
                  </td>
                </tr>
              ) : orders.length === 0 ? (
                <tr>
                  <td colSpan={12} className="py-8 text-center text-sm text-muted-foreground">{t("admin.noOrderData")}</td>
                </tr>
              ) : (
                orders.map((order) => (
                  <tr key={order.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1.5">
                        <span
                          className="cursor-pointer font-mono text-sm font-medium text-foreground underline-offset-4 transition-colors hover:underline hover:text-primary"
                          title={order.id}
                          onClick={() => copyToClipboard(order.id)}
                        >
                          {order.id.length > 16 ? `${order.id.slice(0, 8)}...${order.id.slice(-4)}` : order.id}
                        </span>
                        {order.is_risk_flagged && (
                          <span className="rounded-full bg-red-500/10 px-1.5 py-0.5 text-[10px] font-medium text-red-500">{t("admin.riskFlagged")}</span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-sm text-foreground">
                        {order.items?.[0]?.product_title || "-"}
                        {order.items?.[0]?.spec_name && (
                          <span className="text-muted-foreground"> - {order.items[0].spec_name}</span>
                        )}
                        {(order.items?.length ?? 0) > 1 && (
                          <span className="ml-1 text-xs text-muted-foreground">等{order.items.length}件</span>
                        )}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-foreground">
                      {order.items?.reduce((sum, item) => sum + item.quantity, 0) || 0}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-col gap-0.5">
                        <span className="text-xs text-foreground">{order.username || t("admin.guest")}</span>
                        <span className="text-xs text-muted-foreground">{order.email}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs text-foreground">{order.promoter || "-"}</span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-col gap-0.5">
                        <span className="font-medium text-foreground">¥{order.actual_amount.toFixed(2)}</span>
                        {(order.refunded_amount ?? 0) > 0 && (
                          <span className="text-xs text-red-500">
                            已退 ¥{order.refunded_amount!.toFixed(2)}
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className={cn(
                        "rounded-full px-2.5 py-0.5 text-xs font-medium",
                        order.order_type === "CART"
                          ? "bg-purple-500/10 text-purple-600"
                          : "bg-blue-500/10 text-blue-600"
                      )}>
                        {order.order_type === "CART" ? t("admin.cartOrder") : t("admin.directOrder")}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="inline-flex items-center gap-1.5 text-foreground">
                        <PaymentIcon method={order.payment_method} className="h-4 w-4" />
                        {getPaymentLabel(order.payment_method)}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-xs text-muted-foreground">{order.device || "-"}</span>
                    </td>
                    <td className="px-4 py-3">
                      <OrderStatusBadge status={order.status} />
                    </td>
                    <td className="px-4 py-3 text-sm text-muted-foreground whitespace-nowrap">
                      {new Date(order.created_at).toLocaleString()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => handleViewDetail(order)}
                          className="rounded-md p-1.5 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
                          title={t("admin.viewDetail")}
                        >
                          <Eye className="h-4 w-4" />
                        </button>
                        {order.status === "PENDING" && (
                          <button
                            type="button"
                            onClick={() => setMarkPaidConfirm(order.id)}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-emerald-500/10 hover:text-emerald-600 transition-colors"
                            title={t("admin.markPaid")}
                          >
                            <CheckCircle className="h-4 w-4" />
                          </button>
                        )}
                        {order.provider_type === "native_wxpay" &&
                          (order.status === "PAID" || order.status === "DELIVERED" || order.status === "COMPLETED") && (
                          <button
                            type="button"
                            onClick={() => openRefund(order)}
                            className="rounded-md p-1.5 text-muted-foreground hover:bg-red-500/10 hover:text-red-500 transition-colors"
                            title="退款"
                          >
                            <Undo2 className="h-4 w-4" />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">
            {t("common.page")} {currentPage} / {totalPages}{t("admin.totalRecords")} {total} {t("admin.records")}
          </p>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
              disabled={currentPage === 1}
              className="flex h-9 w-9 items-center justify-center rounded-lg border border-input bg-transparent text-muted-foreground hover:bg-accent hover:text-foreground transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            {Array.from({ length: totalPages }, (_, i) => i + 1)
              .filter(page => page === 1 || page === totalPages || Math.abs(page - currentPage) <= 1)
              .map((page, index, array) => (
                <div key={page} className="flex items-center gap-1">
                  {index > 0 && array[index - 1] !== page - 1 && (
                    <span className="px-2 text-muted-foreground">...</span>
                  )}
                  <button
                    type="button"
                    onClick={() => setCurrentPage(page)}
                    className={cn(
                      "flex h-9 w-9 items-center justify-center rounded-lg text-sm font-medium transition-colors",
                      currentPage === page
                        ? "bg-primary text-primary-foreground"
                        : "border border-input bg-transparent text-foreground hover:bg-accent"
                    )}
                  >
                    {page}
                  </button>
                </div>
              ))}
            <button
              type="button"
              onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
              disabled={currentPage === totalPages}
              className="flex h-9 w-9 items-center justify-center rounded-lg border border-input bg-transparent text-muted-foreground hover:bg-accent hover:text-foreground transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}

      {/* Detail Modal */}
      <OrderDetailModal
        order={showDetail}
        onClose={() => setShowDetail(null)}
        showActions
        onMarkPaid={(orderId) => setMarkPaidConfirm(orderId)}
      />

      {/* Mark Paid Confirmation */}
      <Modal open={markPaidConfirm !== null} onClose={() => setMarkPaidConfirm(null)} className="max-w-sm">
        <div className="flex flex-col items-center gap-4 p-6 text-center">
          <div className="flex h-12 w-12 items-center justify-center rounded-full bg-emerald-500/10">
            <CheckCircle className="h-6 w-6 text-emerald-600" />
          </div>
          <h3 className="text-base font-semibold text-foreground">{t("admin.markPaidConfirm")}</h3>
          <div className="flex w-full gap-3">
            <button
              type="button"
              className="flex-1 rounded-lg border border-input bg-transparent px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors"
              onClick={() => setMarkPaidConfirm(null)}
            >
              {t("admin.cancel")}
            </button>
            <button
              type="button"
              className="flex-1 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
              onClick={() => markPaidConfirm && handleMarkPaid(markPaidConfirm)}
            >
              {t("admin.markPaidBtn")}
            </button>
          </div>
        </div>
      </Modal>

      {/* Refund Modal */}
      <Modal open={refundTarget !== null} onClose={closeRefund} className="max-w-md">
        {refundTarget && (
          <>
            <div className="border-b border-border px-6 py-4 flex items-center justify-between">
              <div>
                <h2 className="text-lg font-semibold text-foreground">订单退款</h2>
                <p className="font-mono text-xs text-muted-foreground">{refundTarget.id}</p>
              </div>
              <button
                type="button"
                onClick={closeRefund}
                className="rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground transition-colors"
              >
                <X className="h-5 w-5" />
              </button>
            </div>
            <div className="flex flex-col gap-5 p-6">
              {/* 订单信息 */}
              <div className="rounded-lg border border-border bg-muted/30 px-4 py-3 text-sm">
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">订单实付金额</span>
                  <span className="font-medium text-foreground">¥{refundTarget.actual_amount.toFixed(2)}</span>
                </div>
                <div className="mt-1 flex items-center justify-between">
                  <span className="text-muted-foreground">支付方式</span>
                  <span className="text-foreground">{getPaymentLabel(refundTarget.payment_method)}</span>
                </div>
                {(refundTarget.refunded_amount ?? 0) > 0 && (
                  <div className="mt-1 flex items-center justify-between">
                    <span className="text-muted-foreground">已退款金额</span>
                    <span className="font-medium text-red-500">¥{refundTarget.refunded_amount!.toFixed(2)}</span>
                  </div>
                )}
              </div>

              {/* 退款方式 */}
              <div>
                <p className="mb-2 text-xs font-medium text-muted-foreground">退款方式</p>
                <div className="grid grid-cols-2 gap-3">
                  <button
                    type="button"
                    onClick={() => {
                      setRefundMode("full")
                      setRefundAmount(refundTarget.actual_amount.toFixed(2))
                    }}
                    className={cn(
                      "rounded-lg border px-4 py-3 text-sm font-medium transition-colors",
                      refundMode === "full"
                        ? "border-red-500/50 bg-red-500/10 text-red-600"
                        : "border-input text-foreground hover:bg-accent"
                    )}
                  >
                    全额退款
                  </button>
                  <button
                    type="button"
                    onClick={() => setRefundMode("partial")}
                    className={cn(
                      "rounded-lg border px-4 py-3 text-sm font-medium transition-colors",
                      refundMode === "partial"
                        ? "border-red-500/50 bg-red-500/10 text-red-600"
                        : "border-input text-foreground hover:bg-accent"
                    )}
                  >
                    部分退款
                  </button>
                </div>
              </div>

              {/* 退款金额 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">
                  退款金额（元）
                </label>
                <input
                  type="number"
                  min={0.01}
                  max={refundTarget.actual_amount}
                  step="0.01"
                  value={refundAmount}
                  disabled={refundMode === "full"}
                  onChange={(e) => setRefundAmount(e.target.value)}
                  className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-60"
                />
                <p className="mt-1 text-xs text-muted-foreground">
                  可退金额 ¥{refundTarget.actual_amount.toFixed(2)}，退款将原路退回用户微信
                </p>
              </div>

              {/* 退款原因 */}
              <div>
                <label className="mb-2 block text-xs font-medium text-muted-foreground">
                  退款原因 <span className="text-red-500">*</span>
                </label>
                <textarea
                  value={refundReason}
                  onChange={(e) => setRefundReason(e.target.value)}
                  rows={3}
                  placeholder="请填写退款原因（必填）"
                  className="w-full resize-none rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
              </div>
            </div>
            <div className="flex justify-end gap-3 border-t border-border px-6 py-4">
              <button
                type="button"
                className="rounded-lg border border-input bg-transparent px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors"
                onClick={closeRefund}
              >
                {t("common.cancel")}
              </button>
              <button
                type="button"
                disabled={refundSubmitting}
                onClick={handleRefund}
                className="inline-flex items-center gap-2 rounded-lg bg-red-600 px-4 py-2 text-sm font-medium text-white hover:bg-red-700 transition-colors disabled:opacity-60"
              >
                {refundSubmitting && <Loader2 className="h-4 w-4 animate-spin" />}
                确认退款
              </button>
            </div>
          </>
        )}
      </Modal>
    </div>
  )
}

const ORDER_STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: "PENDING", label: "待支付" },
  { value: "PAID", label: "已支付" },
  { value: "DELIVERED", label: "已发货" },
  { value: "COMPLETED", label: "已完成" },
  { value: "EXPIRED", label: "已过期" },
  { value: "REFUNDED", label: "已退款" },
  { value: "PARTIALLY_REFUNDED", label: "部分退款" },
]

/**
 * 订单状态多选下拉：勾选时本地暂存，关闭面板（失焦/点外部）或点"确定"才触发过滤。
 * 面板内 mousedown preventDefault 避免点击选项时冒泡触发失焦提交。
 */
function StatusMultiSelect({ value, onChange }: { value: string[]; onChange: (v: string[]) => void }) {
  const { t } = useLocale()
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState<string[]>(value)
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => { setDraft(value) }, [value])

  const commit = () => {
    setOpen(false)
    const changed = draft.length !== value.length || draft.some((s, i) => s !== value[i])
    if (changed) onChange([...draft])
  }

  const toggle = (v: string) => {
    setDraft(prev => prev.includes(v) ? prev.filter(x => x !== v) : [...prev, v])
  }

  return (
    <div
      ref={containerRef}
      className="relative"
      onBlur={(e) => {
        if (!containerRef.current?.contains(e.relatedTarget as Node)) commit()
      }}
    >
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="flex h-10 items-center gap-2 rounded-lg border border-input bg-background pl-3 pr-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
      >
        <span className="max-w-[120px] truncate">
          {value.length === 0 ? t("admin.allStatus") : `已选 ${value.length} 项`}
        </span>
        <ChevronDown className={cn("h-4 w-4 text-muted-foreground transition-transform", open && "rotate-180")} />
      </button>
      {open && (
        <div
          className="absolute left-0 z-30 mt-1 w-48 rounded-lg border border-border bg-background p-2 shadow-lg"
          onMouseDown={(e) => e.preventDefault()}
        >
          {ORDER_STATUS_OPTIONS.map(opt => (
            <label
              key={opt.value}
              className="flex cursor-pointer items-center gap-2 rounded-md px-2 py-1.5 text-sm text-foreground hover:bg-accent"
            >
              <input
                type="checkbox"
                checked={draft.includes(opt.value)}
                onChange={() => toggle(opt.value)}
                className="h-3.5 w-3.5 accent-primary"
              />
              {opt.label}
            </label>
          ))}
          <div className="mt-2 flex items-center justify-between gap-2 border-t border-border pt-2">
            <button
              type="button"
              onClick={() => setDraft([])}
              className="rounded-md px-2 py-1 text-xs text-muted-foreground hover:bg-accent"
            >
              清空
            </button>
            <button
              type="button"
              onClick={commit}
              className="rounded-md bg-primary px-3 py-1 text-xs font-medium text-primary-foreground hover:brightness-110"
            >
              确定
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

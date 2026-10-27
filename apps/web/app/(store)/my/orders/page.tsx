"use client"

import { useState, useEffect } from "react"
import { Package } from "lucide-react"
import { useLocale } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"
import { userApi, withMockFallback } from "@/services/api"
import { mockOrderList } from "@/lib/mock-data"
import { OrderCard } from "@/components/shared/order-card"
import type { OrderBrief, OrderStatus } from "@/types"
import type { TranslationKey } from "@/lib/i18n"
import { cn } from "@/lib/utils"

const STATUS_FILTERS: (OrderStatus | "ALL")[] = ["ALL", "PENDING", "PAID", "DELIVERED", "COMPLETED", "EXPIRED"]

export default function MyOrdersPage() {
  const { t } = useLocale()
  const user = useRequireAuth()
  const [statusFilter, setStatusFilter] = useState<OrderStatus | "ALL">("ALL")
  const [orders, setOrders] = useState<OrderBrief[]>([])
  const [loading, setLoading] = useState(true)
  const [page, setPage] = useState(1)
  const [total, setTotal] = useState(0)
  const pageSize = 20

  useEffect(() => {
    if (!user) return
    let cancelled = false
    async function fetchOrders() {
      setLoading(true)
      try {
        const params: { page: number; page_size: number; status?: string } = { page, page_size: pageSize }
        if (statusFilter !== "ALL") params.status = statusFilter
        const data = await withMockFallback(
          () => userApi.getOrders(params),
          () => mockOrderList(params)
        )
        if (!cancelled) {
          setOrders(data.list)
          setTotal(data.pagination.total)
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
    fetchOrders()
    return () => { cancelled = true }
  }, [user, statusFilter, page])

  const statusKeys: Record<OrderStatus, TranslationKey> = {
    PENDING: "status.PENDING",
    PAID: "status.PAID",
    DELIVERED: "status.DELIVERED",
    COMPLETED: "status.COMPLETED",
    EXPIRED: "status.EXPIRED",
    REFUNDED: "status.REFUNDED",
    PARTIALLY_REFUNDED: "status.PARTIALLY_REFUNDED",
  }

  const getFilterLabel = (s: OrderStatus | "ALL") => {
    if (s === "ALL") return t("myOrders.all")
    return t(statusKeys[s])
  }

  if (!user) return null

  return (
    <div className="mx-auto max-w-3xl">
      <h1 className="mb-6 text-xl font-bold text-foreground">{t("myOrders.title")}</h1>

      {/* Status Filter */}
      <div className="mb-6 flex flex-wrap gap-2">
        {STATUS_FILTERS.map((s) => (
          <button
            key={s}
            onClick={() => { setStatusFilter(s); setPage(1) }}
            className={cn(
              "rounded-full px-3 py-1.5 text-sm font-medium transition-colors",
              statusFilter === s
                ? "bg-primary text-primary-foreground"
                : "bg-muted text-muted-foreground hover:bg-accent"
            )}
          >
            {getFilterLabel(s)}
          </button>
        ))}
      </div>

      {/* Orders List */}
      {loading ? (
        <div className="flex items-center justify-center py-24">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : orders.length > 0 ? (
        <div className="flex flex-col gap-3">
          {orders.map((order) => (
            <OrderCard key={order.id} order={order} />
          ))}

          {/* Pagination */}
          {total > pageSize && (
            <div className="flex justify-center gap-2 pt-4">
              <button
                onClick={() => setPage(p => Math.max(1, p - 1))}
                disabled={page <= 1}
                className="rounded-md border border-border px-3 py-1.5 text-sm text-foreground disabled:opacity-50"
              >
                {t("common.prev")}
              </button>
              <span className="flex items-center px-3 text-sm text-muted-foreground">
                {page} / {Math.ceil(total / pageSize)}
              </span>
              <button
                onClick={() => setPage(p => Math.min(Math.ceil(total / pageSize), p + 1))}
                disabled={page >= Math.ceil(total / pageSize)}
                className="rounded-md border border-border px-3 py-1.5 text-sm text-foreground disabled:opacity-50"
              >
                {t("common.next")}
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="flex flex-col items-center py-16">
          <Package className="mb-3 h-10 w-10 text-muted-foreground/30" />
          <p className="text-sm text-muted-foreground">{t("common.noData")}</p>
        </div>
      )}
    </div>
  )
}

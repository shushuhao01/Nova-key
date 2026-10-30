"use client"

import { useState, useEffect, useCallback } from "react"
import { RefreshCw } from "lucide-react"
import { useLocale } from "@/lib/context"
import { DashboardStats, LowStockAlert } from "@/components/admin/dashboard-stats"
import { SalesChart } from "@/components/admin/sales-chart"
import { RecentOrders } from "@/components/admin/recent-orders"
import { adminDashboardApi, adminOrderApi, withMockFallback } from "@/services/api"
import { mockDashboardStats, mockSalesTrend, mockAdminOrderList } from "@/lib/mock-data"
import type { DashboardStats as DashboardStatsType, SalesTrend, AdminOrderItem } from "@/types"

export default function AdminDashboardPage() {
  const { t } = useLocale()
  const [stats, setStats] = useState<DashboardStatsType | null>(null)
  const [trends, setTrends] = useState<SalesTrend[]>([])
  const [recentOrders, setRecentOrders] = useState<AdminOrderItem[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [period, setPeriod] = useState<"week" | "month">("month")

  // 概览数据（统计卡片 + 最近订单）
  const loadOverview = useCallback(async () => {
    try {
      const [statsData, orderData] = await Promise.all([
        withMockFallback(
          () => adminDashboardApi.getStats(),
          () => mockDashboardStats
        ),
        withMockFallback(
          () => adminOrderApi.getList({ page: 1, page_size: 5 }),
          () => mockAdminOrderList({ page: 1, page_size: 5 })
        ),
      ])
      setStats(statsData)
      setRecentOrders(orderData.list)
    } catch {
      // withMockFallback handles network errors; business errors reach here
    }
  }, [])

  // 趋势图数据：随「周/月」切换重新加载
  const loadTrend = useCallback(async () => {
    try {
      const trendData = await withMockFallback(
        () => adminDashboardApi.getSalesTrend({ period }),
        () => mockSalesTrend
      )
      setTrends(trendData)
    } catch {
      // 忽略
    }
  }, [period])

  useEffect(() => {
    loadOverview().finally(() => setLoading(false))
  }, [loadOverview])

  useEffect(() => {
    loadTrend()
  }, [loadTrend])

  // 手动刷新：同时更新统计、趋势图与最近订单
  const handleRefresh = async () => {
    setRefreshing(true)
    try {
      await Promise.all([loadOverview(), loadTrend()])
    } finally {
      setRefreshing(false)
    }
  }

  if (loading) {
    return (
      <div className="flex flex-col gap-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.dashboard")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.welcome")}</p>
        </div>
        <div className="flex items-center justify-center py-24">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-foreground">仪表盘</h1>
          <p className="text-sm text-muted-foreground">欢迎回来，管理员</p>
        </div>
        <button
          type="button"
          onClick={handleRefresh}
          disabled={refreshing}
          className="inline-flex items-center gap-2 rounded-lg border border-border px-3 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted disabled:cursor-not-allowed disabled:opacity-60"
        >
          <RefreshCw className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`} />
          {t("common.refresh")}
        </button>
      </div>

      {/* Stats Cards */}
      {stats && <DashboardStats stats={stats} />}

      {/* Charts and Alerts */}
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-3">
        <div className="xl:col-span-2">
          <SalesChart trends={trends} period={period} onPeriodChange={setPeriod} />
        </div>
        <LowStockAlert products={stats?.low_stock_products ?? []} />
      </div>

      {/* Recent Orders */}
      <RecentOrders orders={recentOrders} />
    </div>
  )
}

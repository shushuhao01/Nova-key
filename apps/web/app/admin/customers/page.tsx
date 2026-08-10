"use client"

import { useState, useEffect, useCallback } from "react"
import { useRouter } from "next/navigation"
import {
  Search, ChevronLeft, ChevronRight, Eye, Ban, CheckCircle2, Megaphone, Users, UserRound,
  ShoppingBag, CreditCard, Wallet, PlusCircle, X, UserX, UserCheck, Mail, Clock,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { useLocale } from "@/lib/context"
import { toast } from "sonner"
import { adminCustomerApi, withMockFallback } from "@/services/api"
import {
  mockCustomerOverview, mockRegisteredCustomerList, mockAnonymousCustomerList,
} from "@/lib/mock-data"
import type {
  CustomerOverview, RegisteredCustomerItem, AnonymousCustomerItem, CustomerOrderItem,
  RegisteredCustomerDetail, AnonymousCustomerDetail,
} from "@/types"

const ITEMS_PER_PAGE = 10

type Tab = "registered" | "anonymous"

type OrderDetailData = RegisteredCustomerDetail | AnonymousCustomerDetail

export default function AdminCustomersPage() {
  const { t } = useLocale()
  const router = useRouter()

  const [tab, setTab] = useState<Tab>("registered")
  const [overview, setOverview] = useState<CustomerOverview | null>(null)
  const [registered, setRegistered] = useState<RegisteredCustomerItem[]>([])
  const [anonymous, setAnonymous] = useState<AnonymousCustomerItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState("")
  const [currentPage, setCurrentPage] = useState(1)

  const [detailOpen, setDetailOpen] = useState(false)
  const [detailTitle, setDetailTitle] = useState("")
  const [detailData, setDetailData] = useState<OrderDetailData | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [orderPage, setOrderPage] = useState(1)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [detailEmail, setDetailEmail] = useState<string | null>(null)

  const fetchOverview = useCallback(async () => {
    try {
      const data = await withMockFallback(
        () => adminCustomerApi.overview(),
        () => mockCustomerOverview()
      )
      setOverview(data)
    } catch {
      setOverview(null)
    }
  }, [])

  useEffect(() => { fetchOverview() }, [fetchOverview])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      if (tab === "registered") {
        const data = await withMockFallback(
          () => adminCustomerApi.getRegistered({ page: currentPage, page_size: ITEMS_PER_PAGE, keyword: search || undefined }),
          () => mockRegisteredCustomerList({ keyword: search || undefined, page: currentPage, page_size: ITEMS_PER_PAGE })
        )
        setRegistered(data.list)
        setTotal(data.pagination.total)
      } else {
        const data = await withMockFallback(
          () => adminCustomerApi.getAnonymous({ page: currentPage, page_size: ITEMS_PER_PAGE, keyword: search || undefined }),
          () => mockAnonymousCustomerList({ keyword: search || undefined, page: currentPage, page_size: ITEMS_PER_PAGE })
        )
        setAnonymous(data.list)
        setTotal(data.pagination.total)
      }
    } catch {
      if (tab === "registered") setRegistered([])
      else setAnonymous([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [tab, currentPage, search])

  useEffect(() => {
    const timer = setTimeout(() => setCurrentPage(1), 300)
    return () => clearTimeout(timer)
  }, [search, tab])

  useEffect(() => { fetchList() }, [fetchList])

  const totalPages = Math.max(1, Math.ceil(total / ITEMS_PER_PAGE))

  const openDetail = async (kind: "registered" | "anonymous", idOrEmail: string) => {
    setDetailOpen(true)
    setDetailLoading(true)
    setOrderPage(1)
    setDetailId(kind === "registered" ? idOrEmail : null)
    setDetailEmail(kind === "anonymous" ? idOrEmail : null)
    setDetailTitle(kind === "registered" ? t("admin.customerRegistered") : t("admin.customerAnonymous"))
    try {
      const data = await withMockFallback<OrderDetailData>(
        () => kind === "registered"
          ? adminCustomerApi.registeredDetail(idOrEmail, { page: 1, page_size: ITEMS_PER_PAGE })
          : adminCustomerApi.anonymousDetail(idOrEmail, { page: 1, page_size: ITEMS_PER_PAGE }),
        () => ({
          id: idOrEmail,
          username: "mock",
          email: idOrEmail,
          points: 0,
          is_banned: false,
          created_at: new Date().toISOString(),
          registered_at: new Date().toISOString(),
          order_count: 0,
          paid_count: 0,
          total_spent: 0,
          orders: { list: [], pagination: { page: 1, page_size: ITEMS_PER_PAGE, total: 0 } },
        }) as OrderDetailData
      )
      setDetailData(data)
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "加载详情失败")
      setDetailOpen(false)
    } finally {
      setDetailLoading(false)
    }
  }

  const loadOrderPage = async (page: number) => {
    if (!detailId && !detailEmail) return
    setDetailLoading(true)
    setOrderPage(page)
    try {
      const data = await withMockFallback<OrderDetailData>(
        () => detailId
          ? adminCustomerApi.registeredDetail(detailId, { page, page_size: ITEMS_PER_PAGE })
          : adminCustomerApi.anonymousDetail(detailEmail!, { page, page_size: ITEMS_PER_PAGE }),
        () => detailData ?? ({ orders: { list: [], pagination: { page, page_size: ITEMS_PER_PAGE, total: 0 } } }) as unknown as OrderDetailData
      )
      setDetailData(data)
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "加载订单失败")
    } finally {
      setDetailLoading(false)
    }
  }

  const handleToggleBan = async (u: RegisteredCustomerItem) => {
    const newStatus: 0 | 1 = u.is_banned ? 0 : 1
    try {
      await withMockFallback(
        () => adminCustomerApi.toggleRegistered(u.id, newStatus),
        () => null
      )
      toast.success(newStatus === 1 ? t("admin.customerBan") : t("admin.customerUnban"))
      fetchList()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  const goMarketing = (audience: "USER_IDS" | "EMAILS", targets: string) => {
    router.push(`/admin/marketing?audience=${audience}&targets=${encodeURIComponent(targets)}`)
  }

  const orderStatusLabel = (s: string) => {
    const map: Record<string, string> = {
      PENDING: "待支付", PAID: "已支付", DELIVERED: "已发货", EXPIRED: "已过期",
    }
    return map[s] || s
  }

  const currency = (n: number) => `¥${Number(n ?? 0).toFixed(2)}`

  const statCards = [
    { key: "total_customers", label: t("admin.customerTotal"), icon: Users, color: "text-primary", bg: "bg-primary/10" },
    { key: "new_customers", label: t("admin.customerNew"), icon: PlusCircle, color: "text-emerald-600", bg: "bg-emerald-500/10" },
    { key: "deal_customers", label: t("admin.customerDeal"), icon: CreditCard, color: "text-blue-600", bg: "bg-blue-500/10" },
    { key: "no_deal_customers", label: t("admin.customerNoDeal"), icon: ShoppingBag, color: "text-amber-600", bg: "bg-amber-500/10" },
  ]

  const orders = detailData?.orders

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-foreground">{t("admin.customers")}</h1>
        <p className="text-sm text-muted-foreground">{t("admin.customersDesc")}</p>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        {statCards.map(card => {
          const Icon = card.icon
          return (
            <div key={card.key} className="flex items-center gap-4 rounded-xl border border-border bg-card p-5 shadow-sm">
              <div className={cn("flex h-12 w-12 shrink-0 items-center justify-center rounded-lg", card.bg, card.color)}>
                <Icon className="h-6 w-6" />
              </div>
              <div className="min-w-0">
                <p className="truncate text-xs text-muted-foreground">{card.label}</p>
                <p className="mt-0.5 text-2xl font-bold text-foreground">
                  {overview ? String(overview[card.key as keyof CustomerOverview]) : "—"}
                </p>
              </div>
            </div>
          )
        })}
      </div>

      {/* Tabs + search */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex rounded-lg border border-border bg-card p-1">
          {([
            { v: "registered" as Tab, label: `${t("admin.customerRegistered")}${overview ? ` (${overview.total_registered})` : ""}` },
            { v: "anonymous" as Tab, label: `${t("admin.customerAnonymous")}${overview ? ` (${overview.total_anonymous})` : ""}` },
          ]).map(tabItem => (
            <button
              key={tabItem.v}
              type="button"
              onClick={() => setTab(tabItem.v)}
              className={cn(
                "flex items-center gap-1.5 rounded-md px-4 py-2 text-sm font-medium transition-colors",
                tab === tabItem.v
                  ? "bg-primary text-primary-foreground"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              {tabItem.v === "registered" ? <UserRound className="h-4 w-4" /> : <Mail className="h-4 w-4" />}
              {tabItem.label}
            </button>
          ))}
        </div>
        <div className="relative max-w-sm flex-1 sm:max-w-xs">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            placeholder={t("admin.customerSearch")}
            className="h-10 w-full rounded-lg border border-input bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                {tab === "registered" ? (
                  <>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerUsername")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerEmail")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerWechat")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerMpSubscribe")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerOrderCount")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerPaidCount")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerTotalSpent")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerRegisteredAt")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
                    <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
                  </>
                ) : (
                  <>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerEmail")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerOrderCount")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerPaidCount")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerTotalSpent")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerFirstOrder")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.customerLastOrder")}</th>
                    <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={10} className="py-12">
                    <div className="flex items-center justify-center">
                      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    </div>
                  </td>
                </tr>
              ) : (tab === "registered" ? registered : anonymous).length === 0 ? (
                <tr>
                  <td colSpan={10} className="py-8 text-center text-sm text-muted-foreground">{t("admin.customerNoData")}</td>
                </tr>
              ) : tab === "registered" ? (
                registered.map((u) => (
                  <tr key={u.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        {u.mp_avatar ? (
                          <img src={u.mp_avatar} alt="" className="h-7 w-7 shrink-0 rounded-full border border-border object-cover" />
                        ) : (
                          <span className={cn(
                            "flex h-7 w-7 shrink-0 items-center justify-center rounded-full",
                            u.is_banned ? "bg-red-500/10 text-red-500" : "bg-primary/10 text-primary"
                          )}>
                            <UserRound className="h-4 w-4" />
                          </span>
                        )}
                        {u.distributor_level === 1 && (
                          <span
                            title={t("admin.customerLevel1")}
                            className="flex h-4 w-4 shrink-0 items-center justify-center rounded-sm bg-blue-500 text-[10px] font-bold leading-none text-white"
                          >
                            推
                          </span>
                        )}
                        {u.distributor_level === 2 && (
                          <span
                            title={t("admin.customerLevel2")}
                            className="flex h-4 w-4 shrink-0 items-center justify-center rounded-sm bg-emerald-500 text-[10px] font-bold leading-none text-white"
                          >
                            推
                          </span>
                        )}
                        <span className="font-medium text-foreground">{u.username}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">{u.email}</td>
                    <td className="px-4 py-3">
                      {u.wechat_customer ? (
                        <span className="inline-flex max-w-[140px] items-center gap-1 text-xs font-medium text-emerald-600">
                          <UserRound className="h-3.5 w-3.5 shrink-0" />
                          <span className="truncate">{u.mp_nickname || "微信客户"}</span>
                        </span>
                      ) : (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      {u.mp_subscribe === "SUBSCRIBED" ? (
                        <span className="inline-flex rounded-full bg-emerald-500/10 px-2.5 py-0.5 text-xs font-medium text-emerald-600">
                          {t("admin.customerMpSubscribed")}
                        </span>
                      ) : u.mp_subscribe === "UNSUBSCRIBED" ? (
                        <span className="inline-flex rounded-full bg-amber-500/10 px-2.5 py-0.5 text-xs font-medium text-amber-600">
                          {t("admin.customerMpUnsubscribed")}
                        </span>
                      ) : (
                        <span className="text-muted-foreground">{t("admin.customerMpNone")}</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-foreground">{u.order_count}</td>
                    <td className="px-4 py-3 text-foreground">{u.paid_count}</td>
                    <td className="px-4 py-3 font-medium text-primary">{currency(u.total_spent)}</td>
                    <td className="px-4 py-3 text-muted-foreground">{new Date(u.created_at).toLocaleDateString()}</td>
                    <td className="px-4 py-3">
                      <span className={cn(
                        "inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium",
                        u.is_banned
                          ? "bg-red-500/10 text-red-500"
                          : "bg-emerald-500/10 text-emerald-600"
                      )}>
                        {u.is_banned ? <UserX className="h-3 w-3" /> : <UserCheck className="h-3 w-3" />}
                        {u.is_banned ? t("admin.customerBanned") : t("admin.customerNormal")}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => openDetail("registered", u.id)}
                          className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
                          title={t("admin.customerDetail")}
                        >
                          <Eye className="h-4 w-4" />
                        </button>
                        <button
                          type="button"
                          onClick={() => goMarketing("USER_IDS", u.email)}
                          className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-primary/10 hover:text-primary"
                          title={t("admin.customerMarketing")}
                        >
                          <Megaphone className="h-4 w-4" />
                        </button>
                        <button
                          type="button"
                          onClick={() => handleToggleBan(u)}
                          className={cn(
                            "flex h-8 w-8 items-center justify-center rounded-md",
                            u.is_banned
                              ? "text-emerald-600 hover:bg-emerald-500/10"
                              : "text-red-500 hover:bg-red-500/10"
                          )}
                          title={u.is_banned ? t("admin.customerUnban") : t("admin.customerBan")}
                        >
                          {u.is_banned ? <CheckCircle2 className="h-4 w-4" /> : <Ban className="h-4 w-4" />}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              ) : (
                anonymous.map((u) => (
                  <tr key={u.email} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <span className="flex h-7 w-7 items-center justify-center rounded-full bg-muted text-muted-foreground">
                          <Mail className="h-4 w-4" />
                        </span>
                        <span className="font-medium text-foreground">{u.email}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3 text-foreground">{u.order_count}</td>
                    <td className="px-4 py-3 text-foreground">{u.paid_count}</td>
                    <td className="px-4 py-3 font-medium text-primary">{currency(u.total_spent)}</td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {u.first_order_at ? new Date(u.first_order_at).toLocaleDateString() : "—"}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {u.last_order_at ? new Date(u.last_order_at).toLocaleDateString() : "—"}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => openDetail("anonymous", u.email)}
                          className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
                          title={t("admin.customerDetail")}
                        >
                          <Eye className="h-4 w-4" />
                        </button>
                        <button
                          type="button"
                          onClick={() => goMarketing("EMAILS", u.email)}
                          className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-primary/10 hover:text-primary"
                          title={t("admin.customerMarketing")}
                        >
                          <Megaphone className="h-4 w-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        {totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-border px-4 py-3">
            <span className="text-sm text-muted-foreground">{t("admin.totalRecords")} {total}</span>
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                disabled={currentPage === 1}
                className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              {Array.from({ length: totalPages }, (_, i) => i + 1).map((page) => (
                <button
                  key={page}
                  type="button"
                  onClick={() => setCurrentPage(page)}
                  className={cn(
                    "flex h-8 w-8 items-center justify-center rounded-md text-sm font-medium",
                    currentPage === page
                      ? "bg-primary text-primary-foreground"
                      : "border border-input text-foreground hover:bg-accent"
                  )}
                >
                  {page}
                </button>
              ))}
              <button
                type="button"
                onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                disabled={currentPage === totalPages}
                className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
              >
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Detail modal */}
      {detailOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50" onClick={() => setDetailOpen(false)} />
          <div className="relative max-h-[90vh] w-full max-w-4xl overflow-y-auto rounded-xl border border-border bg-card shadow-2xl">
            {/* Header */}
            <div className="sticky top-0 z-10 flex items-center justify-between border-b border-border bg-card px-6 py-4">
              <div className="flex items-center gap-2">
                <UserRound className="h-5 w-5 text-primary" />
                <h2 className="text-lg font-bold text-foreground">{t("admin.customerDetail")} · {detailTitle}</h2>
              </div>
              <button
                type="button"
                onClick={() => setDetailOpen(false)}
                className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="flex flex-col gap-5 p-6">
              {/* Customer summary */}
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                {detailData && [
                  { label: t("admin.customerEmail"), value: (detailData.email as string) || "" },
                  { label: t("admin.customerOrderCount"), value: String(detailData.order_count ?? 0) },
                  { label: t("admin.customerPaidCount"), value: String(detailData.paid_count ?? 0) },
                  { label: t("admin.customerTotalSpent"), value: currency(Number(detailData.total_spent ?? 0)) },
                ].map(item => (
                  <div key={item.label} className="rounded-lg border border-border bg-muted/20 p-3">
                    <p className="text-xs text-muted-foreground">{item.label}</p>
                    <p className="mt-1 truncate font-medium text-foreground">{item.value}</p>
                  </div>
                ))}
              </div>

              {/* Orders */}
              <div>
                <h3 className="mb-3 flex items-center gap-2 text-sm font-semibold text-foreground">
                  <Clock className="h-4 w-4 text-primary" />
                  {t("admin.customerOrders")}
                  {orders ? ` (${orders.pagination.total})` : ""}
                </h3>
                <div className="overflow-hidden rounded-lg border border-border">
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="border-b border-border bg-muted/30">
                          <th className="px-3 py-2.5 text-left font-medium text-muted-foreground">{t("admin.customerOrderNo")}</th>
                          <th className="px-3 py-2.5 text-left font-medium text-muted-foreground">{t("admin.customerOrderItems")}</th>
                          <th className="px-3 py-2.5 text-left font-medium text-muted-foreground">{t("admin.customerOrderStatus")}</th>
                          <th className="px-3 py-2.5 text-right font-medium text-muted-foreground">{t("admin.customerOrderAmount")}</th>
                          <th className="px-3 py-2.5 text-right font-medium text-muted-foreground">{t("admin.customerOrderTime")}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detailLoading && !orders ? (
                          <tr>
                            <td colSpan={5} className="py-10">
                              <div className="flex items-center justify-center">
                                <div className="h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                              </div>
                            </td>
                          </tr>
                        ) : !orders || orders.list.length === 0 ? (
                          <tr>
                            <td colSpan={5} className="py-8 text-center text-muted-foreground">{t("admin.customerNoData")}</td>
                          </tr>
                        ) : (
                          orders.list.map((o) => (
                            <tr key={o.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20">
                              <td className="px-3 py-2.5 font-mono text-xs text-muted-foreground">{o.id.slice(0, 8)}</td>
                              <td className="px-3 py-2.5 text-foreground">
                                {o.items.map((it, i) => (
                                  <span key={i} className="block">
                                    {it.product_title}{it.spec_name ? ` [${it.spec_name}]` : ""} × {it.quantity}
                                  </span>
                                ))}
                              </td>
                              <td className="px-3 py-2.5">
                                <span className={cn(
                                  "rounded-full px-2 py-0.5 text-xs font-medium",
                                  o.status === "DELIVERED" ? "bg-emerald-500/10 text-emerald-600"
                                    : o.status === "PAID" ? "bg-blue-500/10 text-blue-600"
                                    : o.status === "EXPIRED" ? "bg-gray-500/10 text-gray-500"
                                    : "bg-amber-500/10 text-amber-600"
                                )}>
                                  {orderStatusLabel(o.status)}
                                </span>
                              </td>
                              <td className="px-3 py-2.5 text-right font-medium text-primary">
                                {currency(o.actual_amount)}
                                {o.coupon_discount > 0 && (
                                  <span className="ml-1 text-xs text-muted-foreground">(-{currency(o.coupon_discount)})</span>
                                )}
                              </td>
                              <td className="px-3 py-2.5 text-right text-xs text-muted-foreground">
                                {new Date(o.created_at).toLocaleString()}
                              </td>
                            </tr>
                          ))
                        )}
                      </tbody>
                    </table>
                  </div>
                  {orders && orders.pagination.total > ITEMS_PER_PAGE && (
                    <div className="flex items-center justify-end gap-1 border-t border-border px-3 py-2">
                      <button
                        type="button"
                        onClick={() => loadOrderPage(orderPage - 1)}
                        disabled={orderPage <= 1}
                        className="flex h-7 w-7 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
                      >
                        <ChevronLeft className="h-3.5 w-3.5" />
                      </button>
                      <span className="px-2 text-xs text-muted-foreground">
                        {orderPage} / {Math.max(1, Math.ceil(orders.pagination.total / ITEMS_PER_PAGE))}
                      </span>
                      <button
                        type="button"
                        onClick={() => loadOrderPage(orderPage + 1)}
                        disabled={orderPage >= Math.ceil(orders.pagination.total / ITEMS_PER_PAGE)}
                        className="flex h-7 w-7 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
                      >
                        <ChevronRight className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

"use client"

import { useState, useEffect, useCallback } from "react"
import {
  Bell, CheckCheck, Trash2, ShoppingBag, Share2, Settings, Inbox,
  ChevronLeft, ChevronRight, Check,
} from "lucide-react"
import { toast } from "sonner"
import { useLocale } from "@/lib/context"
import { useRequireAuth } from "@/lib/hooks"
import { userMessageApi, getApiErrorMessage } from "@/services/api"
import { cn } from "@/lib/utils"

const PAGE_SIZE = 10

type Category = "ORDER" | "DISTRIBUTION" | "SYSTEM"
type CategoryFilter = "ALL" | Category

interface MessageItem {
  id: string
  category: Category | string
  title: string
  content: string
  is_read: boolean
  created_at: string
}

const categoryConfig: Record<string, { label: string; icon: typeof ShoppingBag; cls: string }> = {
  ORDER: { label: "订单", icon: ShoppingBag, cls: "bg-blue-500/10 text-blue-600" },
  DISTRIBUTION: { label: "分销", icon: Share2, cls: "bg-emerald-500/10 text-emerald-600" },
  SYSTEM: { label: "系统", icon: Settings, cls: "bg-amber-500/10 text-amber-600" },
}

const getCategoryConfig = (cat: string) =>
  categoryConfig[cat] || { label: cat || "消息", icon: Bell, cls: "bg-muted text-muted-foreground" }

const fmtDate = (s: string | null | undefined) => (s ? new Date(s).toLocaleString() : "—")

export default function MessagesPage() {
  const { t } = useLocale()
  const user = useRequireAuth()
  const [list, setList] = useState<MessageItem[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)
  const [unreadCount, setUnreadCount] = useState(0)
  const [filter, setFilter] = useState<CategoryFilter>("ALL")
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [operating, setOperating] = useState(false)

  const fetchUnread = useCallback(async () => {
    try {
      const res = await userMessageApi.unreadCount()
      setUnreadCount((res as any)?.count ?? 0)
    } catch {
      // 静默
    }
  }, [])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const params: { page: number; page_size: number; category?: string; unreadOnly?: boolean } = {
        page,
        page_size: PAGE_SIZE,
      }
      if (filter !== "ALL") params.category = filter
      if (unreadOnly) params.unreadOnly = true
      const data = await userMessageApi.list(params)
      setList(((data as any)?.list || []) as MessageItem[])
      setTotal((data as any)?.pagination?.total ?? 0)
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [page, filter, unreadOnly, t])

  useEffect(() => {
    if (!user) return
    fetchList()
    fetchUnread()
  }, [user, fetchList, fetchUnread])

  useEffect(() => { setPage(1) }, [filter, unreadOnly])

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  const refreshAll = useCallback(() => {
    fetchList()
    fetchUnread()
  }, [fetchList, fetchUnread])

  const handleMarkRead = async (item: MessageItem) => {
    if (item.is_read) return
    // 乐观更新
    setList(prev => prev.map(m => m.id === item.id ? { ...m, is_read: true } : m))
    setUnreadCount(c => Math.max(0, c - 1))
    try {
      await userMessageApi.markRead(item.id)
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
      refreshAll()
    }
  }

  const handleMarkAllRead = async () => {
    if (unreadCount === 0) {
      toast.info("没有未读消息")
      return
    }
    setOperating(true)
    try {
      await userMessageApi.markAllRead()
      toast.success("已全部标记为已读")
      refreshAll()
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setOperating(false)
    }
  }

  const handleClearAll = async () => {
    if (list.length === 0 && total === 0) {
      toast.info("没有可清空的消息")
      return
    }
    if (!window.confirm("确认清空所有消息？此操作不可撤销。")) return
    setOperating(true)
    try {
      await userMessageApi.clearAll()
      toast.success("已清空所有消息")
      setList([])
      setTotal(0)
      setUnreadCount(0)
      setPage(1)
    } catch (err) {
      toast.error(getApiErrorMessage(err, t))
    } finally {
      setOperating(false)
    }
  }

  if (!user) return null

  const filters: { k: CategoryFilter; label: string }[] = [
    { k: "ALL", label: "全部" },
    { k: "ORDER", label: "订单" },
    { k: "DISTRIBUTION", label: "分销" },
    { k: "SYSTEM", label: "系统" },
  ]

  return (
    <div className="mx-auto max-w-3xl">
      {/* 顶部标题栏 */}
      <div className="mb-6 flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="relative flex h-11 w-11 items-center justify-center rounded-full bg-primary/10">
            <Bell className="h-5 w-5 text-primary" />
            {unreadCount > 0 && (
              <span className="absolute -right-1 -top-1 flex h-5 min-w-5 items-center justify-center rounded-full bg-red-500 px-1 text-[11px] font-bold text-white">
                {unreadCount > 99 ? "99+" : unreadCount}
              </span>
            )}
          </div>
          <div>
            <h1 className="text-xl font-bold text-foreground">消息中心</h1>
            <p className="text-sm text-muted-foreground">
              {unreadCount > 0 ? `您有 ${unreadCount} 条未读消息` : "暂无未读消息"}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={handleMarkAllRead}
            disabled={operating || unreadCount === 0}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-input px-3 text-sm font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
            title="全部已读"
          >
            <CheckCheck className="h-4 w-4" />
            <span className="hidden sm:inline">全部已读</span>
          </button>
          <button
            type="button"
            onClick={handleClearAll}
            disabled={operating}
            className="inline-flex h-9 items-center gap-1.5 rounded-lg border border-input px-3 text-sm font-medium text-red-500 transition-colors hover:bg-red-500/10 disabled:opacity-50"
            title="清空消息"
          >
            <Trash2 className="h-4 w-4" />
            <span className="hidden sm:inline">清空</span>
          </button>
        </div>
      </div>

      {/* 筛选 */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {filters.map((f) => (
          <button
            key={f.k}
            type="button"
            onClick={() => setFilter(f.k)}
            className={cn(
              "rounded-full px-3 py-1.5 text-sm font-medium transition-colors",
              filter === f.k
                ? "bg-primary text-primary-foreground"
                : "bg-muted text-muted-foreground hover:bg-accent"
            )}
          >
            {f.label}
          </button>
        ))}
        <span className="mx-1 h-4 w-px bg-border" />
        <button
          type="button"
          onClick={() => setUnreadOnly(v => !v)}
          className={cn(
            "rounded-full px-3 py-1.5 text-sm font-medium transition-colors",
            unreadOnly
              ? "bg-amber-500/10 text-amber-600"
              : "bg-muted text-muted-foreground hover:bg-accent"
          )}
        >
          仅看未读
        </button>
      </div>

      {/* 消息列表 */}
      {loading ? (
        <div className="flex items-center justify-center py-20">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
        </div>
      ) : list.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-border bg-card py-16 text-sm text-muted-foreground">
          <Inbox className="h-10 w-10 opacity-40" />
          {t("common.noData")}
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
          <div className="divide-y divide-border">
            {list.map((m) => {
              const cfg = getCategoryConfig(m.category)
              const Icon = cfg.icon
              return (
                <button
                  key={m.id}
                  type="button"
                  onClick={() => handleMarkRead(m)}
                  className={cn(
                    "flex w-full items-start gap-3 p-4 text-left transition-colors hover:bg-muted/30",
                    !m.is_read && "bg-primary/[0.03]"
                  )}
                >
                  {/* 图标 */}
                  <span className={cn("flex h-10 w-10 shrink-0 items-center justify-center rounded-full", cfg.cls)}>
                    <Icon className="h-5 w-5" />
                  </span>

                  {/* 内容 */}
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      {!m.is_read && (
                        <span className="h-2 w-2 shrink-0 rounded-full bg-red-500" />
                      )}
                      <p className={cn(
                        "truncate text-sm",
                        m.is_read ? "font-medium text-foreground" : "font-semibold text-foreground"
                      )}>
                        {m.title}
                      </p>
                      <span className={cn(
                        "shrink-0 rounded-full px-1.5 py-0.5 text-[10px] font-medium",
                        cfg.cls
                      )}>
                        {cfg.label}
                      </span>
                    </div>
                    <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{m.content}</p>
                    <p className="mt-1 text-[11px] text-muted-foreground">{fmtDate(m.created_at)}</p>
                  </div>

                  {/* 未读标记可点击已读 */}
                  {!m.is_read && (
                    <span className="flex shrink-0 items-center gap-1 rounded-md bg-muted px-2 py-1 text-[11px] font-medium text-muted-foreground">
                      <Check className="h-3 w-3" />
                      已读
                    </span>
                  )}
                </button>
              )
            })}
          </div>

          {/* 分页 */}
          {total > PAGE_SIZE && (
            <div className="flex items-center justify-between border-t border-border px-4 py-3">
              <span className="text-xs text-muted-foreground">
                共 {total} 条，第 {page}/{totalPages} 页
              </span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  disabled={page <= 1}
                  onClick={() => setPage(p => Math.max(1, p - 1))}
                  className="inline-flex h-8 items-center gap-1 rounded-md border border-input px-2.5 text-xs font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
                >
                  <ChevronLeft className="h-3.5 w-3.5" />
                  {t("common.prev")}
                </button>
                <button
                  type="button"
                  disabled={page >= totalPages}
                  onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                  className="inline-flex h-8 items-center gap-1 rounded-md border border-input px-2.5 text-xs font-medium text-foreground transition-colors hover:bg-accent disabled:opacity-50"
                >
                  {t("common.next")}
                  <ChevronRight className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

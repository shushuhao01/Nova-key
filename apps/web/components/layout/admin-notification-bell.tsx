"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import { Bell, CheckCheck, ChevronDown, ChevronUp, Loader2, Trash2 } from "lucide-react"
import { cn } from "@/lib/utils"
import { toast } from "sonner"
import { adminNotificationApi } from "@/services/api"
import { confirmDialog } from "@/components/ui/confirm-dialog"
import type { SystemMessageItem } from "@/types"

const BELL_POS_KEY = "admin.notificationBell.pos"
const BELL_SIZE = 40

function formatTime(iso: string): string {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const diff = Date.now() - d.getTime()
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) return "刚刚"
  if (diff < hour) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  const pad = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function typeLabel(type: string): string {
  switch (type) {
    case "ORDER": return "订单"
    case "USER": return "用户"
    case "SYSTEM": return "系统"
    case "REPORT": return "报表"
    default: return type || "通知"
  }
}

/** 管理后台消息铃铛：未读角标 + 下拉消息列表（详情/已读/清空），支持拖动并记忆位置 */
export function AdminNotificationBell() {
  const [open, setOpen] = useState(false)
  const [unread, setUnread] = useState(0)
  const [messages, setMessages] = useState<SystemMessageItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const wrapRef = useRef<HTMLDivElement>(null)

  // 铃铛位置（左上角坐标）：默认右下角，拖动后写入 localStorage 记忆
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null)
  const dragRef = useRef<{ startX: number; startY: number; origX: number; origY: number; moved: boolean } | null>(null)

  const clamp = (value: number, max: number) => Math.max(8, Math.min(value, Math.max(8, max)))

  // 初始化位置：读取记忆位置，没有则放在右上角（页面标题/刷新按钮下方，避免重叠）
  useEffect(() => {
    let saved: { x: number; y: number } | null = null
    try {
      const raw = localStorage.getItem(BELL_POS_KEY)
      if (raw) {
        const parsed = JSON.parse(raw) as { x?: number; y?: number }
        if (typeof parsed?.x === "number" && typeof parsed?.y === "number") {
          saved = { x: parsed.x, y: parsed.y }
        }
      }
    } catch {
      saved = null
    }
    const fallback = {
      x: window.innerWidth - BELL_SIZE - 20,
      y: 96,
    }
    const p = saved ?? fallback
    setPos({
      x: clamp(p.x, window.innerWidth - BELL_SIZE - 8),
      y: clamp(p.y, window.innerHeight - BELL_SIZE - 8),
    })
  }, [])

  // 窗口尺寸变化时把铃铛拉回可视范围
  useEffect(() => {
    const onResize = () => {
      setPos((p) =>
        p
          ? {
              x: clamp(p.x, window.innerWidth - BELL_SIZE - 8),
              y: clamp(p.y, window.innerHeight - BELL_SIZE - 8),
            }
          : p
      )
    }
    window.addEventListener("resize", onResize)
    return () => window.removeEventListener("resize", onResize)
  }, [])

  const handlePointerDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    if (!pos || e.button !== 0) return
    dragRef.current = { startX: e.clientX, startY: e.clientY, origX: pos.x, origY: pos.y, moved: false }
    e.currentTarget.setPointerCapture(e.pointerId)
  }

  const handlePointerMove = (e: React.PointerEvent<HTMLButtonElement>) => {
    const drag = dragRef.current
    if (!drag) return
    const dx = e.clientX - drag.startX
    const dy = e.clientY - drag.startY
    if (!drag.moved && Math.abs(dx) + Math.abs(dy) < 4) return
    drag.moved = true
    setPos({
      x: clamp(drag.origX + dx, window.innerWidth - BELL_SIZE - 8),
      y: clamp(drag.origY + dy, window.innerHeight - BELL_SIZE - 8),
    })
  }

  const handlePointerUp = () => {
    const drag = dragRef.current
    dragRef.current = null
    if (!drag) return
    if (drag.moved) {
      // 拖动结束：记忆最后位置
      setPos((p) => {
        if (p) {
          try {
            localStorage.setItem(BELL_POS_KEY, JSON.stringify(p))
          } catch {
            // 忽略存储失败
          }
        }
        return p
      })
      return
    }
    handleToggle()
  }


  const refreshUnread = useCallback(async () => {
    try {
      const { count } = await adminNotificationApi.getUnreadCount()
      setUnread(count)
    } catch {
      // 静默：后台接口暂不可用时不影响页面
    }
  }, [])

  const loadMessages = useCallback(async () => {
    setLoading(true)
    try {
      const data = await adminNotificationApi.getMessages({ page: 1, page_size: 20 })
      setMessages(data.list)
      setTotal(data.pagination.total)
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "系统消息加载失败")
    } finally {
      setLoading(false)
    }
  }, [])

  // 轮询未读数（30s），保证角标实时
  useEffect(() => {
    refreshUnread()
    const timer = setInterval(refreshUnread, 30000)
    return () => clearInterval(timer)
  }, [refreshUnread])

  // 点击外部关闭
  useEffect(() => {
    if (!open) return
    const onMouseDown = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener("mousedown", onMouseDown)
    return () => document.removeEventListener("mousedown", onMouseDown)
  }, [open])

  const handleToggle = () => {
    const next = !open
    setOpen(next)
    if (next) {
      loadMessages()
      refreshUnread()
    }
  }

  const handleMarkRead = async (id: string) => {
    try {
      await adminNotificationApi.markRead(id)
      setMessages(prev => prev.map(m => (m.id === id ? { ...m, read: true } : m)))
      setUnread(prev => Math.max(0, prev - 1))
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  const handleMarkAllRead = async () => {
    try {
      await adminNotificationApi.markAllRead()
      setMessages(prev => prev.map(m => ({ ...m, read: true })))
      setUnread(0)
      toast.success("已全部标记为已读")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  const handleClear = async () => {
    if (!(await confirmDialog({
      title: "清空系统消息",
      description: "确认清空全部系统消息？此操作不可恢复。",
      confirmText: "清空",
      danger: true,
    }))) return
    try {
      await adminNotificationApi.clearMessages()
      setMessages([])
      setTotal(0)
      setUnread(0)
      toast.success("已清空系统消息")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  if (!pos) return null

  // 下拉面板方向：靠近屏幕底部时向上展开，靠近右侧时右对齐
  const openUp = pos.y > window.innerHeight / 2
  const alignRight = pos.x + BELL_SIZE / 2 > window.innerWidth / 2

  return (
    <div
      ref={wrapRef}
      className="fixed z-50"
      style={{ left: `${pos.x}px`, top: `${pos.y}px` }}
    >
      <button
        type="button"
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={() => { dragRef.current = null }}
        className={cn(
          "relative flex h-10 w-10 cursor-grab touch-none items-center justify-center rounded-lg border border-border bg-card text-muted-foreground shadow-sm transition-colors hover:text-foreground active:cursor-grabbing",
          open && "text-foreground"
        )}
        title="系统消息（可拖动到任意位置，位置会自动记忆）"
        aria-label="系统消息"
      >
        <Bell className="h-5 w-5" />
        {unread > 0 && (
          <span className="absolute -right-1.5 -top-1.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-red-500 px-1 text-[10px] font-bold text-white">
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </button>

      {open && (
        <div
          className={cn(
            "absolute w-[min(92vw,420px)] overflow-hidden rounded-xl border border-border bg-card shadow-xl",
            openUp ? "bottom-12" : "top-12",
            alignRight ? "right-0" : "left-0"
          )}
        >
          {/* 头部 */}
          <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-3">
            <div className="flex items-center gap-2">
              <Bell className="h-4 w-4 text-primary" />
              <span className="text-sm font-semibold text-foreground">系统消息</span>
              {unread > 0 && (
                <span className="rounded-full bg-red-500/10 px-1.5 py-0.5 text-xs font-medium text-red-500">{unread} 未读</span>
              )}
            </div>
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={handleMarkAllRead}
                disabled={unread === 0}
                className="flex h-7 items-center gap-1 rounded-md px-2 text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-40"
              >
                <CheckCheck className="h-3.5 w-3.5" />
                全部已读
              </button>
              <button
                type="button"
                onClick={handleClear}
                disabled={total === 0}
                className="flex h-7 items-center gap-1 rounded-md px-2 text-xs text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive disabled:opacity-40"
              >
                <Trash2 className="h-3.5 w-3.5" />
                清空
              </button>
            </div>
          </div>

          {/* 列表 */}
          <div className="max-h-[55vh] overflow-y-auto">
            {loading && messages.length === 0 ? (
              <div className="flex h-32 items-center justify-center">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              </div>
            ) : messages.length === 0 ? (
              <div className="flex h-32 flex-col items-center justify-center gap-2 text-muted-foreground">
                <Bell className="h-8 w-8 opacity-40" />
                <span className="text-xs">暂无系统消息</span>
              </div>
            ) : (
              messages.map((m) => (
                <div
                  key={m.id}
                  className={cn(
                    "border-b border-border/60 px-4 py-3 last:border-b-0 transition-colors",
                    !m.read && "bg-primary/5"
                  )}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        {!m.read && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-red-500" />}
                        <span className="truncate text-sm font-medium text-foreground">{m.title}</span>
                      </div>
                      <div className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
                        <span className="rounded bg-muted px-1 py-0.5">{typeLabel(m.message_type)}</span>
                        <span>{formatTime(m.created_at)}</span>
                      </div>
                    </div>
                    <button
                      type="button"
                      className="mt-0.5 shrink-0 rounded p-0.5 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                      onClick={() => setExpandedId(expandedId === m.id ? null : m.id)}
                      aria-label={expandedId === m.id ? "收起详情" : "展开详情"}
                    >
                      {expandedId === m.id ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                    </button>
                  </div>
                  {expandedId === m.id && (
                    <p className="mt-2 whitespace-pre-wrap rounded-lg bg-muted/50 p-3 text-xs leading-relaxed text-foreground">
                      {m.content}
                    </p>
                  )}
                  <div className="mt-1.5 flex justify-end">
                    {!m.read ? (
                      <button
                        type="button"
                        onClick={() => handleMarkRead(m.id)}
                        className="text-xs text-primary transition-colors hover:underline"
                      >
                        标记已读
                      </button>
                    ) : (
                      <button
                        type="button"
                        onClick={() => setExpandedId(expandedId === m.id ? null : m.id)}
                        className="text-xs text-muted-foreground transition-colors hover:underline"
                      >
                        {expandedId === m.id ? "收起详情" : "查看详情"}
                      </button>
                    )}
                  </div>
                </div>
              ))
            )}
          </div>

          {/* 底部 */}
          <div className="border-t border-border bg-muted/30 px-4 py-2 text-center text-xs text-muted-foreground">
            共 {total} 条系统消息
          </div>
        </div>
      )}
    </div>
  )
}

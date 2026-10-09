"use client"

import { useEffect } from "react"
import { usePathname } from "next/navigation"

const VISITOR_KEY = "visit_visitor_id"

/** 读取或生成持久化的匿名访客标识（localStorage 不可用时降级为内存随机值） */
function getVisitorId(): string {
  try {
    let id = localStorage.getItem(VISITOR_KEY)
    if (!id) {
      id =
        typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
          ? crypto.randomUUID()
          : `${Date.now()}-${Math.random().toString(36).slice(2)}`
      localStorage.setItem(VISITOR_KEY, id)
    }
    return id
  } catch {
    return `${Date.now()}-${Math.random().toString(36).slice(2)}`
  }
}

/** 仅返回跨域来源页，同源跳转视为站内访问（返回 null） */
function getExternalReferer(): string | null {
  const ref = document.referrer
  if (!ref) return null
  try {
    const url = new URL(ref)
    if (url.origin === window.location.origin) return null
    return ref
  } catch {
    return ref
  }
}

export function VisitTracker() {
  const pathname = usePathname()

  useEffect(() => {
    // 用 window.location 读取完整路径，避免 useSearchParams 触发 Suspense 边界要求
    const path =
      typeof window !== "undefined" && window.location.search
        ? `${window.location.pathname}${window.location.search}`
        : pathname

    const payload = {
      path,
      referer: getExternalReferer(),
      visitor_id: getVisitorId(),
      screen: `${window.screen.width}x${window.screen.height}`,
      lang: navigator.language || null,
    }

    const headers: Record<string, string> = { "Content-Type": "application/json" }
    try {
      const token = localStorage.getItem("auth_token")
      if (token) headers["Authorization"] = `Bearer ${token}`
    } catch {
      // 忽略：匿名访问
    }

    // keepalive 支持自定义请求头（sendBeacon 无法携带 Authorization），失败不影响用户体验
    fetch("/api/visit/track", {
      method: "POST",
      headers,
      body: JSON.stringify(payload),
      keepalive: true,
    }).catch(() => {
      // fire-and-forget：采集失败不影响用户体验
    })
  }, [pathname])

  return null
}

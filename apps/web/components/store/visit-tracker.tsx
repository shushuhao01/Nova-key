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

const CHANNEL_COOKIE = "ch_ref"

/** 读取指定 Cookie 值 */
function readCookie(name: string): string | null {
  try {
    const match = document.cookie.match(
      new RegExp("(?:^|; )" + name.replace(/([.$?*|{}()[\]\\/+^])/g, "\\$1") + "=([^;]*)")
    )
    return match ? decodeURIComponent(match[1]) : null
  } catch {
    return null
  }
}

/** 渠道码归一化：去空白、转小写、限长 32（与后端一致） */
function normalizeChannel(value: string | null): string | null {
  if (!value) return null
  const v = value.trim().toLowerCase()
  if (!v) return null
  return v.length <= 32 ? v : v.slice(0, 32)
}

/**
 * 解析渠道码：
 * 1. URL 参数 ?ch= / ?utm_source=（新进入，优先并回写 Cookie）
 * 2. Cookie ch_ref（由 /c/{code} 跳转页写入，30 天有效）
 */
function resolveChannelCode(): string | null {
  try {
    const sp = new URLSearchParams(window.location.search)
    const fromUrl = normalizeChannel(sp.get("ch") || sp.get("utm_source"))
    if (fromUrl) {
      const maxAge = 30 * 24 * 60 * 60
      document.cookie = `${CHANNEL_COOKIE}=${encodeURIComponent(fromUrl)}; path=/; max-age=${maxAge}; SameSite=Lax`
      return fromUrl
    }
  } catch {
    // 忽略：URL 解析失败
  }
  return normalizeChannel(readCookie(CHANNEL_COOKIE))
}

export function VisitTracker() {
  const pathname = usePathname()

  useEffect(() => {
    // 渠道短链跳转页（/c/{code}）不计入访问统计：点击已由服务端 resolve 记录，此页仅做跳转
    if (typeof window !== "undefined" && window.location.pathname.startsWith("/c/")) return

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
      channel_code: resolveChannelCode(),
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

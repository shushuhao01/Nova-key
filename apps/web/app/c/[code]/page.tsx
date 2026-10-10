"use client"

import { useEffect, useRef, useState, use } from "react"
import { useRouter } from "next/navigation"
import { channelApi } from "@/services/api"

/**
 * 渠道短链跳转页
 * 用户访问 /c/{code} → 调用后端解析渠道并记录点击 → 写入 ch_ref Cookie → 跳转落地页
 */
export default function ChannelRedirectPage({ params }: { params: Promise<{ code: string }> }) {
  const router = useRouter()
  const [error, setError] = useState<string | null>(null)
  const redirected = useRef(false)
  const { code } = use(params)

  useEffect(() => {
    if (redirected.current) return
    redirected.current = true

    if (!code) {
      router.replace("/")
      return
    }

    channelApi
      .resolve(code)
      .then((data) => {
        // 写入渠道归因 Cookie（30 天有效；SameSite=Lax 兼容 http/https）
        const channelCode = data?.channel_code || data?.code || code
        if (channelCode) {
          const maxAge = 30 * 24 * 60 * 60
          document.cookie = `ch_ref=${encodeURIComponent(channelCode)}; path=/; max-age=${maxAge}; SameSite=Lax`
        }
        const target = data?.target_path && data.target_path.trim() ? data.target_path : "/"
        router.replace(target)
      })
      .catch(() => {
        setError("渠道链接无效或已停用")
        setTimeout(() => router.replace("/"), 2000)
      })
  }, [code, router])

  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4">
      {error ? (
        <>
          <p className="text-sm text-muted-foreground">{error}</p>
          <p className="text-xs text-muted-foreground">正在跳转首页...</p>
        </>
      ) : (
        <>
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-primary border-t-transparent" />
          <p className="text-sm text-muted-foreground">正在跳转...</p>
        </>
      )}
    </div>
  )
}

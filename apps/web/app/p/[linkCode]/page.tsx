"use client"

import { useEffect, useRef, useState, use } from "react"
import { useRouter } from "next/navigation"
import { distributionApi } from "@/services/api"

/**
 * 推广链接跳转页
 * 用户访问 /p/{linkCode} → 调用后端解析 → 设置 Cookie → 跳转商品页或首页
 */
export default function PromotionRedirectPage({ params }: { params: Promise<{ linkCode: string }> }) {
  const router = useRouter()
  const [error, setError] = useState<string | null>(null)
  const redirected = useRef(false)
  const { linkCode } = use(params)

  useEffect(() => {
    if (redirected.current) return
    redirected.current = true

    if (!linkCode) {
      router.replace("/")
      return
    }

    distributionApi.resolveLink(linkCode)
      .then((data: any) => {
        // 设置推广员 Cookie（30天有效，Secure 确保仅 HTTPS 传输）
        if (data?.distributor_id) {
          const maxAge = 30 * 24 * 60 * 60
          document.cookie = `dist_ref=${data.distributor_id}; path=/; max-age=${maxAge}; SameSite=Lax; Secure`
        }
        // 记录推广链接 ID（用于下单时关联统计 paid_count/total_sales/total_commission）
        if (data?.promotion_link_id) {
          const maxAge = 30 * 24 * 60 * 60
          document.cookie = `dist_link=${data.promotion_link_id}; path=/; max-age=${maxAge}; SameSite=Lax; Secure`
        }
        // 根据链接类型跳转
        if (data?.product_id) {
          router.replace(`/product/${data.product_id}`)
        } else {
          router.replace("/")
        }
      })
      .catch(() => {
        setError("推广链接无效或已失效")
        setTimeout(() => router.replace("/"), 2000)
      })
  }, [linkCode, router])

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

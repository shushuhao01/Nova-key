"use client"

import { useEffect, useRef, useState, use } from "react"
import { useRouter } from "next/navigation"
import { productApi } from "@/services/api"

/**
 * 商品短链跳转页
 * 用户访问 /s/{shortCode} → 调用后端解析商品 → 跳转商品详情页（不绑定任何分销推荐关系）
 */
export default function ProductShortLinkPage({ params }: { params: Promise<{ code: string }> }) {
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

    productApi.resolveShortCode(code)
      .then((data: any) => {
        if (data?.product_id) {
          router.replace(`/product/${data.product_id}`)
        } else {
          router.replace("/")
        }
      })
      .catch(() => {
        setError("商品链接无效或已失效")
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

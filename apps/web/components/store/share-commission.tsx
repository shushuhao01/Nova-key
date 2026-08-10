"use client"

import { useState, useEffect, useCallback } from "react"
import { Share2, X, Copy, Check, Image as ImageIcon } from "lucide-react"
import { cn } from "@/lib/utils"
import { useAuth, useLocale } from "@/lib/context"
import { toast } from "sonner"
import { distributionApi, distributorApi, getApiErrorMessage } from "@/services/api"
import { PosterModal } from "@/components/store/poster-modal"

interface ShareCommissionBadgeProps {
  productId: string
  productTitle: string
  productPrice: number
  className?: string
}

/**
 * 分享赚佣金标签 — 显示在商品卡片上
 * - 未登录/非分销员：显示最高预估佣金
 * - 已登录分销员：显示实际佣金
 */
export function ShareCommissionBadge({ productId, productPrice, className }: ShareCommissionBadgeProps) {
  const { user } = useAuth()
  const [commission, setCommission] = useState<number | null>(null)
  const [maxCommission, setMaxCommission] = useState<number | null>(null)

  useEffect(() => {
    let cancelled = false
    distributionApi.commissionPreview([productId]).then(data => {
      if (cancelled || !data?.items?.length) return
      const item = data.items[0]
      if (item.is_excluded || data.is_distribution_enabled === false) {
        setCommission(null)
        setMaxCommission(null)
        return
      }
      setCommission(item.commission_preview || 0)
      setMaxCommission(item.max_commission || 0)
    }).catch(() => {
      // 静默失败
    })
    return () => { cancelled = true }
  }, [productId])

  // 未登录或非分销员 → 显示最高佣金
  // 已登录分销员 → 显示实际佣金
  const amount = user ? commission : maxCommission
  if (!amount || amount <= 0) return null

  return (
    <span className={cn(
      "inline-flex items-center gap-0.5 rounded bg-emerald-100 px-1.5 py-0.5 text-xs font-semibold text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-300",
      className
    )}>
      <Share2 className="h-3 w-3" />
      分享赚 ¥{amount.toFixed(2)}
    </span>
  )
}

interface ShareCommissionButtonProps {
  productId: string
  productTitle: string
  productPrice: number
}

/**
 * 分享赚佣金按钮 — 显示在商品详情页
 * 点击弹出推广面板
 */
export function ShareCommissionButton({ productId, productTitle, productPrice }: ShareCommissionButtonProps) {
  const { user } = useAuth()
  const { t } = useLocale()
  const [commission, setCommission] = useState<number | null>(null)
  const [maxCommission, setMaxCommission] = useState<number | null>(null)
  const [modalOpen, setModalOpen] = useState(false)
  const [linkUrl, setLinkUrl] = useState("")
  const [loading, setLoading] = useState(false)
  const [copied, setCopied] = useState(false)
  // 已审核分销员 → 显示"生成推广海报"（与分销中心商品海报一致）
  const [isDistributor, setIsDistributor] = useState(false)
  const [poster, setPoster] = useState<any>(null)
  const [posterLoading, setPosterLoading] = useState(false)

  useEffect(() => {
    let cancelled = false
    distributionApi.commissionPreview([productId]).then(data => {
      if (cancelled || !data?.items?.length) return
      const item = data.items[0]
      if (item.is_excluded || data.is_distribution_enabled === false) return
      setCommission(item.commission_preview || 0)
      setMaxCommission(item.max_commission || 0)
    }).catch(() => {})
    return () => { cancelled = true }
  }, [productId])

  const amount = user ? commission : maxCommission

  // ⚠️ hooks 必须在条件 return 之前声明（React 要求 hooks 调用顺序恒定）
  const handleShare = useCallback(async () => {
    if (!user) {
      toast.info("请先登录后再分享赚佣金")
      return
    }
    setModalOpen(true)
    setLoading(true)
    try {
      const result = await distributorApi.generateLink(productId)
      const url = `${window.location.origin}/p/${result.link_code}`
      setLinkUrl(url)
      // 检测是否已审核分销员（决定是否展示"生成推广海报"入口，海报与分销中心一致）
      try {
        const profile = await distributorApi.getProfile()
        setIsDistributor(profile?.status === "APPROVED")
      } catch {
        setIsDistributor(false)
      }
    } catch (err) {
      toast.error(getApiErrorMessage(err, t) || "生成推广链接失败")
      setModalOpen(false)
    } finally {
      setLoading(false)
    }
  }, [user, productId, t])

  // 生成商品推广海报（复用分销中心同款 Canvas 海报）
  const handleGeneratePoster = useCallback(async () => {
    setPosterLoading(true)
    try {
      const res = await distributorApi.generateProductPoster(productId)
      setPoster(res)
    } catch (err) {
      toast.error(getApiErrorMessage(err, t) || "生成海报失败")
    } finally {
      setPosterLoading(false)
    }
  }, [productId, t])

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(linkUrl)
    setCopied(true)
    toast.success("链接已复制")
    setTimeout(() => setCopied(false), 2000)
  }, [linkUrl])

  if (!amount || amount <= 0) return null

  return (
    <>
      <button
        onClick={handleShare}
        className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-sm font-semibold text-emerald-700 transition-colors hover:bg-emerald-100 dark:border-emerald-800 dark:bg-emerald-900/30 dark:text-emerald-300 dark:hover:bg-emerald-900/50"
      >
        <Share2 className="h-4 w-4" />
        分享赚 ¥{amount.toFixed(2)}
      </button>

      {modalOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={() => setModalOpen(false)}
        >
          <div
            className="w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-xl"
            onClick={e => e.stopPropagation()}
          >
            <div className="mb-4 flex items-center justify-between">
              <h3 className="text-lg font-bold text-foreground">推广分享</h3>
              <button onClick={() => setModalOpen(false)} className="text-muted-foreground hover:text-foreground">
                <X className="h-5 w-5" />
              </button>
            </div>

            <p className="mb-4 text-sm text-muted-foreground">
              商品：{productTitle}（¥{productPrice.toFixed(2)}）
            </p>

            {loading ? (
              <div className="flex items-center justify-center py-8">
                <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              </div>
            ) : (
              <>
                {/* 推广链接 */}
                <div className="mb-4">
                  <label className="mb-1 block text-xs font-medium text-muted-foreground">推广链接</label>
                  <div className="flex gap-2">
                    <input
                      type="text"
                      readOnly
                      value={linkUrl}
                      className="flex-1 rounded-lg border border-border bg-background px-3 py-2 text-sm text-foreground"
                    />
                    <button
                      onClick={handleCopy}
                      className="inline-flex items-center gap-1 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground hover:brightness-110"
                    >
                      {copied ? <Check className="h-4 w-4" /> : <Copy className="h-4 w-4" />}
                    </button>
                  </div>
                </div>

                {/* 二维码 */}
                <div className="flex flex-col items-center gap-2">
                  <label className="text-xs font-medium text-muted-foreground">推广二维码</label>
                  <div className="rounded-lg border border-border bg-white p-3">
                    <img
                      src={`/qr-image?url=${encodeURIComponent(linkUrl)}&size=200`}
                      alt="推广二维码"
                      className="h-40 w-40"
                    />
                  </div>
                  <p className="text-xs text-muted-foreground">扫码或复制链接分享给好友</p>
                </div>

                {/* 已审核分销员：生成与分销中心一致的商品推广海报 */}
                {isDistributor && (
                  <button
                    onClick={handleGeneratePoster}
                    disabled={posterLoading}
                    className="mt-4 inline-flex w-full items-center justify-center gap-1.5 rounded-lg border border-primary/30 bg-primary/5 px-3 py-2.5 text-sm font-semibold text-primary transition-colors hover:bg-primary/10 disabled:opacity-50"
                  >
                    {posterLoading ? (
                      <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    ) : (
                      <ImageIcon className="h-4 w-4" />
                    )}
                    生成推广海报
                  </button>
                )}
              </>
            )}
          </div>
        </div>
      )}

      {/* 商品推广海报（与分销中心一致） */}
      {poster && (
        <PosterModal data={poster} type="product" onClose={() => setPoster(null)} />
      )}
    </>
  )
}

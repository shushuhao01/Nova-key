"use client"

import { useState, useEffect, useCallback } from "react"
import { useRouter } from "next/navigation"
import { Share2 } from "lucide-react"
import { useAuth, useLocale } from "@/lib/context"
import { toast } from "sonner"
import { distributionApi, distributorApi, getApiErrorMessage } from "@/services/api"
import { PosterModal } from "@/components/store/poster-modal"

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
  const router = useRouter()
  const [commission, setCommission] = useState<number | null>(null)
  const [maxCommission, setMaxCommission] = useState<number | null>(null)
  // 已审核分销员 → 点击直接弹出与分销中心一致的商品海报（可下载/分享/复制链接）
  const [poster, setPoster] = useState<any>(null)
  const [busy, setBusy] = useState(false)
  // 非分销员 → 提示申请分销员资格
  const [applyPromptOpen, setApplyPromptOpen] = useState(false)

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
    setBusy(true)
    try {
      // 检测是否已审核分销员：是 → 直接弹出与分销中心一致的商品海报（下载/分享/复制链接齐全）
      let profile: any = null
      try { profile = await distributorApi.getProfile() } catch { profile = null }
      if (profile?.status === "APPROVED") {
        const res = await distributorApi.generateProductPoster(productId)
        setPoster(res)
        return
      }
      // 非分销员 → 提示申请分销员资格（跳转分销中心申请）
      setApplyPromptOpen(true)
    } catch (err) {
      toast.error(getApiErrorMessage(err, t) || "操作失败，请稍后再试")
    } finally {
      setBusy(false)
    }
  }, [user, productId, t])

  if (!amount || amount <= 0) return null

  return (
    <>
      <button
        onClick={handleShare}
        disabled={busy}
        className="inline-flex items-center gap-1.5 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-1.5 text-sm font-semibold text-emerald-700 transition-colors hover:bg-emerald-100 disabled:opacity-60 dark:border-emerald-800 dark:bg-emerald-900/30 dark:text-emerald-300 dark:hover:bg-emerald-900/50"
      >
        {busy ? (
          <div className="h-4 w-4 animate-spin rounded-full border-2 border-emerald-600 border-t-transparent" />
        ) : (
          <Share2 className="h-4 w-4" />
        )}
        分享赚 ¥{amount.toFixed(2)}
      </button>

      {/* 非分销员：申请分销员资格提示 */}
      {applyPromptOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={() => setApplyPromptOpen(false)}
        >
          <div
            className="w-full max-w-sm rounded-xl border border-border bg-card p-6 shadow-xl"
            onClick={e => e.stopPropagation()}
          >
            <h3 className="text-lg font-bold text-foreground">申请成为分销员</h3>
            <p className="mt-2 text-sm leading-relaxed text-muted-foreground">
              成为分销员后，即可生成精美商品海报分享赚佣金，好友通过您的链接下单，您将获得佣金奖励。
            </p>
            <div className="mt-5 flex gap-3">
              <button
                onClick={() => setApplyPromptOpen(false)}
                className="flex-1 rounded-lg border border-input px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-accent"
              >
                取消
              </button>
              <button
                onClick={() => { setApplyPromptOpen(false); router.push("/my/distribution") }}
                className="flex-1 rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
              >
                去申请
              </button>
            </div>
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

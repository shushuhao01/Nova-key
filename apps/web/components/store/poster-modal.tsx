"use client"

import { useEffect, useRef, useState } from "react"
import { Download, Share2, Copy, X, Image as ImageIcon } from "lucide-react"
import { toast } from "sonner"

/**
 * 推广海报弹窗（Canvas 合成）— 分销中心 / 商品详情页共用
 * type: product=商品海报 / store=全店海报 / invite=邀请海报
 */
export function PosterModal({
  data, type, onClose,
}: {
  data: any
  type: "product" | "store" | "invite"
  onClose: () => void
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [drawing, setDrawing] = useState(true)
  const [fallback, setFallback] = useState(false)

  const linkUrl: string = data?.link_url || ""

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext("2d")
    if (!ctx) return

    let cancelled = false
    const W = 600
    const H = type === "product" ? 1080 : 1060
    canvas.width = W
    canvas.height = H

    const truncate = (s: string, n: number) => (s && s.length > n ? `${s.slice(0, n)}…` : s || "")

    // 按最大宽度换行（返回多行文本）
    const wrapLines = (text: string, maxWidth: number) => {
      const chars = Array.from(text || "")
      const lines: string[] = []
      let cur = ""
      for (const ch of chars) {
        const test = cur + ch
        if (ctx.measureText(test).width > maxWidth && cur) {
          lines.push(cur)
          cur = ch
        } else {
          cur = test
        }
      }
      if (cur) lines.push(cur)
      return lines
    }

    // 最多展示 n 行，超出部分省略号截断
    const clampLines = (lines: string[], n: number) =>
      lines.slice(0, n).map((l, i) => (i === n - 1 && lines.length > n ? `${l.slice(0, -1)}…` : l))

    const roundRect = (x: number, y: number, w: number, h: number, r: number) => {
      ctx.beginPath()
      if (typeof ctx.roundRect === "function") {
        ctx.roundRect(x, y, w, h, r)
      } else {
        ctx.rect(x, y, w, h)
      }
      ctx.fill()
    }

    const paint = (images: Record<string, HTMLImageElement | null>, qr: HTMLImageElement | null) => {
      if (cancelled) return
      ctx.textBaseline = "alphabetic"
      ctx.textAlign = "center"
      ctx.fillStyle = "#ffffff"
      ctx.fillRect(0, 0, W, H)

      // 顶部品牌条（橙色渐变，仅展示店铺名，不显示推广员ID/佣金）
      const grad = ctx.createLinearGradient(0, 0, W, 0)
      grad.addColorStop(0, "#f97316")
      grad.addColorStop(1, "#f43f5e")
      ctx.fillStyle = grad
      ctx.fillRect(0, 0, W, 110)
      ctx.fillStyle = "#ffffff"
      ctx.font = "bold 34px sans-serif"
      ctx.fillText(truncate(data?.store_name || "精选好物", 16), W / 2, 64)
      ctx.font = "15px sans-serif"
      ctx.fillText(
        type === "product" ? "好物推荐 · 扫码即购"
          : type === "invite" ? "邀请好友 · 一起赚钱"
            : "精选好物 · 专属推荐",
        W / 2, 96)

      if (type === "product") {
        // 商品封面（居中圆角大图 + 柔和阴影 + 爆款角标）
        const cover = data?.cover_url ? images[data.cover_url] : undefined
        const size = 340
        const cy = 130
        const cx = (W - size) / 2
        ctx.save()
        ctx.shadowColor = "rgba(0,0,0,0.14)"
        ctx.shadowBlur = 26
        ctx.shadowOffsetY = 10
        ctx.fillStyle = "#ffffff"
        ctx.beginPath()
        ctx.roundRect(cx - 6, cy - 6, size + 12, size + 12, 22)
        ctx.fill()
        ctx.restore()
        ctx.fillStyle = "#f3f4f6"
        ctx.fillRect(cx, cy, size, size)
        if (cover) {
          const iw = cover.naturalWidth || size
          const ih = cover.naturalHeight || size
          const scale = Math.max(size / iw, size / ih)
          const dw = iw * scale
          const dh = ih * scale
          ctx.save()
          ctx.beginPath()
          ctx.roundRect(cx, cy, size, size, 18)
          ctx.clip()
          ctx.drawImage(cover, cx + (size - dw) / 2, cy + (size - dh) / 2, dw, dh)
          ctx.restore()
        } else {
          ctx.fillStyle = "#9ca3af"
          ctx.font = "18px sans-serif"
          ctx.fillText("（商品图片缺失）", W / 2, cy + size / 2)
        }
        // 右上角"爆款"角标
        const badgeW = 62
        const badgeH = 32
        ctx.save()
        ctx.beginPath()
        ctx.roundRect(cx + size - badgeW - 16, cy + 16, badgeW, badgeH, 10)
        ctx.fillStyle = "#ef4444"
        ctx.fill()
        ctx.restore()
        ctx.fillStyle = "#ffffff"
        ctx.font = "bold 16px sans-serif"
        ctx.fillText("爆款", cx + size - badgeW - 16 + badgeW / 2, cy + 16 + 23)

        // 商品名称（左对齐，最多两行）
        let ty = 516
        ctx.textAlign = "left"
        ctx.font = "bold 26px sans-serif"
        ctx.fillStyle = "#111827"
        clampLines(wrapLines(data?.product_title || "", W - 88), 2).forEach((l) => {
          ctx.fillText(l, 44, ty)
          ty += 42
        })

        // 单价：中间偏右，大号红色突出
        const priceY = 606
        ctx.font = "18px sans-serif"
        ctx.fillStyle = "#9ca3af"
        ctx.textAlign = "right"
        ctx.fillText("单价", 396, priceY + 6)
        ctx.fillStyle = "#ef4444"
        ctx.font = "bold 56px sans-serif"
        ctx.textAlign = "left"
        ctx.fillText(`¥${Number(data?.base_price || 0).toFixed(2)}`, 412, priceY + 12)
        ctx.textAlign = "center"

        // 明显促销词：橙色文字（无背景），位于底部橙色区域上方
        ctx.fillStyle = "#ea580c"
        ctx.font = "bold 26px sans-serif"
        ctx.fillText("正品保障 · 自动发货 · 售后无忧", W / 2, 690)

        // 底部橙色号召区：左侧引导词 + 右侧二维码
        const bandY = 760
        const bandH = 244
        const bandGrad = ctx.createLinearGradient(0, bandY, W, bandY)
        bandGrad.addColorStop(0, "#f97316")
        bandGrad.addColorStop(1, "#f43f5e")
        ctx.fillStyle = bandGrad
        ctx.fillRect(0, bandY, W, bandH)
        ctx.fillStyle = "#ffffff"
        ctx.textAlign = "left"
        ctx.font = "bold 31px sans-serif"
        ctx.fillText("扫码立即购买", 44, bandY + 86)
        ctx.font = "16px sans-serif"
        ctx.fillText("长按识别二维码 · 查看商品", 44, bandY + 128)
        const qsize = 152
        const qx = W - 44 - qsize
        const qy = bandY + (bandH - qsize) / 2
        ctx.save()
        ctx.shadowColor = "rgba(0,0,0,0.15)"
        ctx.shadowBlur = 14
        ctx.shadowOffsetY = 4
        ctx.beginPath()
        ctx.roundRect(qx - 8, qy - 8, qsize + 16, qsize + 16, 12)
        ctx.fillStyle = "#ffffff"
        ctx.fill()
        ctx.restore()
        if (qr) {
          ctx.drawImage(qr, qx, qy, qsize, qsize)
        } else {
          ctx.fillStyle = "#f3f4f6"
          ctx.fillRect(qx, qy, qsize, qsize)
        }
        ctx.textAlign = "center"

        // 底部商品推广链接
        ctx.fillStyle = "#9ca3af"
        ctx.font = "13px sans-serif"
        ctx.fillText(truncate(linkUrl, 60), W / 2, H - 26)
      } else if (type === "invite") {
        // 邀请海报：邀请好友加入分销，引导赚钱
        // 中部大徽章
        const badgeCX = W / 2
        const badgeCY = 244
        const badgeR = 72
        const badgeGrad = ctx.createLinearGradient(badgeCX - badgeR, badgeCY - badgeR, badgeCX + badgeR, badgeCY + badgeR)
        badgeGrad.addColorStop(0, "#f97316")
        badgeGrad.addColorStop(1, "#f43f5e")
        ctx.save()
        ctx.beginPath()
        ctx.arc(badgeCX, badgeCY, badgeR, 0, Math.PI * 2)
        ctx.fillStyle = badgeGrad
        ctx.fill()
        ctx.restore()
        ctx.fillStyle = "#ffffff"
        ctx.font = "bold 64px sans-serif"
        ctx.fillText("邀", badgeCX, badgeCY + 24)
        // 标题与副标题
        ctx.fillStyle = "#111827"
        ctx.font = "bold 34px sans-serif"
        ctx.fillText("成为分销员，分享赚佣金", W / 2, 376)
        ctx.fillStyle = "#6b7280"
        ctx.font = "16px sans-serif"
        ctx.fillText("自购省钱 · 分享赚钱 · 0 门槛加入", W / 2, 414)
        // 卖点列表
        const perks = [
          "分享商品给好友，成交即得佣金",
          "佣金实时到账，余额随时提现",
          "邀请好友加入，还能获取额外抽成",
        ]
        ctx.textAlign = "left"
        perks.forEach((pt, i) => {
          const py = 478 + i * 54
          ctx.beginPath()
          ctx.arc(W / 2 - 152, py - 8, 10, 0, Math.PI * 2)
          ctx.fillStyle = "#10b981"
          ctx.fill()
          ctx.fillStyle = "#ffffff"
          ctx.font = "bold 13px sans-serif"
          ctx.fillText("✓", W / 2 - 152, py - 3)
          ctx.fillStyle = "#374151"
          ctx.font = "19px sans-serif"
          ctx.fillText(pt, W / 2 - 124, py)
        })
        ctx.textAlign = "center"
        // 邀请码框（虚线）
        const codeBoxX = W / 2 - 200
        const codeBoxY = 662
        const codeBoxW = 400
        const codeBoxH = 106
        ctx.save()
        ctx.strokeStyle = "#fb923c"
        ctx.lineWidth = 2.5
        ctx.setLineDash([8, 6])
        ctx.beginPath()
        ctx.roundRect(codeBoxX, codeBoxY, codeBoxW, codeBoxH, 14)
        ctx.stroke()
        ctx.restore()
        ctx.setLineDash([])
        ctx.fillStyle = "#9ca3af"
        ctx.font = "15px sans-serif"
        ctx.fillText("我的邀请码", W / 2, codeBoxY + 32)
        ctx.fillStyle = "#ea580c"
        ctx.font = "bold 44px sans-serif"
        ctx.fillText(truncate(data?.invite_code || "——", 20), W / 2, codeBoxY + 86)
        // 扫码提示
        ctx.fillStyle = "#6b7280"
        ctx.font = "15px sans-serif"
        ctx.fillText("长按识别下方二维码，输入邀请码即刻加入", W / 2, 810)
        // 底部号召区：左侧号召行动 + 右侧二维码
        const ibandY = 838
        const ibandH = 164
        const ibandGrad = ctx.createLinearGradient(0, ibandY, W, ibandY)
        ibandGrad.addColorStop(0, "#f97316")
        ibandGrad.addColorStop(1, "#f43f5e")
        ctx.fillStyle = ibandGrad
        roundRect(0, ibandY, W, ibandH, 0)
        ctx.fillStyle = "#ffffff"
        ctx.textAlign = "left"
        ctx.font = "bold 30px sans-serif"
        ctx.fillText("扫码加入我的团队", 46, ibandY + 66)
        ctx.font = "17px sans-serif"
        ctx.fillText("一起开启赚钱之旅", 46, ibandY + 104)
        const iq = 120
        const iqX = W - 46 - iq
        const iqY = ibandY + (ibandH - iq) / 2
        ctx.fillStyle = "#ffffff"
        ctx.fillRect(iqX - 6, iqY - 6, iq + 12, iq + 12)
        if (qr) {
          ctx.drawImage(qr, iqX, iqY, iq, iq)
        }
        ctx.textAlign = "center"
        ctx.fillStyle = "#9ca3af"
        ctx.font = "13px sans-serif"
        ctx.fillText(truncate(linkUrl, 60), W / 2, H - 20)
      } else {
        // 全店海报：热销商品竖排卡片 + 促销词（不绘制中间大二维码，底部号召区保留唯一二维码）
        ctx.fillStyle = "#111827"
        ctx.font = "bold 26px sans-serif"
        ctx.fillText("热销推荐", W / 2, 148)
        ctx.fillStyle = "#9ca3af"
        ctx.font = "14px sans-serif"
        ctx.fillText("为你精选 3 款好物", W / 2, 174)

        const hot: any[] = (data?.hot_products || []).slice(0, 3)
        const cardX = 44
        const cardW = W - cardX * 2
        const cardH = 152
        const cardGap = 16
        const coverSize = 128
        const startY = 192
        if (hot.length === 0) {
          ctx.fillStyle = "#9ca3af"
          ctx.font = "18px sans-serif"
          ctx.fillText("（暂无推广商品）", W / 2, startY + 70)
        }
        hot.forEach((hp: any, i: number) => {
          const y = startY + i * (cardH + cardGap)
          // 卡片底色
          ctx.fillStyle = "#f9fafb"
          roundRect(cardX, y, cardW, cardH, 16)
          // 商品封面（左侧圆角图，放大）
          const img = hp.cover_url ? images[hp.cover_url] : undefined
          if (img) {
            const iw = img.naturalWidth || coverSize
            const ih = img.naturalHeight || coverSize
            const scale = Math.max(coverSize / iw, coverSize / ih)
            const dw = iw * scale
            const dh = ih * scale
            ctx.save()
            ctx.beginPath()
            ctx.roundRect(cardX + 12, y + 12, coverSize, coverSize, 12)
            ctx.clip()
            ctx.drawImage(img, cardX + 12 + (coverSize - dw) / 2, y + 12 + (coverSize - dh) / 2, dw, dh)
            ctx.restore()
          } else {
            ctx.fillStyle = "#e5e7eb"
            ctx.fillRect(cardX + 12, y + 12, coverSize, coverSize)
          }
          // 商品名（右侧，最多两行，左对齐；名称长自动换行）
          const nameX = cardX + coverSize + 30
          const nameMaxW = cardW - coverSize - 70
          ctx.textAlign = "left"
          ctx.fillStyle = "#111827"
          ctx.font = "bold 18px sans-serif"
          const nameLines = clampLines(wrapLines(hp.product_title || "", nameMaxW), 2)
          nameLines.forEach((l, li) => {
            ctx.fillText(l, nameX, y + 56 + li * 27)
          })
          // 价格（名称两行之后，卡片最右侧）
          ctx.fillStyle = "#ef4444"
          ctx.font = "bold 26px sans-serif"
          ctx.textAlign = "right"
          ctx.fillText(`¥${Number(hp.base_price || 0).toFixed(2)}`, cardX + cardW - 16, y + cardH - 24)
          // 热销角标
          const tagW = 48
          const tagH = 26
          const tagX = cardX + cardW - tagW - 16
          const tagY = y + 16
          ctx.save()
          ctx.beginPath()
          ctx.roundRect(tagX, tagY, tagW, tagH, 13)
          ctx.fillStyle = "rgba(244,63,94,0.12)"
          ctx.fill()
          ctx.restore()
          ctx.fillStyle = "#f43f5e"
          ctx.font = "bold 14px sans-serif"
          ctx.textAlign = "center"
          ctx.fillText(`TOP${i + 1}`, tagX + tagW / 2, tagY + 19)
          ctx.textAlign = "left"
        })
        ctx.textAlign = "center"

        // 促销词区
        const promoY = startY + hot.length * (cardH + cardGap) - cardGap + 22
        if (hot.length > 0) {
          ctx.strokeStyle = "#fde68a"
          ctx.lineWidth = 2
          ctx.beginPath()
          ctx.moveTo(W / 2 - 120, promoY - 4)
          ctx.lineTo(W / 2 + 120, promoY - 4)
          ctx.stroke()
          ctx.fillStyle = "#f97316"
          ctx.font = "bold 26px sans-serif"
          ctx.fillText("爆款直降 · 全场精选", W / 2, promoY + 34)
          ctx.fillStyle = "#6b7280"
          ctx.font = "15px sans-serif"
          ctx.fillText("扫描下方二维码，解锁你的专属优惠", W / 2, promoY + 66)
        }

        // 底部号召区：左侧号召行动 + 右侧小二维码
        const bandY = 806
        const bandH = 164
        const bandGrad = ctx.createLinearGradient(0, bandY, W, bandY)
        bandGrad.addColorStop(0, "#f97316")
        bandGrad.addColorStop(1, "#f43f5e")
        ctx.fillStyle = bandGrad
        roundRect(0, bandY, W, bandH, 0)
        ctx.fillStyle = "#ffffff"
        ctx.textAlign = "left"
        ctx.font = "bold 30px sans-serif"
        ctx.fillText("扫码进店", 46, bandY + 66)
        ctx.font = "17px sans-serif"
        ctx.fillText("专属好物 · 尽在掌握", 46, bandY + 104)
        const sq = 120
        const sqX = W - 46 - sq
        const sqY = bandY + (bandH - sq) / 2
        ctx.fillStyle = "#ffffff"
        ctx.fillRect(sqX - 6, sqY - 6, sq + 12, sq + 12)
        if (qr) {
          ctx.drawImage(qr, sqX, sqY, sq, sq)
        }
        // 底部专属链接
        ctx.textAlign = "center"
        ctx.fillStyle = "#9ca3af"
        ctx.font = "13px sans-serif"
        ctx.fillText(truncate(linkUrl, 60), W / 2, H - 20)
      }
    }

    // 收集所有图片 URL（去重后加载，失败降级为占位）
    const urls: string[] = []
    const pushUrl = (u?: string) => { if (u && !urls.includes(u)) urls.push(u) }
    pushUrl(data?.store_logo)
    pushUrl(data?.cover_url)
    ;(data?.hot_products || []).forEach((hp: any) => pushUrl(hp.cover_url))

    const images: Record<string, HTMLImageElement | null> = {}
    let loaded = 0
    const tryPaint = () => {
      if (loaded !== urls.length) return
      const qr = new Image()
      qr.crossOrigin = "anonymous"
      qr.onload = () => {
        if (cancelled) return
        try { paint(images, qr); setDrawing(false) } catch { setFallback(true); setDrawing(false) }
      }
      qr.onerror = () => {
        if (cancelled) return
        try { paint(images, null); setDrawing(false) } catch { setFallback(true); setDrawing(false) }
      }
      qr.src = `/qr-image?url=${encodeURIComponent(linkUrl)}&size=400`
    }
    if (urls.length === 0) {
      tryPaint()
      return
    }
    urls.forEach((u) => {
      const img = new Image()
      img.crossOrigin = "anonymous"
      img.onload = () => { images[u] = img; loaded++; tryPaint() }
      img.onerror = () => { images[u] = null; loaded++; tryPaint() }
      img.src = u
    })

    return () => { cancelled = true }
  }, [data, type, linkUrl])

  const downloadPoster = () => {
    const canvas = canvasRef.current
    if (!canvas) return
    try {
      const a = document.createElement("a")
      a.href = canvas.toDataURL("image/png")
      a.download = `推广海报_${type === "product" ? (data?.product_title || "商品") : type === "invite" ? "邀请" : "全店"}.png`
      a.click()
      toast.success("海报已下载")
    } catch {
      toast.error("海报图片跨域受限，导出失败，请使用「复制链接」分享")
    }
  }

  const sharePoster = async () => {
    const canvas = canvasRef.current
    if (!canvas) return
    try {
      const blob: Blob | null = await new Promise((res) => canvas.toBlob(res, "image/png"))
      if (!blob) return
      const file = new File([blob], "poster.png", { type: "image/png" })
      if (navigator.share && navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: "推广海报" })
      } else {
        const a = document.createElement("a")
        a.href = URL.createObjectURL(blob)
        a.download = "推广海报.png"
        a.click()
        toast.success("海报已下载")
      }
    } catch {
      toast.error("分享失败")
    }
  }

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(linkUrl)
      toast.success("推广链接已复制")
    } catch {
      toast.error("复制失败，请手动复制")
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative flex max-h-[92vh] w-full max-w-sm flex-col overflow-hidden rounded-xl border border-border bg-card shadow-2xl">
        <div className="flex items-center justify-between border-b border-border px-5 py-3">
          <h2 className="flex items-center gap-2 text-base font-bold text-foreground">
            <ImageIcon className="h-5 w-5 text-primary" />
            {type === "invite" ? "邀请海报" : "推广海报"}
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4">
          <div className="relative flex justify-center rounded-lg bg-white">
            {drawing && (
              <div className="absolute inset-0 flex items-center justify-center">
                <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
              </div>
            )}
            <canvas ref={canvasRef} className="max-h-[56vh] w-auto max-w-full rounded-lg" />
          </div>
          {fallback && (
            <p className="mt-2 text-center text-xs text-amber-600">部分图片加载失败，海报已降级为文字版</p>
          )}
          <p className="mt-2 truncate text-center font-mono text-[11px] text-muted-foreground" title={linkUrl}>
            {linkUrl}
          </p>
        </div>

        <div className="grid grid-cols-3 gap-2 border-t border-border p-4">
          <button
            type="button"
            onClick={downloadPoster}
            className="inline-flex h-10 items-center justify-center gap-1.5 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
          >
            <Download className="h-4 w-4" />
            下载海报
          </button>
          <button
            type="button"
            onClick={sharePoster}
            className="inline-flex h-10 items-center justify-center gap-1.5 rounded-lg border border-input text-sm font-medium text-foreground transition-colors hover:bg-accent"
          >
            <Share2 className="h-4 w-4" />
            分享
          </button>
          <button
            type="button"
            onClick={copyLink}
            className="inline-flex h-10 items-center justify-center gap-1.5 rounded-lg border border-input text-sm font-medium text-foreground transition-colors hover:bg-accent"
          >
            <Copy className="h-4 w-4" />
            复制链接
          </button>
        </div>
      </div>
    </div>
  )
}

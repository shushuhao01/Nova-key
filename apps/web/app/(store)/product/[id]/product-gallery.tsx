"use client"

import { useEffect, useState } from "react"
import { Package, Play, X, ZoomIn } from "lucide-react"
import type { ProductDetail } from "@/types"

export function ProductGallery({ product }: { product: ProductDetail }) {
  const hasVideo = !!product.video_url
  // 封面图固定作为第一张，后面依次拼接后台配置的主图（去重、去空）
  const images = [product.cover_url, ...(product.gallery_images || [])].filter(
    (url, idx, arr): url is string => !!url && arr.indexOf(url) === idx
  )
  const [activeTab, setActiveTab] = useState<"video" | "image">(hasVideo ? "video" : "image")
  const [activeImage, setActiveImage] = useState(0)
  // 点击主图放大查看
  const [previewOpen, setPreviewOpen] = useState(false)
  const currentImage = images[Math.min(activeImage, Math.max(images.length - 1, 0))]

  // 放大查看时支持 Esc 关闭
  useEffect(() => {
    if (!previewOpen) return
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") setPreviewOpen(false)
    }
    window.addEventListener("keydown", onKeyDown)
    return () => window.removeEventListener("keydown", onKeyDown)
  }, [previewOpen])

  return (
    <div className="flex flex-col gap-3">
      {/* 主展示区 */}
      <div className="group relative aspect-square overflow-hidden rounded-lg border border-border bg-muted">
        {activeTab === "video" && hasVideo ? (
          <video
            src={product.video_url}
            className="h-full w-full object-contain"
            controls
            playsInline
            preload="metadata"
          />
        ) : currentImage ? (
          <button
            type="button"
            onClick={() => setPreviewOpen(true)}
            className="relative h-full w-full cursor-zoom-in"
            aria-label="点击放大查看当前图片"
          >
            <img
              src={currentImage}
              alt={product.title}
              fetchPriority="high"
              decoding="async"
              className="h-full w-full object-cover"
            />
            <span className="pointer-events-none absolute right-2 top-2 flex h-8 w-8 items-center justify-center rounded-full bg-black/50 text-white opacity-0 transition-opacity group-hover:opacity-100">
              <ZoomIn className="h-4 w-4" />
            </span>
          </button>
        ) : (
          <div className="flex h-full w-full items-center justify-center">
            <Package className="h-20 w-20 text-muted-foreground/20" />
          </div>
        )}

      </div>

      {/* 缩略图切换：视频 + 封面图 + 主图 */}
      {(hasVideo || images.length > 1) && (
        <div className="flex flex-wrap gap-2">
          {hasVideo && (
            <button
              onClick={() => setActiveTab("video")}
              className={`relative aspect-square w-20 shrink-0 overflow-hidden rounded-lg border-2 transition-colors ${activeTab === "video" ? "border-primary" : "border-border hover:border-muted-foreground/40"}`}
            >
              <div className="flex h-full w-full items-center justify-center bg-black">
                <Play className="h-6 w-6 fill-white text-white" />
              </div>
            </button>
          )}
          {images.map((url, idx) => (
            <button
              key={`${url}-${idx}`}
              onClick={() => {
                setActiveTab("image")
                setActiveImage(idx)
              }}
              className={`relative aspect-square w-20 shrink-0 overflow-hidden rounded-lg border-2 transition-colors ${activeTab === "image" && activeImage === idx ? "border-primary" : "border-border hover:border-muted-foreground/40"}`}
            >
              <img src={url} alt={`${product.title} 图 ${idx + 1}`} className="h-full w-full object-cover" loading="lazy" decoding="async" />
            </button>
          ))}
        </div>
      )}

      {/* 放大查看当前图片 */}
      {previewOpen && currentImage && (
        <div
          className="fixed inset-0 z-[100] flex items-center justify-center bg-black/90 p-4 cursor-zoom-out"
          onClick={() => setPreviewOpen(false)}
          role="dialog"
          aria-modal="true"
          aria-label="查看大图"
        >
          <button
            type="button"
            onClick={() => setPreviewOpen(false)}
            className="absolute right-4 top-4 flex h-10 w-10 items-center justify-center rounded-full bg-white/10 text-white transition-colors hover:bg-white/20"
            aria-label="关闭"
          >
            <X className="h-5 w-5" />
          </button>
          <img
            src={currentImage}
            alt={product.title}
            className="max-h-full max-w-full object-contain"
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}
    </div>
  )
}

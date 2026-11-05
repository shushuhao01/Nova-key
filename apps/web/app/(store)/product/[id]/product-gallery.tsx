"use client"

import { useState } from "react"
import { Package, Play } from "lucide-react"
import type { ProductDetail } from "@/types"

export function ProductGallery({ product }: { product: ProductDetail }) {
  const hasVideo = !!product.video_url
  // 封面图固定作为第一张，后面依次拼接后台配置的主图（去重、去空）
  const images = [product.cover_url, ...(product.gallery_images || [])].filter(
    (url, idx, arr): url is string => !!url && arr.indexOf(url) === idx
  )
  const [activeTab, setActiveTab] = useState<"video" | "image">(hasVideo ? "video" : "image")
  const [activeImage, setActiveImage] = useState(0)
  const currentImage = images[Math.min(activeImage, Math.max(images.length - 1, 0))]

  return (
    <div className="flex flex-col gap-3">
      {/* 主展示区 */}
      <div className="relative aspect-square overflow-hidden rounded-lg border border-border bg-muted">
        {activeTab === "video" && hasVideo ? (
          <video
            src={product.video_url}
            className="h-full w-full object-contain"
            controls
            playsInline
            preload="metadata"
          />
        ) : currentImage ? (
          <img
            src={currentImage}
            alt={product.title}
            fetchPriority="high"
            decoding="async"
            className="h-full w-full object-cover"
          />
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
    </div>
  )
}

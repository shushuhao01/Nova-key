"use client"

import { useState } from "react"
import { Package, Play } from "lucide-react"
import type { ProductDetail } from "@/types"

export function ProductGallery({ product }: { product: ProductDetail }) {
  const hasVideo = !!product.video_url
  const hasCover = !!product.cover_url
  const [activeTab, setActiveTab] = useState<"video" | "image">(hasVideo ? "video" : "image")

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
        ) : hasCover ? (
          <img
            src={product.cover_url}
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

      {/* 缩略图切换 */}
      {hasVideo && hasCover && (
        <div className="flex gap-2">
          <button
            onClick={() => setActiveTab("video")}
            className={`relative aspect-square w-20 shrink-0 overflow-hidden rounded-lg border-2 transition-colors ${activeTab === "video" ? "border-primary" : "border-border hover:border-muted-foreground/40"}`}
          >
            <div className="flex h-full w-full items-center justify-center bg-black">
              <Play className="h-6 w-6 fill-white text-white" />
            </div>
          </button>
          <button
            onClick={() => setActiveTab("image")}
            className={`relative aspect-square w-20 shrink-0 overflow-hidden rounded-lg border-2 transition-colors ${activeTab === "image" ? "border-primary" : "border-border hover:border-muted-foreground/40"}`}
          >
            {product.cover_url && (
              <img src={product.cover_url} alt={product.title} className="h-full w-full object-cover" loading="lazy" decoding="async" />
            )}
          </button>
        </div>
      )}
    </div>
  )
}

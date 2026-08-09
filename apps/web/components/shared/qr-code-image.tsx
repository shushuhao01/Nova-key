interface QrCodeImageProps {
  value: string
  /** 显示尺寸（CSS），不影响生成清晰度 */
  size?: number
  className?: string
  alt?: string
}

/**
 * 二维码图片组件
 *
 * 通过 Next.js API Route /qr-image 生成真实 HTTP URL 的 SVG 图片，以 <img> 标签展示。
 * 关键用途：微信内置浏览器长按 data URI / SVG / Canvas 二维码不会出现"识别二维码"菜单，
 * 只有 <img> 标签加载真实 HTTP URL 图片才能被微信长按识别。
 *
 * 注意：路径用 /qr-image 而非 /api/qrcode，因为 Nginx 将 /api/ 代理到后端。
 */
export function QrCodeImage({
  value,
  size = 184,
  className,
  alt = "二维码",
}: QrCodeImageProps) {
  if (!value) return null

  // 后端始终生成 400px 高清图片，前端用 CSS 控制显示尺寸
  const imgUrl = `/qr-image?url=${encodeURIComponent(value)}&size=400`

  return (
    <img
      src={imgUrl}
      alt={alt}
      width={size}
      height={size}
      className={className}
      aria-label={alt}
    />
  )
}

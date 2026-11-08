/**
 * 分销商品点击埋点
 *
 * 用于统计「商品维度」的推广点击（distribution_clicks.product_id 非空）。
 * 同一「推广链接 + 商品」在一个会话内只上报一次，避免商品卡片点击、
 * 单品推广链接跳转（后端 resolve 已计数）与商品详情页兜底上报重复计数。
 */

const DIST_LINK_COOKIE = "dist_link"
const KEY_PREFIX = "dist_click_"

/** 读取推广链接 Cookie（由 /p/{linkCode} 落地页写入） */
function readLinkIdFromCookie(): string | null {
  if (typeof document === "undefined") return null
  const m = document.cookie.match(/(?:^|;\s*)dist_link=([^;]+)/)
  return m ? decodeURIComponent(m[1]) : null
}

function dedupeKey(linkId: string, productId: string): string {
  return `${KEY_PREFIX}${linkId}_${productId}`
}

/** 标记该「推广链接 + 商品」本次会话已上报过点击 */
export function markProductClick(linkId: string, productId: string): void {
  try {
    if (typeof window === "undefined") return
    window.sessionStorage.setItem(dedupeKey(linkId, productId), "1")
  } catch {
    /* 隐私模式等场景不可用，忽略 */
  }
}

function isProductClicked(linkId: string, productId: string): boolean {
  try {
    if (typeof window === "undefined") return false
    return window.sessionStorage.getItem(dedupeKey(linkId, productId)) === "1"
  } catch {
    return false
  }
}

/**
 * 上报商品点击（写入 product_id 维度埋点）
 * @param productId 被点击的商品 ID
 * @param oncePerSession true = 同一会话内同一商品只上报一次（详情页兜底用）
 * @returns 是否实际发出上报
 */
export function reportProductClick(productId: string, oncePerSession = false): boolean {
  const linkId = readLinkIdFromCookie()
  if (!linkId) return false
  if (oncePerSession && isProductClicked(linkId, productId)) return false
  markProductClick(linkId, productId)
  try {
    // 用 fetch 而非 sendBeacon：JSON Content-Type 更可靠，keepalive 保证跳转时请求仍发出
    fetch("/api/distribution/product-click", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ link_id: linkId, product_id: productId }),
      keepalive: true,
      credentials: "include",
    }).catch(() => {
      /* 埋点失败静默，不影响用户操作 */
    })
    return true
  } catch {
    return false
  }
}

import type {
  ApiResponse,
  PaginatedData,
  UserProfile,
  OrderBrief,
  OrderDetail,
  OrderStatus,
  PointRecord,
  PointsData,
  ProductCard,
  ProductDetail,
  ProductSpec,
  Category,
  Cart,
  CreateOrderRequest,
  CreateCartOrderRequest,
  CreateOrderResult,
  DeliverResult,
  SiteConfig,
  SiteConfigKV,
  DashboardStats,
  SalesTrend,
  CardKeyStockSummary,
  CardKeyListItem,
  SoldCardKeyRecord,
  CardImportBatch,
  OrderCardKey,
  AdminOrderItem,
  PaymentChannelItem,
  PaymentTestResult,
  NotificationTemplateItem,
  NotificationChannelItem,
  SystemMessageItem,
  NotificationTestResult,
  OperationLog,
  OperationLogCleanupConfig,
  RiskConfig,
  WholesaleRule,
  CaptchaResult,
  AuthResult,
  CurrencyItem,
  TxidVerifyResult,
  SystemStaffItem,
  SystemStaffDetail,
  SystemRoleItem,
  PermissionItem,
} from "@/types"

// ============================================================
// Config
// ============================================================

const API_BASE = process.env.NEXT_PUBLIC_API_BASE || "/api"

// ============================================================
// Error
// ============================================================

class ApiError extends Error {
  code: number
  params?: Record<string, string | number>
  constructor(code: number, message: string, params?: Record<string, string | number>) {
    super(message)
    this.code = code
    this.params = params
    this.name = "ApiError"
  }
}

// ============================================================
// Token management
// ============================================================

function getToken(): string | null {
  if (typeof window === "undefined") return null
  return localStorage.getItem("auth_token")
}

export function setToken(token: string) {
  localStorage.setItem("auth_token", token)
}

export function clearToken() {
  localStorage.removeItem("auth_token")
}

/**
 * JWT 过期/无效时：清除本地登录态，跳转登录页
 * 使用防抖避免多个并发请求同时触发多次跳转
 */
let redirecting = false
function handleUnauthorized() {
  if (typeof window === "undefined" || redirecting) return
  redirecting = true
  clearToken()
  localStorage.removeItem("userProfile")
  const currentPath = window.location.pathname
  // 已经在登录页则不再跳转
  if (currentPath === "/login") {
    redirecting = false
    return
  }
  window.location.href = `/login?redirect=${encodeURIComponent(currentPath)}`
}

// ============================================================
// Session Token (guest cart)
// ============================================================

function getSessionToken(): string | null {
  if (typeof window === "undefined") return null
  return localStorage.getItem("session_token")
}

function setSessionToken(token: string) {
  localStorage.setItem("session_token", token)
}

export function clearSessionToken() {
  localStorage.removeItem("session_token")
}

// ============================================================
// Upload (admin rich text images)
// ============================================================

export const uploadApi = {
  image: (file: File) => {
    const formData = new FormData()
    formData.append("file", file)
    return uploadRequest<{ url: string }>("/upload/image", formData)
  },
}

// ============================================================
// Query builder
// ============================================================

function buildQuery(params: Record<string, string | number | boolean | undefined | null>): string {
  const sp = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      sp.set(key, String(value))
    }
  }
  return sp.toString()
}

// ============================================================
// Device Fingerprint
// ============================================================

let cachedDeviceId: string | null = null

async function ensureDeviceId(): Promise<string> {
  if (cachedDeviceId) return cachedDeviceId
  if (typeof window === "undefined") return ""
  try {
    const { getDeviceId } = await import("@/lib/fingerprint")
    cachedDeviceId = await getDeviceId()
    return cachedDeviceId
  } catch {
    return ""
  }
}

// 启动时异步预热，不阻塞首屏
if (typeof window !== "undefined") {
  ensureDeviceId()
}

// ============================================================
// Turnstile token（模块级别，由页面组件设置）
// ============================================================

let pendingTurnstileToken: string | null = null

/** 设置 Turnstile token（在调用受保护 API 前由页面组件调用） */
export function setTurnstileHeaders(token: string) {
  pendingTurnstileToken = token
}

/** 消费并清除 Turnstile token（request 内部使用） */
function consumeTurnstileToken(): string | null {
  const t = pendingTurnstileToken
  pendingTurnstileToken = null
  return t
}

// ============================================================
// Core request
// ============================================================

async function request<T>(
  path: string,
  options: RequestInit = {}
): Promise<T> {
  const token = getToken()
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string>),
  }

  if (token) {
    headers["Authorization"] = `Bearer ${token}`
  }
  // 始终发送 session token（购物车等功能需要：JWT 无效/过期时作为身份回退）
  const sessionToken = getSessionToken()
  if (sessionToken) {
    headers["X-Session-Token"] = sessionToken
  }

  // 设备指纹 — 始终发送
  const deviceId = cachedDeviceId || (await ensureDeviceId())
  if (deviceId) {
    headers["X-Device-Id"] = deviceId
  }

  // Turnstile token — 如果有 pending token 则发送（单次消费）
  const turnstileToken = consumeTurnstileToken()
  if (turnstileToken) {
    headers["X-Turnstile-Token"] = turnstileToken
  }

  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  })

  // Capture session token from response
  const newSessionToken = res.headers.get("X-Session-Token")
  if (newSessionToken) {
    setSessionToken(newSessionToken)
  }

  if (!res.ok) {
    if (res.status === 401) {
      handleUnauthorized()
    }
    const body = await res.json().catch(() => ({ code: res.status, message: res.statusText }))
    throw new ApiError(body.code || res.status, body.message || res.statusText, body.params)
  }

  const body: ApiResponse<T> = await res.json()

  if (body.code !== 0) {
    throw new ApiError(body.code, body.message, body.params)
  }

  return body.data
}

/** 下载文件（带登录态，响应为字节流，支持 query 参数） */
async function downloadFile(path: string, params?: Record<string, string>, filename?: string): Promise<void> {
  const token = getToken()
  const qs = params && Object.keys(params).length > 0 ? `?${new URLSearchParams(params).toString()}` : ""
  const res = await fetch(`${API_BASE}${path}${qs}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!res.ok) {
    if (res.status === 401) {
      handleUnauthorized()
    }
    throw new ApiError(res.status, res.statusText)
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement("a")
  a.href = url
  a.download = filename || `download-${Date.now()}`
  a.click()
  URL.revokeObjectURL(url)
}

async function uploadRequest<T>(path: string, formData: FormData): Promise<T> {
  const token = getToken()
  const headers: Record<string, string> = {}
  if (token) {
    headers["Authorization"] = `Bearer ${token}`
  }

  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers,
    body: formData,
  })

  if (!res.ok) {
    if (res.status === 401) {
      handleUnauthorized()
    }
    const body = await res.json().catch(() => ({ code: res.status, message: res.statusText }))
    throw new ApiError(body.code || res.status, body.message || res.statusText, body.params)
  }

  const body: ApiResponse<T> = await res.json()

  if (body.code !== 0) {
    throw new ApiError(body.code, body.message, body.params)
  }

  return body.data
}

// ============================================================
// Mock Fallback
// ============================================================

/**
 * Wraps an API call with a mock fallback.
 * Only network-level errors (TypeError from fetch) trigger fallback.
 * Business errors (ApiError with code!=0) propagate normally.
 */
export async function withMockFallback<T>(
  apiCall: () => Promise<T>,
  mockFn: () => T
): Promise<T> {
  try {
    return await apiCall()
  } catch (err) {
    if (err instanceof ApiError) {
      throw err // business error — let UI handle it
    }
    // Network error (TypeError) or unexpected — fallback to mock
    console.warn("[API] Network error, falling back to mock data:", err)
    return mockFn()
  }
}


// ============================================================
// Auth
// ============================================================

export const authApi = {
  getCaptcha: () =>
    request<CaptchaResult>("/auth/captcha"),
  register: (data: { username: string; password: string; email: string; captcha_id: string; captcha: string; invite_code?: string }) =>
    request<AuthResult>("/auth/register", { method: "POST", body: JSON.stringify(data) }),
  login: (data: { account: string; password: string }) =>
    request<AuthResult>("/auth/login", { method: "POST", body: JSON.stringify(data) }),
  logout: () =>
    request<null>("/auth/logout", { method: "POST" }),
}

// ============================================================
// User
// ============================================================

export const userApi = {
  getProfile: () =>
    request<UserProfile>("/user/profile"),
  updatePassword: (data: { old_password: string; new_password: string }) =>
    request<null>("/user/password", { method: "PUT", body: JSON.stringify(data) }),
  getOrders: (params: { page?: number; page_size?: number; status?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<OrderBrief>>(`/user/orders?${qs}`)
  },
  getPoints: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PointsData>(`/user/points?${qs}`)
  },
}

// ============================================================
// Product
// ============================================================

export const productApi = {
  getList: (params: { page?: number; page_size?: number; category_id?: string; keyword?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<ProductCard>>(`/products?${qs}`)
  },
  getDetail: (id: string) =>
    request<ProductDetail>(`/products/${id}`),
  /** 商品短链解析（/s/{code} 跳转页使用） */
  resolveShortCode: (code: string) =>
    request<{ product_id: string }>(`/products/short/${encodeURIComponent(code)}`),
  getCategories: () =>
    request<Category[]>("/categories"),
}

// ============================================================
// Cart
// ============================================================

export const cartApi = {
  get: () =>
    request<Cart>("/cart"),
  addItem: (data: { product_id: string; spec_id: string | null; quantity: number }) =>
    request<null>("/cart/items", { method: "POST", body: JSON.stringify(data) }),
  updateItem: (itemId: string, quantity: number) =>
    request<null>(`/cart/items/${itemId}`, { method: "PUT", body: JSON.stringify({ quantity }) }),
  removeItem: (itemId: string) =>
    request<null>(`/cart/items/${itemId}`, { method: "DELETE" }),
}

// ============================================================
// Order
// ============================================================

export const orderApi = {
  create: (data: CreateOrderRequest) =>
    request<CreateOrderResult>("/orders", { method: "POST", body: JSON.stringify(data) }),
  createFromCart: (data: CreateCartOrderRequest) =>
    request<CreateOrderResult>("/orders/from-cart", { method: "POST", body: JSON.stringify(data) }),
  getStatus: (orderId: string) =>
    request<{ order_id: string; status: OrderStatus; expires_at: string; remaining_seconds: number; payment_url?: string; jsapi_params?: import("@/types").JsapiPayParams }>(`/orders/${orderId}/status`),
  refreshStatus: (orderId: string) =>
    request<{ status: OrderStatus }>(`/orders/${orderId}/refresh`, { method: "POST" }),
  query: (data: { order_ids?: string[]; emails?: string[] }) =>
    request<OrderBrief[]>("/orders/query", { method: "POST", body: JSON.stringify(data) }),
  deliver: (data: { order_ids: string[] }) =>
    request<DeliverResult[]>("/orders/deliver", { method: "POST", body: JSON.stringify(data) }),
  exportKeys: (orderId: string) =>
    request<string>(`/orders/${orderId}/export`),
  submitTxid: (orderId: string, txid: string) =>
    request<TxidVerifyResult>(`/orders/${orderId}/txid-verify`, {
      method: "POST",
      body: JSON.stringify({ txid }),
    }),
  repay: (orderId: string, device?: string, openid?: string) =>
    request<import("@/types").PaymentCreateResult>(`/orders/${orderId}/repay`, {
      method: "POST",
      body: JSON.stringify({ device, openid }),
    }),
}

// ============================================================
// Site Config (public)
// ============================================================

export const siteApi = {
  getConfig: () =>
    request<SiteConfig>("/site/config"),
}

// ============================================================
// WeChat MP (公众号配置 — 后台管理)
// ============================================================

export interface MpMessageTemplate {
  code: string
  name: string
  description: string
  variables: string[]
  template_id: string
  enabled: boolean
}

export const adminWechatMpApi = {
  getConfig: () => request<any>("/admin/wechat-mp/config"),
  updateConfig: (data: {
    appid?: string
    appsecret?: string
    follow_qr?: string
    template_id?: string
    message_templates?: MpMessageTemplate[]
    token?: string
    aes_key?: string
    encrypt_mode?: string
  }) =>
    request<void>("/admin/wechat-mp/config", { method: "PUT", body: JSON.stringify(data) }),
  test: () => request<any>("/admin/wechat-mp/test", { method: "POST" }),
}

// ============================================================
// WeChat MP (公开：公众号关注信息，分销中心引导关注)
// ============================================================

export const wechatMpApi = {
  getFollowInfo: () => request<any>("/wechat-mp/follow-info"),
  /** 生成公众号 OAuth 授权链接（snsapi_base 静默授权取 openid），需登录；state 由服务端生成并返回 */
  bindUrl: () =>
    request<{ oauth_url: string; state: string }>("/wechat-mp/bind-url", { method: "POST" }),
  /** 用微信授权 code 完成公众号账号绑定，需登录 */
  bind: (code: string, state: string) =>
    request<{ bound: boolean; openid: string }>("/wechat-mp/bind", { method: "POST", body: JSON.stringify({ code, state }) }),
  /** 微信内静默授权（snsapi_base，游客无需登录）换取 openid：生成授权链接 */
  getOauthUrl: (redirect: string) =>
    request<{ oauth_url: string; state: string }>(`/wechat-mp/oauth2/url?redirect=${encodeURIComponent(redirect)}`),
}

// ============================================================
// Payment Channels (public, for store display)
// ============================================================

export const paymentApi = {
  getChannels: () =>
    request<PaymentChannelItem[]>("/payment-channels"),
}

// ============================================================
// Currencies (public)
// ============================================================

export const currencyApi = {
  getList: () =>
    request<CurrencyItem[]>("/currencies"),
}

// ============================================================
// Admin Dashboard
// ============================================================

export const adminDashboardApi = {
  getStats: () =>
    request<DashboardStats>("/admin/dashboard"),
  getSalesTrend: (params: { period?: string; start_date?: string; end_date?: string }) => {
    const qs = buildQuery(params)
    return request<SalesTrend[]>(`/admin/dashboard/sales-trend?${qs}`)
  },
}

// ============================================================
// Admin Product
// ============================================================

export const adminProductApi = {
  getList: (params: { page?: number; page_size?: number; category_id?: string; is_enabled?: number; keyword?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<ProductDetail>>(`/admin/products?${qs}`)
  },
  getDetail: (id: string) =>
    request<ProductDetail>(`/admin/products/${id}`),
  create: (data: {
    title: string; description?: string; detail_md?: string; detail_images?: string[]; cover_url?: string;
    base_price: number; category_id: string; low_stock_threshold?: number;
    wholesale_enabled?: boolean; is_enabled?: boolean; sort_order?: number; homepage_visible?: boolean
  }) =>
    request<ProductDetail>("/admin/products", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: Partial<{
    title: string; description: string; detail_md: string; detail_images: string[]; cover_url: string;
    base_price: number; category_id: string; low_stock_threshold: number;
    wholesale_enabled: boolean; is_enabled: boolean; sort_order: number; homepage_visible: boolean
  }>) =>
    request<null>(`/admin/products/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  delete: (id: string) =>
    request<null>(`/admin/products/${id}`, { method: "DELETE" }),
  // Specs
  getSpecs: (productId: string) =>
    request<ProductSpec[]>(`/admin/products/${productId}/specs`),
  addSpec: (productId: string, data: { name: string; price: number; is_visible?: boolean; sort_order?: number }) =>
    request<ProductSpec>(`/admin/products/${productId}/specs`, { method: "POST", body: JSON.stringify(data) }),
  updateSpec: (productId: string, specId: string, data: Partial<{ name: string; price: number; is_visible: boolean; sort_order: number }>) =>
    request<null>(`/admin/products/${productId}/specs/${specId}`, { method: "PUT", body: JSON.stringify(data) }),
  deleteSpec: (productId: string, specId: string) =>
    request<null>(`/admin/products/${productId}/specs/${specId}`, { method: "DELETE" }),
  // Wholesale rules
  getWholesaleRules: (productId: string) =>
    request<WholesaleRule[]>(`/admin/products/${productId}/wholesale-rules`),
  setWholesaleRules: (productId: string, data: { spec_id?: string | null; rules: { min_quantity: number; unit_price: number }[] }) =>
    request<null>(`/admin/products/${productId}/wholesale-rules`, { method: "POST", body: JSON.stringify(data) }),
  // Image upload
  uploadImage: (file: File) => {
    const formData = new FormData()
    formData.append("file", file)
    return uploadRequest<{ url: string }>("/upload/image", formData)
  },
  // Video upload
  uploadVideo: (file: File) => {
    const formData = new FormData()
    formData.append("file", file)
    return uploadRequest<{ url: string }>("/upload/video", formData)
  },
}

// ============================================================
// Admin Category
// ============================================================

export const adminCategoryApi = {
  getList: () =>
    request<Category[]>("/admin/categories"),
  create: (data: { name: string; sort_order?: number }) =>
    request<null>("/admin/categories", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: { name?: string; sort_order?: number }) =>
    request<null>(`/admin/categories/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  delete: (id: string) =>
    request<null>(`/admin/categories/${id}`, { method: "DELETE" }),
}

// ============================================================
// Admin Card Keys
// ============================================================

export const adminCardKeyApi = {
  getList: (params: { product_id: string; spec_id?: string | null; status?: string; keyword?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<CardKeyListItem>>(`/admin/card-keys/list?${qs}`)
  },
  getSold: (params?: { keyword?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params ?? {})
    return request<PaginatedData<SoldCardKeyRecord>>(`/admin/card-keys/sold?${qs}`)
  },
  getStock: (params?: { product_id?: string; spec_id?: string }) => {
    const qs = buildQuery(params ?? {})
    return request<CardKeyStockSummary[]>(`/admin/card-keys/stock?${qs}`)
  },
  import: (data: { product_id: string; spec_id?: string | null; content: string }) =>
    request<CardImportBatch>("/admin/card-keys/import", { method: "POST", body: JSON.stringify(data) }),
  getImportBatches: (params: { product_id?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<CardImportBatch>>(`/admin/card-keys/import-batches?${qs}`)
  },
  invalidate: (id: string) =>
    request<null>(`/admin/card-keys/${id}/invalidate`, { method: "POST" }),
  batchInvalidate: (params: { product_id: string; spec_id?: string | null }) => {
    const qs = buildQuery(params)
    return request<{ invalidated_count: number }>(`/admin/card-keys/batch-invalidate?${qs}`, { method: "POST" })
  },
  getByOrder: (orderId: string) =>
    request<OrderCardKey[]>(`/admin/card-keys/by-order/${orderId}`),
}

// ============================================================
// Admin Order
// ============================================================

export const adminOrderApi = {
  getList: (params: {
    page?: number; page_size?: number; status?: string; order_type?: string;
    payment_method?: string; is_risk_flagged?: number; keyword?: string
  }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdminOrderItem>>(`/admin/orders?${qs}`)
  },
  getDetail: (id: string) =>
    request<AdminOrderItem>(`/admin/orders/${id}`),
  markPaid: (id: string) =>
    request<null>(`/admin/orders/${id}/mark-paid`, { method: "POST" }),
  refund: (id: string, data: { amount: number; reason: string }) =>
    request<{ refunded_amount: number; out_refund_no: string; wx_refund_id: string; status: string }>(
      `/admin/orders/${id}/refund`,
      { method: "POST", body: JSON.stringify(data) }
    ),
}

// ============================================================
// Admin System (RBAC：内部员工 + 角色权限)
// ============================================================

export const adminSystemApi = {
  // ── 员工管理 ──
  getStaff: (params: { keyword?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<SystemStaffItem>>(`/admin/system/users?${qs}`)
  },
  staffDetail: (id: string) =>
    request<SystemStaffDetail>(`/admin/system/users/${id}`),
  createStaff: (data: { username: string; email: string; password: string; role_id: string }) =>
    request<{ id: string }>("/admin/system/users", { method: "POST", body: JSON.stringify(data) }),
  updateStaff: (id: string, data: { username?: string; role_id?: string }) =>
    request<SystemStaffDetail>(`/admin/system/users/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  resetPassword: (id: string, password: string) =>
    request<null>(`/admin/system/users/${id}/password`, { method: "POST", body: JSON.stringify({ password }) }),
  toggleStaff: (id: string, isDeleted: 0 | 1) =>
    request<null>(`/admin/system/users/${id}/toggle`, { method: "POST", body: JSON.stringify({ is_deleted: isDeleted }) }),
  deleteStaff: (id: string) =>
    request<null>(`/admin/system/users/${id}`, { method: "DELETE" }),
  // ── 角色管理 ──
  getRoles: (params: { keyword?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<SystemRoleItem>>(`/admin/system/roles?${qs}`)
  },
  createRole: (data: { code: string; name: string; description?: string; permissions: string[] }) =>
    request<{ id: string }>("/admin/system/roles", { method: "POST", body: JSON.stringify(data) }),
  updateRole: (id: string, data: { name?: string; description?: string; permissions?: string[] }) =>
    request<SystemRoleItem>(`/admin/system/roles/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  deleteRole: (id: string) =>
    request<null>(`/admin/system/roles/${id}`, { method: "DELETE" }),
  // ── 权限清单 ──
  getPermissions: () =>
    request<PermissionItem[]>("/admin/system/permissions"),
}

// ============================================================
// Marketing (public coupon claim/validate)
// ============================================================

export const marketingApi = {
  claim: (data: { code: string; email?: string }) =>
    request<import("@/types").CouponClaimResult>("/marketing/coupons/claim", { method: "POST", body: JSON.stringify(data) }),
  validate: (data: { code: string; email?: string; amount: number; product_ids?: string[] }) =>
    request<import("@/types").CouponValidateResult>("/marketing/coupons/validate", { method: "POST", body: JSON.stringify(data) }),
  info: (code: string) =>
    request<import("@/types").CouponInfo>(`/marketing/coupons/info?code=${encodeURIComponent(code)}`),
  myCoupons: (params: { status?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<import("@/types").MyCouponItem>>(`/marketing/coupons/my?${qs}`)
  },
}

// ============================================================
// Admin Marketing (coupons & email campaigns, separate tabs)
// ============================================================

export const adminMarketingApi = {
  // ── 优惠券管理 ──
  getCoupons: (params: { keyword?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<import("@/types").MarketingCouponItem>>(`/admin/marketing/coupons?${qs}`)
  },
  getCoupon: (id: string) =>
    request<import("@/types").MarketingCouponItem>(`/admin/marketing/coupons/${id}`),
  createCoupon: (data: import("@/types").CouponPayload) =>
    request<import("@/types").MarketingCouponItem>("/admin/marketing/coupons", { method: "POST", body: JSON.stringify(data) }),
  updateCoupon: (id: string, data: Partial<import("@/types").CouponPayload>) =>
    request<import("@/types").MarketingCouponItem>(`/admin/marketing/coupons/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  cancelCoupon: (id: string) =>
    request<null>(`/admin/marketing/coupons/${id}/cancel`, { method: "POST" }),
  deleteCoupon: (id: string) =>
    request<null>(`/admin/marketing/coupons/${id}`, { method: "DELETE" }),
  // ── 营销邮件管理 ──
  getEmails: (params: { keyword?: string; status?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<import("@/types").MarketingEmailItem>>(`/admin/marketing/emails?${qs}`)
  },
  getEmail: (id: string) =>
    request<import("@/types").MarketingEmailItem>(`/admin/marketing/emails/${id}`),
  createEmail: (data: import("@/types").EmailPayload) =>
    request<import("@/types").MarketingEmailItem>("/admin/marketing/emails", { method: "POST", body: JSON.stringify(data) }),
  updateEmail: (id: string, data: Partial<import("@/types").EmailPayload>) =>
    request<import("@/types").MarketingEmailItem>(`/admin/marketing/emails/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  deleteEmail: (id: string) =>
    request<null>(`/admin/marketing/emails/${id}`, { method: "DELETE" }),
  sendEmail: (id: string) =>
    request<{ scheduled: boolean; sent?: number; send_at?: string }>(`/admin/marketing/emails/${id}/send`, { method: "POST" }),
  recipients: (id: string, params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<import("@/types").RecipientsResult>(`/admin/marketing/emails/${id}/recipients?${qs}`)
  },
  audienceSuggestions: () =>
    request<{ registered_usernames: string[]; anonymous_emails: string[] }>("/admin/marketing/emails/audience-suggestions"),
}

// ============================================================
// Admin Customers
// ============================================================

export const adminCustomerApi = {
  overview: () =>
    request<import("@/types").CustomerOverview>("/admin/customers/overview"),
  getRegistered: (params: { keyword?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<import("@/types").RegisteredCustomerItem>>(`/admin/customers/registered?${qs}`)
  },
  getAnonymous: (params: { keyword?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<import("@/types").AnonymousCustomerItem>>(`/admin/customers/anonymous?${qs}`)
  },
  registeredDetail: (id: string, params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<import("@/types").RegisteredCustomerDetail>(`/admin/customers/registered/${id}?${qs}`)
  },
  anonymousDetail: (email: string, params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<import("@/types").AnonymousCustomerDetail>(`/admin/customers/anonymous/detail?${qs}&email=${encodeURIComponent(email)}`)
  },
  toggleRegistered: (id: string, isDeleted: 0 | 1) =>
    request<null>(`/admin/customers/registered/${id}/toggle`, { method: "POST", body: JSON.stringify({ is_deleted: isDeleted }) }),
}

// ============================================================
// Admin Payment
// ============================================================

export const adminPaymentApi = {
  getList: () =>
    request<PaymentChannelItem[]>("/admin/payment-channels"),
  create: (data: { channel_code: string; channel_name: string; provider_type: string; config_data?: Record<string, unknown>; is_enabled?: boolean; sort_order?: number }) =>
    request<null>("/admin/payment-channels", { method: "POST", body: JSON.stringify(data) }),
  update: (id: string, data: Partial<{ channel_name: string; provider_type: string; config_data: Record<string, unknown>; is_enabled: boolean; sort_order: number }>) =>
    request<null>(`/admin/payment-channels/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  delete: (id: string) =>
    request<null>(`/admin/payment-channels/${id}`, { method: "DELETE" }),
  // 微信支付证书上传（apiclient_cert.pem / apiclient_key.pem），返回服务器绝对路径
  uploadCert: (file: File) => {
    const formData = new FormData()
    formData.append("file", file)
    return uploadRequest<{ path: string; filename: string }>("/upload/cert", formData)
  },
  // 测试支付渠道配置与支付平台的连通性，返回 { success, message, items: 逐项清单 }
  testChannel: (id: string) =>
    request<PaymentTestResult>(`/admin/payment-channels/${id}/test`, { method: "POST" }),
  // 获取渠道完整配置（含私钥/公钥明文），仅管理员，用于后台"查看原值"
  getRawConfig: (id: string) =>
    request<Record<string, string | undefined>>(`/admin/payment-channels/${id}/raw-config`),
}

// ============================================================
// Admin Notifications
// ============================================================

export const adminNotificationApi = {
  // 模板
  getTemplates: (params: { page?: number; page_size?: number; category?: string; enabled?: boolean }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<NotificationTemplateItem>>(`/admin/notifications/templates?${qs}`)
  },
  createTemplate: (data: { code: string; name: string; category: string; title?: string; content?: string; channels?: string; enabled?: boolean; auto_trigger?: boolean }) =>
    request<null>("/admin/notifications/templates", { method: "POST", body: JSON.stringify(data) }),
  updateTemplate: (id: string, data: { enabled?: boolean; channels?: string; title?: string; content?: string }) =>
    request<null>(`/admin/notifications/templates/${id}`, { method: "PUT", body: JSON.stringify(data) }),
  // 渠道
  getChannels: () =>
    request<NotificationChannelItem[]>("/admin/notifications/channels"),
  saveChannel: (channelType: string, data: { name?: string; enabled?: boolean; webhook_url?: string; email_to?: string }) =>
    request<null>(`/admin/notifications/channels/${channelType}`, { method: "PUT", body: JSON.stringify(data) }),
  // 测试发送
  testSend: (templateCode: string) =>
    request<NotificationTestResult>("/admin/notifications/test", { method: "POST", body: JSON.stringify({ template_code: templateCode }) }),
  // 系统消息（铃铛）
  getMessages: (params: { page?: number; page_size?: number; unread?: boolean }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<SystemMessageItem>>(`/admin/notifications/messages?${qs}`)
  },
  getUnreadCount: () =>
    request<{ count: number }>("/admin/notifications/messages/unread-count"),
  markRead: (id: string) =>
    request<null>(`/admin/notifications/messages/${id}/read`, { method: "POST" }),
  markAllRead: () =>
    request<{ updated: number }>("/admin/notifications/messages/read-all", { method: "POST" }),
  clearMessages: () =>
    request<null>("/admin/notifications/messages/clear", { method: "POST" }),
}

// ============================================================
// Admin Config
// ============================================================

export const adminConfigApi = {
  get: () =>
    request<SiteConfigKV[]>("/admin/site-config"),
  update: (data: { configs: { config_key: string; config_value: string }[] }) =>
    request<null>("/admin/site-config", { method: "PUT", body: JSON.stringify(data) }),
  toggleMaintenance: (enabled: boolean) =>
    request<null>("/admin/site-config/maintenance", { method: "POST", body: JSON.stringify({ enabled }) }),
  // 用当前 SMTP 配置发送测试邮件
  testEmail: (toEmail: string) =>
    request<null>("/admin/site-config/email/test", { method: "POST", body: JSON.stringify({ to_email: toEmail }) }),
}

// ============================================================
// Admin Log
// ============================================================

export const adminLogApi = {
  getList: (params: { page?: number; page_size?: number; user_id?: string; action?: string; target_type?: string; start_date?: string; end_date?: string }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<OperationLog>>(`/admin/operation-logs?${qs}`)
  },
  // 定时清理配置
  getCleanupConfig: () =>
    request<OperationLogCleanupConfig>("/admin/operation-logs/cleanup-config"),
  saveCleanupConfig: (data: { enabled: boolean; hours: number }) =>
    request<null>("/admin/operation-logs/cleanup-config", { method: "PUT", body: JSON.stringify(data) }),
  // 立即清理过期日志（按当前保留时长）
  cleanupNow: () =>
    request<{ deleted: number }>("/admin/operation-logs/cleanup", { method: "POST" }),
}

// ============================================================
// Admin Risk
// ============================================================

export const adminRiskApi = {
  getConfig: () =>
    request<RiskConfig>("/admin/risk-config"),
  updateConfig: (data: Partial<RiskConfig>) =>
    request<null>("/admin/risk-config", { method: "PUT", body: JSON.stringify(data) }),
  getFlaggedOrders: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<AdminOrderItem>>(`/admin/risk/flagged-orders?${qs}`)
  },
}

// ============================================================
// Admin TXID Review
// ============================================================

export const adminTxidReviewApi = {
  getList: (params: { status?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<import("@/types").UnmatchedTransaction>>(`/admin/txid-reviews?${qs}`)
  },
  approve: (id: string) =>
    request<null>(`/admin/txid-reviews/${id}/approve`, { method: "POST" }),
  reject: (id: string, reason: string) =>
    request<null>(`/admin/txid-reviews/${id}/reject`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    }),
}

export { ApiError }

// ============================================================
// Distribution — Admin
// ============================================================

export const adminDistributionApi = {
  getOverview: (params: { range?: string; from?: string; to?: string } = {}) => {
    const qs = buildQuery(params)
    return request<any>(`/admin/distribution/overview?${qs}`)
  },
  getRecentOrders: (params: { from?: string; to?: string; limit?: number } = {}) => {
    const qs = buildQuery(params)
    return request<any>(`/admin/distribution/recent-orders?${qs}`)
  },
  getRules: () => request<any>("/admin/distribution/rules"),
  updateRules: (data: Record<string, any>) =>
    request<void>("/admin/distribution/rules", { method: "PUT", body: JSON.stringify(data) }),
  getTiers: () => request<any[]>("/admin/distribution/tiers"),
  updateTiers: (tiers: any[]) =>
    request<void>("/admin/distribution/tiers", { method: "PUT", body: JSON.stringify({ tiers }) }),
  listDistributors: (params: { status?: string; keyword?: string; from?: string; to?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/admin/distribution/distributors?${qs}`)
  },
  getDistributor: (id: string) => request<any>(`/admin/distribution/distributors/${id}`),
  listSubordinates: (id: string, params: { keyword?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/admin/distribution/distributors/${id}/subordinates?${qs}`)
  },
  listCustomers: (id: string, params: { keyword?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/admin/distribution/distributors/${id}/customers?${qs}`)
  },
  updateDistributorStatus: (id: string, status: string, reason?: string) =>
    request<void>(`/admin/distribution/distributors/${id}/status`, { method: "PUT", body: JSON.stringify({ status, reason: reason || "" }) }),
  updateDistributorRate: (id: string, custom_rate?: number, sub_rate?: number) =>
    request<void>(`/admin/distribution/distributors/${id}/rate`, { method: "PUT", body: JSON.stringify({ custom_rate, sub_rate }) }),
  listProducts: (params: { keyword?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/admin/distribution/products?${qs}`)
  },
  productStats: (params: { range?: string; from?: string; to?: string }) => {
    const qs = buildQuery(params)
    return request<any>(`/admin/distribution/products/stats?${qs}`)
  },
  productPromoters: (productId: string, params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/admin/distribution/products/${productId}/promoters?${qs}`)
  },
  updateProductCommission: (productId: string, custom_rate?: number | null, excluded?: boolean) =>
    request<void>(`/admin/distribution/products/${productId}`, { method: "PUT", body: JSON.stringify({ custom_rate, excluded }) }),
  listCommissions: (params: { distributor_id?: string; status?: string; from?: string; to?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/admin/distribution/commissions?${qs}`)
  },
  commissionStats: (params: { from?: string; to?: string } = {}) => {
    const qs = buildQuery(params)
    return request<any>(`/admin/distribution/commissions/stats?${qs}`)
  },
  listWithdrawals: (params: { status?: string; from?: string; to?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/admin/distribution/withdrawals?${qs}`)
  },
  withdrawalItems: (id: string) => request<any[]>(`/admin/distribution/withdrawals/${id}/items`),
  withdrawalStats: (params: { from?: string; to?: string }) => {
    const qs = buildQuery(params)
    return request<any>(`/admin/distribution/withdrawals/stats?${qs}`)
  },
  approveWithdrawal: (id: string) =>
    request<void>(`/admin/distribution/withdrawals/${id}/approve`, { method: "PUT" }),
  rejectWithdrawal: (id: string, reason: string) =>
    request<void>(`/admin/distribution/withdrawals/${id}/reject`, { method: "PUT", body: JSON.stringify({ reason }) }),
  settleWithdrawal: (id: string, actualAmount?: number) =>
    request<void>(`/admin/distribution/withdrawals/${id}/settle`, {
      method: "PUT",
      body: JSON.stringify(actualAmount != null ? { actual_amount: actualAmount } : {}),
    }),
  withdrawalTransferStatus: (id: string) =>
    request<any>(`/admin/distribution/withdrawals/${id}/transfer-status`),
  retryWithdrawalTransfer: (id: string) =>
    request<any>(`/admin/distribution/withdrawals/${id}/retry-transfer`, { method: "POST" }),
}

// ============================================================
// Distribution — Distributor (前台)
// ============================================================

export const distributorApi = {
  apply: (invite_code?: string) =>
    request<any>("/distributor/apply", { method: "POST", body: JSON.stringify({ invite_code }) }),
  getProfile: () => request<any>("/distributor/profile"),
  getRules: () => request<any>("/distributor/rules"),
  getStats: (range?: string) => request<any>(`/distributor/stats?range=${range || "all"}`),
  listProducts: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/distributor/products?${qs}`)
  },
  listMyProducts: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/distributor/products/mine?${qs}`)
  },
  generateLink: (productId: string) =>
    request<any>(`/distributor/products/${productId}/link`, { method: "POST" }),
  generateStoreLink: () =>
    request<any>("/distributor/store/link", { method: "POST" }),
  getStoreStats: () => request<any>("/distributor/store/stats"),
  bindInvite: (invite_code: string) =>
    request<any>("/distributor/customer/bind", { method: "POST", body: JSON.stringify({ invite_code }) }),
  getCustomerBinding: () => request<any>("/distributor/customer/binding"),
  getCustomerBindingHistory: () => request<any>("/distributor/customer/binding/history"),
  generateProductPoster: (productId: string) =>
    request<any>(`/distributor/products/${productId}/poster`, { method: "POST" }),
  generateStorePoster: () =>
    request<any>("/distributor/store/poster", { method: "POST" }),
  getWechatBindUrl: () => request<any>("/distributor/wechat/bind-url"),
  wechatCallback: (code: string, state: string) =>
    request<any>(`/distributor/wechat/callback?code=${encodeURIComponent(code)}&state=${encodeURIComponent(state)}`),
  unbindWechat: () =>
    request<void>("/distributor/wechat/unbind", { method: "POST" }),
  getWechatStatus: () => request<any>("/distributor/wechat/status"),
  listLinks: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/distributor/links?${qs}`)
  },
  listPromotionOrders: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/distributor/orders?${qs}`)
  },
  listCommissions: (params: { status?: string; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/distributor/commissions?${qs}`)
  },
  /** 导出佣金明细 Excel（全部数据，status 可选过滤） */
  exportCommissions: (status?: string) => {
    const d = new Date()
    const pad = (n: number) => String(n).padStart(2, "0")
    const filename = `佣金明细_${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}.xlsx`
    return downloadFile("/distributor/commissions/export", status ? { status } : undefined, filename)
  },
  applyWithdrawal: (commissionRecordIds: string[]) =>
    request<any>("/distributor/withdrawals", { method: "POST", body: JSON.stringify({ commission_record_ids: commissionRecordIds }) }),
  withdrawableOrders: () => request<any[]>("/distributor/withdrawals/withdrawable-orders"),
  listWithdrawals: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/distributor/withdrawals?${qs}`)
  },
  /** 获取确认收款拉起参数（mchId/appId/packageInfo），供微信内调起 requestMerchantTransfer */
  withdrawalConfirmInfo: (id: string) => request<any>(`/distributor/withdrawals/${id}/confirm-info`),
  listSubordinates: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/distributor/subordinates?${qs}`)
  },
  listCustomers: (params: { page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/distributor/customers?${qs}`)
  },
}

// ============================================================
// Distribution — Public
// ============================================================

export const distributionApi = {
  resolveLink: (linkCode: string) => request<any>(`/distribution/resolve/${linkCode}`),
  commissionPreview: (productIds: string[]) =>
    request<any>(`/distribution/commission-preview?product_ids=${productIds.join(",")}`),
  /** 商品点击埋点：全店推广链接进店后点击商品时上报，计入分销员对应商品点击统计 */
  recordProductClick: (linkId: string, productId: string) =>
    request<void>("/distribution/product-click", {
      method: "POST",
      body: JSON.stringify({ link_id: linkId, product_id: productId }),
    }),
}

// ============================================================
// User Messages (铃铛)
// ============================================================

export const userMessageApi = {
  unreadCount: () => request<{ count: number }>("/user/messages/unread-count"),
  recent: () => request<any[]>("/user/messages/recent"),
  list: (params: { category?: string; unreadOnly?: boolean; page?: number; page_size?: number }) => {
    const qs = buildQuery(params)
    return request<PaginatedData<any>>(`/user/messages?${qs}`)
  },
  markRead: (id: string) =>
    request<void>(`/user/messages/${id}/read`, { method: "PUT" }),
  markAllRead: () =>
    request<void>("/user/messages/read-all", { method: "PUT" }),
  clearAll: () =>
    request<void>("/user/messages", { method: "DELETE" }),
}

// ============================================================
// Error code → i18n key mapping
// ============================================================

// 只映射用户可见的前台错误码，后台管理页面不映射（后台统一中文界面）
const ERROR_CODE_I18N: Record<number, string> = {
  // 通用
  10002: "error.unauthorized",
  10003: "error.forbidden",
  10005: "error.tooManyRequests",
  10006: "error.serverError",
  10007: "error.maintenance",
  // Auth
  20001: "error.usernameExists",
  20002: "error.emailExists",
  20003: "error.captchaInvalid",
  20004: "error.invalidCredentials",
  20005: "error.oldPasswordWrong",
  20006: "error.accountDisabled",
  // Product
  30001: "error.productNotFound",
  30002: "error.insufficientStock",
  30003: "error.specNotFound",
  30004: "error.purchaseLimitExceeded",
  30005: "error.specHasCardKeys",
  30006: "error.specNameDuplicate",
  // Order
  40001: "error.orderNotFound",
  40002: "error.orderExpired",
  40003: "error.orderNotPaid",
  40004: "error.orderOutOfStock",
  40005: "error.orderProcessing",
  40006: "error.purchaseLimitPerUser",
  40007: "error.unpaidOrderExists",
  40008: "error.cartEmpty",
  // Payment
  50001: "error.channelUnavailable",
  50003: "error.txidInvalidFormat",
  50004: "error.txidAlreadyUsed",
  50005: "error.txidVerifyFailed",
  50006: "error.orderNotUsdt",
}

/**
 * 从 API 错误中提取用户可见的提示文案。
 * 已映射 → i18n 文案 + 参数插值
 * 未映射 → 后端原始 message（兜底，不丢信息）
 * 非 ApiError → 原始 error message
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function getApiErrorMessage(err: unknown, t: (key: any) => string): string {
  if (err instanceof ApiError) {
    const i18nKey = ERROR_CODE_I18N[err.code]
    if (i18nKey) {
      let msg = t(i18nKey)
      // 参数插值: {available} → 实际值
      if (err.params) {
        for (const [k, v] of Object.entries(err.params)) {
          msg = msg.replace(`{${k}}`, String(v))
        }
      }
      return msg
    }
    // 未映射的 code → 直接返回后端原始 message（兜底，不丢信息）
    return err.message
  }
  return err instanceof Error ? err.message : t("common.error")
}

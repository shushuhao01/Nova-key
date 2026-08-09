// ============================================================
// API Response Types
// ============================================================

export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  params?: Record<string, string | number>
}

export interface Pagination {
  page: number
  page_size: number
  total: number
}

export interface PaginatedData<T> {
  list: T[]
  pagination: Pagination
}

// ============================================================
// Auth
// ============================================================

export interface LoginRequest {
  account: string
  password: string
}

export interface RegisterRequest {
  username: string
  password: string
  email: string
  captcha_id: string
  captcha: string
}

export interface CaptchaResult {
  captcha_id: string
  captcha_image: string
}

export interface AuthResult {
  token: string
  user: UserProfile
}

export interface UserProfile {
  id: string
  username: string
  email: string
  role: 'USER' | 'STAFF' | 'ADMIN'
  points: number
  created_at: string
  /** RBAC 后台权限码（登录后由后端返回，前端按此渲染菜单/按钮） */
  permissions?: string[]
}

// ============================================================
// Product & Category
// ============================================================

export interface Category {
  id: string
  name: string
  sort_order: number
}

export interface ProductSpec {
  id: string
  name: string
  price: number
  stock_available: number
  card_key_count?: number
  is_visible?: boolean
  sort_order?: number
}

export interface WholesaleRule {
  min_quantity: number
  unit_price: number
}

/** Product list item (returned by GET /products) */
export interface ProductCard {
  id: string
  title: string
  description?: string
  cover_url?: string
  video_url?: string
  base_price: number
  currency?: string
  category_id: string
  stock_available: number
  has_specs: boolean
  delivery_type?: string
  sales_count?: number
  initial_sales?: number
  is_enabled?: boolean
  sort_order?: number
  created_at?: string
}

/** Full product detail (returned by GET /products/{id}) */
export interface ProductDetail extends ProductCard {
  detail_md?: string
  detail_images?: string[]
  specs: ProductSpec[]
  spec_enabled?: boolean
  wholesale_enabled: boolean
  wholesale_rules: WholesaleRule[]
  low_stock_threshold?: number
  category_name?: string
  updated_at?: string
}

// ============================================================
// Cart
// ============================================================

export interface CartItem {
  id: string
  product_id: string
  spec_id: string | null
  product_title: string
  spec_name: string | null
  cover_url?: string
  currency?: string
  unit_price: number
  quantity: number
  subtotal: number
  stock_available?: number
}

export interface Cart {
  items: CartItem[]
  total_amount: number
}

// ============================================================
// Order
// ============================================================

export type OrderStatus = 'PENDING' | 'PAID' | 'DELIVERED' | 'EXPIRED'

export type OrderType = 'DIRECT' | 'CART'

export interface OrderBrief {
  id: string
  total_amount: number
  actual_amount: number
  status: OrderStatus
  order_type: OrderType
  payment_method: string
  created_at: string
  // USDT 支付字段（仅 USDT 订单返回）
  usdt_tx_id?: string
  // TXID 审核状态（仅 USDT 订单且有审核记录时返回）
  txid_review_status?: string
  txid_review_reason?: string
}

export interface OrderItemDetail {
  id: string
  product_id: string
  product_title: string
  spec_name: string | null
  quantity: number
  unit_price: number
  subtotal: number
}

export interface OrderDetail extends OrderBrief {
  email: string
  points_deducted: number
  points_discount: number
  expires_at: string
  paid_at: string | null
  delivered_at: string | null
  items: OrderItemDetail[]
}

export interface PaymentCreateResult {
  order_id: string
  payment_url: string
  qrcode_url?: string
  pay_url?: string
  expires_at: string
  // USDT 新增（仅 USDT 渠道返回）
  wallet_address?: string
  crypto_amount?: string
  chain?: string
}

export interface TxidVerifyResult {
  result: "AUTO_APPROVED" | "AUTO_REJECTED" | "PENDING_REVIEW"
  reason: string
}

export interface CreateOrderResult {
  order: OrderDetail
  /** 0 元订单（优惠券全额抵扣）时为 null，无需支付 */
  payment: PaymentCreateResult | null
}

export interface DeliverResultGroup {
  product_title: string
  spec_name: string | null
  card_keys: string[]
}

export interface DeliverResult {
  order_id: string
  status: OrderStatus
  groups: DeliverResultGroup[]
}

// ============================================================
// Currency
// ============================================================

export interface CurrencyItem {
  code: string
  name: string
  symbol: string
}

// ============================================================
// Payment
// ============================================================

export type ProviderType = 'epay' | 'native_alipay' | 'native_wxpay' | 'usdt'

export interface PaymentChannelConfig {
  // 易支付
  pid?: string
  key?: string
  api_url?: string
  notify_url?: string
  return_url?: string
  // 原生支付宝
  appid?: string
  private_key?: string
  alipay_public_key?: string
  gateway_url?: string
  // 原生微信
  mchid?: string
  api_v3_key?: string
  serial_no?: string
  private_key_path?: string
  /** 商家支付证书 apiclient_cert.pem 的服务器绝对路径（上传后自动写入） */
  wxpay_cert_path?: string
  // USDT
  wallet_address?: string
  rate_api_url?: string
  [key: string]: string | undefined
}

export interface PaymentChannelItem {
  id: string
  channel_code: string
  channel_name: string
  provider_type: ProviderType
  config_data?: PaymentChannelConfig | null
  is_enabled: boolean
  sort_order: number
  created_at: string
}

// ============================================================
// Admin Notifications
// ============================================================

/** 消息通知模板（预设，后台勾选启用与渠道） */
export interface NotificationTemplateItem {
  id: string
  code: string
  name: string
  category: string
  title: string
  content: string
  /** 勾选的发送渠道，逗号分隔：DINGTALK,WECOM,EMAIL */
  channels: string
  enabled: boolean
  auto_trigger: boolean
  sort_order: number
}

/** 消息通知渠道配置（钉钉/企业微信机器人/通知邮箱） */
export interface NotificationChannelItem {
  id: string
  channel_type: string
  name: string
  enabled: boolean
  sort_order: number
  webhook_url?: string
  /** 钉钉加签密钥（安全设置选「加签」时必填） */
  secret?: string
  email_to?: string
}

/** 系统消息（后台铃铛列表项） */
export interface SystemMessageItem {
  id: string
  title: string
  content: string
  message_type: string
  read: boolean
  created_at: string
}

/** 通知测试发送结果（逐渠道 ✅/❌，与支付渠道测试连接一致） */
export interface NotificationTestResult {
  template_code: string
  template_name: string
  passed: boolean
  items: { name: string; status: boolean; message: string }[]
  message: string
}

/** 测试连接的单项检测结果（如 AppID、商户号、连接测试等） */
export interface PaymentTestItem {
  name: string
  status: boolean
  message: string
}

/** 测试连接的逐项清单，与 CRM 项目支付配置的测试连接输出一致 */
export interface PaymentTestResult {
  success: boolean
  message: string
  items?: PaymentTestItem[]
}

// ============================================================
// Site Config
// ============================================================

export interface SiteConfig {
  site_name: string
  site_slogan?: string
  site_description?: string
  logo_url?: string
  favicon_url?: string
  announcement_enabled: boolean
  announcement?: string
  popup_enabled: boolean
  popup_content?: string
  contact_email?: string
  contact_telegram?: string
  contact_telegram_group?: string
  contact_phone?: string
  contact_address?: string
  wechat_kefu_link?: string
  wechat_qrcode?: string
  points_enabled: boolean
  points_rate: number
  maintenance_enabled: boolean
  maintenance_message?: string
  footer_text?: string
  github_url?: string
  copyright?: string
  icp_number?: string
  police_number?: string
  custom_css?: string
}

export interface SiteConfigKV {
  config_key: string
  config_value: string
  config_group?: string
  readonly?: boolean
}

// ============================================================
// Create Order Requests
// ============================================================

export interface CreateOrderRequest {
  product_id: string
  spec_id: string | null
  quantity: number
  email: string
  payment_method: string
  use_points?: boolean
  idempotency_key: string
  device?: string
  /** 优惠券核销码（选填） */
  coupon_code?: string
}

export interface CreateCartOrderRequest {
  email: string
  payment_method: string
  use_points?: boolean
  idempotency_key: string
  device?: string
  /** 优惠券核销码（选填） */
  coupon_code?: string
}

// ============================================================
// Marketing & Coupon
// ============================================================

export type CouponType = 'AMOUNT' | 'PERCENT'

/** 优惠券适用范围：ALL 全部商品通用 / SPECIFIC 仅指定商品可用 */
export type CouponScope = 'ALL' | 'SPECIFIC'

/** 营销活动（后台管理） */
export interface MarketingCampaignItem {
  id: string
  title: string
  subject: string
  content: string
  audience_type: 'ALL_USERS' | 'USER_IDS' | 'EMAILS'
  target_json: string | null
  status: 'DRAFT' | 'SENT'
  sent_count: number
  coupon_type: CouponType | null
  coupon_value: number | null
  coupon_min_amount: number | null
  coupon_code: string | null
  coupon_quantity: number
  coupon_claimed: number
  coupon_valid_from: string | null
  coupon_valid_to: string | null
  coupon_scope: CouponScope | null
  coupon_product_ids: string | null
  created_at: string
  updated_at: string
}

export interface CampaignPayload {
  title: string
  subject?: string
  content?: string
  audience_type: string
  target_json?: string | null
  coupon_type?: CouponType | null
  coupon_value?: number | null
  coupon_min_amount?: number | null
  coupon_code?: string | null
  coupon_quantity?: number
  coupon_valid_from?: string | null
  coupon_valid_to?: string | null
  coupon_scope?: CouponScope | null
  coupon_product_ids?: string | null
}

/** 优惠券校验结果（下单页确认核销） */
export interface CouponValidateResult {
  valid: boolean
  discount?: number
  coupon_type?: CouponType
  coupon_value?: number
  message?: string
}

/** 优惠券领取结果 */
export interface CouponClaimResult {
  code: string
  coupon_type: CouponType
  coupon_value: number
  coupon_title?: string
  valid_from: string | null
  valid_to: string | null
}

/** 公开查询优惠券信息（领取页展示） */
export interface CouponInfo {
  code: string | null
  title: string
  coupon_type: CouponType
  coupon_value: number
  coupon_min_amount: number
  coupon_valid_from: string | null
  coupon_valid_to: string | null
  coupon_quantity: number
  coupon_claimed: number
  coupon_scope: CouponScope | null
  coupon_product_ids: string | null
  is_canceled: number
  is_unique: boolean
}

/** 后台：优惠券（recordType=COUPON）列表项 */
export interface MarketingCouponItem {
  id: string
  title: string
  coupon_type: CouponType | null
  coupon_value: number | null
  coupon_min_amount: number | null
  coupon_code: string | null
  coupon_quantity: number
  coupon_claimed: number
  coupon_used: number
  coupon_valid_from: string | null
  coupon_valid_to: string | null
  coupon_scope: CouponScope | null
  coupon_product_ids: string | null
  is_canceled: number
  created_at: string
  updated_at: string
}

/** 后台：优惠券创建/编辑请求 */
export interface CouponPayload {
  title: string
  coupon_type: CouponType
  coupon_value: number
  coupon_min_amount?: number
  coupon_code?: string | null
  coupon_quantity?: number
  coupon_valid_from?: string | null
  coupon_valid_to?: string | null
  coupon_scope?: CouponScope
  coupon_product_ids?: string | null
}

/** 后台：营销邮件（recordType=EMAIL）列表项 */
export interface MarketingEmailItem {
  id: string
  title: string
  subject: string | null
  content: string | null
  audience_type: 'ALL_USERS' | 'USER_IDS' | 'EMAILS'
  target_json: string | null
  status: 'DRAFT' | 'SCHEDULED' | 'SENT'
  sent_count: number
  failed_count: number
  send_at: string | null
  is_canceled: number
  coupon_ref_id: string | null
  coupon_title: string | null
  recipient_count: number
  created_at: string
  updated_at: string
}

/** 后台：营销邮件创建/编辑请求 */
export interface EmailPayload {
  title: string
  subject?: string | null
  content?: string | null
  audience_type: 'ALL_USERS' | 'USER_IDS' | 'EMAILS'
  target_json?: string | null
  send_at?: string | null
  coupon_ref_id?: string | null
}

/** 营销邮件收件人（发送用户弹窗） */
export interface MarketingRecipientItem {
  email: string
  username: string | null
  code: string | null
  delivered: number
  error: string | null
  sent_at: string | null
}

/** 收件人分页结果 + 送达统计 */
export interface RecipientsResult {
  list: MarketingRecipientItem[]
  total: number
  page: number
  page_size: number
  total_pages: number
  delivered: number
  failed: number
}

/** 个人中心：我的优惠券 */
export interface MyCouponItem {
  id: string
  code: string
  type: CouponType
  value: number
  valid_from: string | null
  valid_to: string | null
  claimed_at: string | null
  used_at: string | null
  order_id: string | null
  scope: CouponScope
  product_ids: string[]
  status: 'CLAIMED' | 'USED' | 'EXPIRED'
  campaign_title: string
  coupon_min_amount: number
}

// ============================================================
// Admin Customers
// ============================================================

export interface CustomerOverview {
  total_registered: number
  total_anonymous: number
  total_customers: number
  new_registered: number
  new_anonymous: number
  new_customers: number
  deal_registered: number
  deal_anonymous: number
  deal_customers: number
  no_deal_customers: number
}

/** 注册客户列表项 */
export interface RegisteredCustomerItem {
  id: string
  username: string
  email: string
  points: number
  is_banned: boolean
  created_at: string
  order_count: number
  paid_count: number
  total_spent: number
}

/** 匿名客户列表项 */
export interface AnonymousCustomerItem {
  email: string
  order_count: number
  paid_count: number
  total_spent: number
  first_order_at: string | null
  last_order_at: string | null
}

/** 客户订单摘要 */
export interface CustomerOrderItem {
  id: string
  email: string
  status: string
  payment_method: string | null
  total_amount: number
  actual_amount: number
  coupon_code: string | null
  coupon_discount: number
  created_at: string
  paid_at: string | null
  delivered_at: string | null
  items: {
    product_title: string
    spec_name: string | null
    quantity: number
    unit_price: number
    subtotal: number
  }[]
}

/** 注册客户详情 */
export interface RegisteredCustomerDetail extends RegisteredCustomerItem {
  registered_at: string
  orders: PaginatedData<CustomerOrderItem>
}

/** 匿名客户详情 */
export interface AnonymousCustomerDetail {
  email: string
  order_count: number
  paid_count: number
  total_spent: number
  first_order_at: string | null
  last_order_at: string | null
  orders: PaginatedData<CustomerOrderItem>
}

// ============================================================
// Points
// ============================================================

export interface PointRecord {
  change_amount: number
  balance_after: number
  reason: string
  order_id: string | null
  created_at: string
}

export interface PointsData {
  total_points: number
  list: PointRecord[]
  pagination: Pagination
}

// ============================================================
// Admin Dashboard
// ============================================================

export interface LowStockProduct {
  product_id: string
  title: string
  available_stock: number
  threshold: number
}

export interface DashboardStats {
  today_sales: number
  month_sales: number
  today_orders: number
  month_orders: number
  conversion_rate: number
  today_pv: number
  today_uv: number
  low_stock_products: LowStockProduct[]
}

export interface SalesTrend {
  date: string
  sales_amount: number
  order_count: number
}

// ============================================================
// Admin Card Keys
// ============================================================

export interface CardKeyStockSummary {
  product_id: string
  product_title: string
  spec_id: string | null
  spec_name: string | null
  spec_enabled?: boolean
  total: number
  available: number
  sold: number
  locked: number
  invalid: number
}

export interface CardKeyListItem {
  id: string
  content: string
  status: 'AVAILABLE' | 'LOCKED' | 'SOLD' | 'INVALID'
  order_id: string | null
  created_at: string
  sold_at: string | null
}

export interface CardImportBatch {
  id: string
  product_id: string
  spec_id: string | null
  imported_by: string
  total_count: number
  success_count: number
  fail_count: number
  fail_detail: string | null
  created_at: string
}

export interface OrderCardKey {
  card_key_id: string
  content: string
  product_title: string
  spec_name: string | null
  status: 'AVAILABLE' | 'LOCKED' | 'SOLD' | 'INVALID'
}

// ============================================================
// Admin Orders
// ============================================================

export interface AdminOrderItem extends OrderDetail {
  user_id: string | null
  username: string | null
  is_risk_flagged: boolean
}

// ============================================================
// Admin System (RBAC：内部员工 + 角色权限)
// ============================================================

/** 内部员工列表项（系统管理-用户管理） */
export interface SystemStaffItem {
  id: string
  username: string
  email: string
  role: 'ADMIN' | 'STAFF'
  role_id: string | null
  role_name: string | null
  is_deleted: 0 | 1
  created_at: string
}

/** 内部员工详情 */
export interface SystemStaffDetail extends SystemStaffItem {
  permissions: string[]
}

/** 角色列表项（系统管理-角色管理） */
export interface SystemRoleItem {
  id: string
  code: string
  name: string
  description: string | null
  permissions: string[]
  is_system: 0 | 1
  user_count: number
  created_at: string
}

/** 权限点（码 + 名称） */
export interface PermissionItem {
  code: string
  name: string
}

// ============================================================
// Admin Operation Logs
// ============================================================

export interface OperationLog {
  id: string
  user_id: string
  username: string
  action: string
  target_type: string
  target_id?: string
  detail?: string
  ip_address: string
  created_at: string
}

// ============================================================
// Admin Risk
// ============================================================

export interface RiskConfig {
  // 人机验证
  turnstile_enabled: boolean
  // 设备指纹限流
  device_rate_limit_enabled: boolean
  device_order_limit_per_hour: number
  device_txid_limit_per_hour: number
  txid_submit_limit_per_order: number
  device_query_limit_per_hour: number
  device_login_limit_per_hour: number
  device_register_limit_per_hour: number
  // 已有配置
  rate_limit_per_second: number
  login_attempt_limit: number
  max_purchase_per_user: number
  max_pending_orders_per_ip: number
  max_pending_orders_per_user: number
  order_expire_minutes: number
}

// ============================================================
// Admin TXID Review
// ============================================================

export type TxidReviewStatus = 'PENDING_REVIEW' | 'AUTO_APPROVED' | 'AUTO_REJECTED' | 'APPROVED' | 'REJECTED'

export interface UnmatchedTransaction {
  id: string
  order_id: string
  txid: string
  chain: string | null
  on_chain_from: string | null
  on_chain_to: string | null
  on_chain_amount: number | null
  expected_amount: number
  amount_diff: number | null
  source: 'USER_SUBMIT' | 'WEBHOOK_MISMATCH'
  status: TxidReviewStatus
  verify_reason: string | null
  reviewer_id: string | null
  reviewed_at: string | null
  submitted_at: string
  created_at: string
}

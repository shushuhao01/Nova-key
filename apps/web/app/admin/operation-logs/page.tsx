"use client"

import { useState, useEffect } from "react"
import { ChevronDown, ChevronLeft, ChevronRight, Save, Loader2, Trash2, Clock } from "lucide-react"
import { cn } from "@/lib/utils"
import { useLocale } from "@/lib/context"
import { toast } from "sonner"
import { adminLogApi, withMockFallback } from "@/services/api"
import { mockOperationLogList } from "@/lib/mock-data"
import type { OperationLog, OperationLogCleanupConfig } from "@/types"

const actionLabels: Record<string, { label: string; color: string }> = {
  "product.create": { label: "创建商品", color: "bg-emerald-500/10 text-emerald-600" },
  "product.update": { label: "修改商品", color: "bg-blue-500/10 text-blue-600" },
  "product.delete": { label: "删除商品", color: "bg-red-500/10 text-red-500" },
  "cardkey.import": { label: "导入卡密", color: "bg-violet-500/10 text-violet-600" },
  "cardkey.invalidate": { label: "作废卡密", color: "bg-red-500/10 text-red-500" },
  "order.mark_paid": { label: "手动标记已付", color: "bg-amber-500/10 text-amber-600" },
  "order.refund": { label: "订单退款", color: "bg-red-500/10 text-red-500" },
  "user.disable": { label: "禁用用户", color: "bg-red-500/10 text-red-500" },
  "user.enable": { label: "启用用户", color: "bg-emerald-500/10 text-emerald-600" },
  "config.update": { label: "更新配置", color: "bg-blue-500/10 text-blue-600" },
  "category.create": { label: "创建分类", color: "bg-emerald-500/10 text-emerald-600" },
  "category.update": { label: "修改分类", color: "bg-blue-500/10 text-blue-600" },
  "category.delete": { label: "删除分类", color: "bg-red-500/10 text-red-500" },
  "payment.create": { label: "添加支付渠道", color: "bg-emerald-500/10 text-emerald-600" },
  "payment.update": { label: "更新支付渠道", color: "bg-blue-500/10 text-blue-600" },
  "payment.delete": { label: "删除支付渠道", color: "bg-red-500/10 text-red-500" },
  "payment.test": { label: "测试支付渠道", color: "bg-amber-500/10 text-amber-600" },
  "user.toggle": { label: "切换用户状态", color: "bg-amber-500/10 text-amber-600" },
  "txid.approve": { label: "通过TXID审核", color: "bg-emerald-500/10 text-emerald-600" },
  "txid.reject": { label: "拒绝TXID审核", color: "bg-red-500/10 text-red-500" },
  "notify.create": { label: "新增通知模板", color: "bg-emerald-500/10 text-emerald-600" },
  "notify.update": { label: "更新通知模板", color: "bg-blue-500/10 text-blue-600" },
  "notify.save": { label: "保存通知渠道", color: "bg-blue-500/10 text-blue-600" },
  "notify.test": { label: "测试发送通知", color: "bg-amber-500/10 text-amber-600" },
  "notify.clear": { label: "清空系统消息", color: "bg-red-500/10 text-red-500" },
  "marketing.coupon.create": { label: "创建优惠券", color: "bg-emerald-500/10 text-emerald-600" },
  "marketing.coupon.update": { label: "编辑优惠券", color: "bg-blue-500/10 text-blue-600" },
  "marketing.coupon.cancel": { label: "作废优惠券", color: "bg-amber-500/10 text-amber-600" },
  "marketing.coupon.delete": { label: "删除优惠券", color: "bg-red-500/10 text-red-500" },
  "marketing.email.create": { label: "创建营销邮件", color: "bg-emerald-500/10 text-emerald-600" },
  "marketing.email.update": { label: "编辑营销邮件", color: "bg-blue-500/10 text-blue-600" },
  "marketing.email.delete": { label: "删除营销邮件", color: "bg-red-500/10 text-red-500" },
  "marketing.email.send": { label: "发送营销邮件", color: "bg-violet-500/10 text-violet-600" },
  "customer.toggle": { label: "封禁/解禁客户", color: "bg-amber-500/10 text-amber-600" },
  "staff.create": { label: "创建内部员工", color: "bg-emerald-500/10 text-emerald-600" },
  "staff.update": { label: "编辑内部员工", color: "bg-blue-500/10 text-blue-600" },
  "staff.password": { label: "重置员工密码", color: "bg-amber-500/10 text-amber-600" },
  "staff.toggle": { label: "切换员工状态", color: "bg-amber-500/10 text-amber-600" },
  "staff.delete": { label: "删除内部员工", color: "bg-red-500/10 text-red-500" },
  "role.create": { label: "创建角色", color: "bg-emerald-500/10 text-emerald-600" },
  "role.update": { label: "编辑角色", color: "bg-blue-500/10 text-blue-600" },
  "role.delete": { label: "删除角色", color: "bg-red-500/10 text-red-500" },
  "log.cleanup_config": { label: "保存清理配置", color: "bg-blue-500/10 text-blue-600" },
  "log.cleanup": { label: "清理操作日志", color: "bg-red-500/10 text-red-500" },
  "wechat_mp.save": { label: "保存公众号配置", color: "bg-blue-500/10 text-blue-600" },
  "wechat_mp.test": { label: "测试公众号配置", color: "bg-amber-500/10 text-amber-600" },
}

/** 操作对象（target_type）中文映射 */
const targetTypeLabels: Record<string, string> = {
  PRODUCT: "商品",
  CARD_KEY: "卡密",
  ORDER: "订单",
  USER: "用户",
  CATEGORY: "分类",
  PAYMENT_CHANNEL: "支付渠道",
  SITE_CONFIG: "网站配置",
  RISK_CONFIG: "风控配置",
  NOTIFY_TEMPLATE: "通知模板",
  NOTIFY_CHANNEL: "通知渠道",
  SYSTEM_MESSAGE: "系统消息",
  MARKETING: "营销活动",
  CUSTOMER: "客户",
  TXID_REVIEW: "TXID 审核",
  ROLE: "角色",
  OPERATION_LOG: "操作日志",
  WECHAT_MP_CONFIG: "公众号配置",
}

const ITEMS_PER_PAGE = 20

export default function AdminOperationLogsPage() {
  const { t } = useLocale()
  const [logs, setLogs] = useState<OperationLog[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [actionFilter, setActionFilter] = useState("")
  const [currentPage, setCurrentPage] = useState(1)

  // 定时清理配置
  const [cleanupConfig, setCleanupConfig] = useState<OperationLogCleanupConfig>({ enabled: true, hours: 24 })
  const [cleanupLoading, setCleanupLoading] = useState(true)
  const [cleanupSaving, setCleanupSaving] = useState(false)
  const [cleanupRunning, setCleanupRunning] = useState(false)

  const fetchLogs = async () => {
    setLoading(true)
    try {
      const data = await withMockFallback(
        () => adminLogApi.getList({
          page: currentPage,
          page_size: ITEMS_PER_PAGE,
          action: actionFilter || undefined,
        }),
        () => mockOperationLogList({
          page: currentPage,
          page_size: ITEMS_PER_PAGE,
        })
      )
      setLogs(data.list)
      setTotal(data.pagination.total)
    } catch {
      setLogs([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchLogs() }, [currentPage, actionFilter])

  // 加载定时清理配置
  useEffect(() => {
    adminLogApi.getCleanupConfig()
      .then(setCleanupConfig)
      .catch(() => { /* 接口失败保留默认值 */ })
      .finally(() => setCleanupLoading(false))
  }, [])

  const handleSaveCleanup = async () => {
    const hours = Math.max(1, Math.floor(Number(cleanupConfig.hours) || 24))
    setCleanupSaving(true)
    try {
      await adminLogApi.saveCleanupConfig({ enabled: cleanupConfig.enabled, hours })
      setCleanupConfig(prev => ({ ...prev, hours }))
      toast.success("清理配置已保存")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setCleanupSaving(false)
    }
  }

  const handleCleanupNow = async () => {
    if (!window.confirm(`确定立即清理超过 ${cleanupConfig.hours} 小时的操作日志？该操作不可恢复。`)) return
    setCleanupRunning(true)
    try {
      const res = await adminLogApi.cleanupNow()
      toast.success(`已清理 ${res.deleted} 条过期日志`)
      fetchLogs()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "清理失败")
    } finally {
      setCleanupRunning(false)
    }
  }

  const totalPages = Math.ceil(total / ITEMS_PER_PAGE)

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-foreground">{t("admin.logs")}</h1>
        <p className="text-sm text-muted-foreground">{t("admin.logsDesc")}</p>
      </div>

      {/* 定时清理配置 */}
      <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
        <div className="flex items-center gap-2">
          <Clock className="h-5 w-5 text-primary" />
          <h2 className="text-base font-semibold text-foreground">定时清理配置</h2>
        </div>
        <p className="mt-1 text-xs text-muted-foreground leading-relaxed">
          开启后系统每小时自动清理创建时间超过保留时长的操作日志（默认 24 小时），可自定义清理策略。
        </p>
        {cleanupLoading ? (
          <div className="mt-4 flex h-10 items-center justify-center">
            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
          </div>
        ) : (
          <div className="mt-4 flex flex-wrap items-end gap-4">
            <div className="flex items-center gap-3">
              <span className="text-sm font-medium text-foreground">启用定时清理</span>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 rounded-full transition-colors",
                  cleanupConfig.enabled ? "bg-primary" : "bg-muted"
                )}
                onClick={() => setCleanupConfig(prev => ({ ...prev, enabled: !prev.enabled }))}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  cleanupConfig.enabled && "translate-x-5"
                )} />
              </button>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-xs font-medium text-muted-foreground">保留时长（小时）</label>
              <input
                type="number"
                min={1}
                className="h-10 w-32 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={cleanupConfig.hours}
                onChange={(e) => setCleanupConfig(prev => ({ ...prev, hours: Number(e.target.value) }))}
              />
            </div>
            <button
              type="button"
              className="flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSaveCleanup}
              disabled={cleanupSaving}
            >
              {cleanupSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              {cleanupSaving ? "保存中..." : "保存清理配置"}
            </button>
            <button
              type="button"
              className="flex items-center gap-1.5 rounded-lg border border-red-500/40 bg-red-500/5 px-4 py-2.5 text-sm font-medium text-red-600 hover:bg-red-500/10 transition-colors disabled:opacity-50"
              onClick={handleCleanupNow}
              disabled={cleanupRunning}
            >
              {cleanupRunning ? <Loader2 className="h-4 w-4 animate-spin" /> : <Trash2 className="h-4 w-4" />}
              {cleanupRunning ? "清理中..." : "立即清理"}
            </button>
          </div>
        )}
      </div>

      {/* Filters */}
      <div className="flex flex-wrap gap-3">
        <div className="relative">
          <select
            className="h-10 appearance-none rounded-lg border border-input bg-background pl-3 pr-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            value={actionFilter}
            onChange={(e) => { setActionFilter(e.target.value); setCurrentPage(1) }}
          >
            <option value="">{t("admin.allActionTypes")}</option>
            <option value="product">{t("admin.productOps")}</option>
            <option value="cardkey">{t("admin.cardKeyOps")}</option>
            <option value="order">{t("admin.orderOps")}</option>
            <option value="user">{t("admin.userOps")}</option>
            <option value="config">{t("admin.configOps")}</option>
            <option value="category">{t("admin.categoryOps")}</option>
            <option value="payment">{t("admin.paymentOps")}</option>
            <option value="txid">{t("admin.txidOps")}</option>
            <option value="notify">通知操作</option>
            <option value="marketing">营销操作</option>
            <option value="staff">员工操作</option>
            <option value="role">角色操作</option>
            <option value="customer">客户操作</option>
          </select>
          <ChevronDown className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
        </div>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.time")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.operator")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.actionType")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.targetLabel")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.detailLabel")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.ipLabel")}</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={6} className="py-12">
                    <div className="flex items-center justify-center">
                      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    </div>
                  </td>
                </tr>
              ) : logs.length === 0 ? (
                <tr>
                  <td colSpan={6} className="py-8 text-center text-sm text-muted-foreground">{t("admin.noLogData")}</td>
                </tr>
              ) : (
                logs.map((log) => {
                  const action = actionLabels[log.action]
                  return (
                    <tr key={log.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                      <td className="px-4 py-3 text-xs text-muted-foreground whitespace-nowrap">
                        {new Date(log.created_at).toLocaleString()}
                      </td>
                      <td className="px-4 py-3 font-medium text-foreground">{log.username}</td>
                      <td className="px-4 py-3">
                        <span className={cn("rounded-full px-2.5 py-0.5 text-xs font-medium", action?.color || "bg-muted text-foreground")}>
                          {action?.label || log.action}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-foreground">
                        {targetTypeLabels[log.target_type] || log.target_type}
                        {log.target_id && (
                          <span className="ml-1 font-mono text-xs text-muted-foreground">
                            ({log.target_id.length > 12 ? `${log.target_id.slice(0, 8)}...` : log.target_id})
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-muted-foreground">{log.detail || "-"}</td>
                      <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{log.ip_address}</td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
        <div className="flex items-center justify-between border-t border-border px-4 py-3">
          <span className="text-sm text-muted-foreground">{t("admin.totalRecords")} {total} {t("admin.records")}</span>
          {totalPages > 1 && (
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                disabled={currentPage === 1}
                className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              {Array.from({ length: totalPages }, (_, i) => i + 1)
                .filter(page => page === 1 || page === totalPages || Math.abs(page - currentPage) <= 1)
                .map((page, index, array) => (
                  <div key={page} className="flex items-center gap-1">
                    {index > 0 && array[index - 1] !== page - 1 && (
                      <span className="px-1 text-muted-foreground">...</span>
                    )}
                    <button
                      type="button"
                      onClick={() => setCurrentPage(page)}
                      className={cn(
                        "flex h-8 w-8 items-center justify-center rounded-md text-sm font-medium",
                        currentPage === page
                          ? "bg-primary text-primary-foreground"
                          : "border border-input text-foreground hover:bg-accent"
                      )}
                    >
                      {page}
                    </button>
                  </div>
                ))}
              <button
                type="button"
                onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                disabled={currentPage === totalPages}
                className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
              >
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

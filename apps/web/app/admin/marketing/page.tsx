"use client"

import { useState, useEffect, useMemo, useRef, useCallback } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import {
  Megaphone, Plus, Search, Pencil, Trash2, Send, Eye, EyeOff, ChevronLeft, ChevronRight,
  Tag, Copy, X, Ticket, Mail, Ban, CircleAlert, Bold, Italic, Underline, Heading1, Link as LinkIcon,
  Image as ImageIcon, Code2, Users, CheckCircle2, XCircle, Save, List,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { useLocale } from "@/lib/context"
import { toast } from "sonner"
import { adminMarketingApi, adminProductApi, uploadApi } from "@/services/api"
import type {
  MarketingCouponItem, CouponPayload, MarketingEmailItem, EmailPayload,
  RecipientsResult, CouponScope, CouponType,
} from "@/types"

const ITEMS_PER_PAGE = 10

type AudienceType = "ALL_USERS" | "USER_IDS" | "EMAILS"

interface ProductOption { id: string; title: string }

function toDateTimeLocal(iso: string | null | undefined): string {
  if (!iso) return ""
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ""
  const pad = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

export default function AdminMarketingPage() {
  const { t } = useLocale()
  const router = useRouter()
  const searchParams = useSearchParams()

  const [tab, setTab] = useState<"coupons" | "emails">("coupons")

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.marketing")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.marketingDesc")}</p>
        </div>
        <TabSwitch tab={tab} setTab={setTab} />
      </div>

      {tab === "coupons" ? (
        <CouponsTab key="coupons" searchParams={searchParams} router={router} />
      ) : (
        <EmailsTab key="emails" searchParams={searchParams} router={router} />
      )}
    </div>
  )
}

function TabSwitch({ tab, setTab }: { tab: "coupons" | "emails"; setTab: (v: "coupons" | "emails") => void }) {
  const { t } = useLocale()
  return (
    <div className="flex rounded-lg bg-muted p-1">
      {([
        { v: "coupons" as const, label: t("admin.marketingCoupons"), icon: Ticket },
        { v: "emails" as const, label: t("admin.marketingEmails"), icon: Mail },
      ]).map(opt => (
        <button
          key={opt.v}
          type="button"
          onClick={() => setTab(opt.v)}
          className={cn(
            "flex items-center gap-2 rounded-md px-4 py-2 text-sm font-medium transition-colors",
            tab === opt.v ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
          )}
        >
          <opt.icon className="h-4 w-4" />
          {opt.label}
        </button>
      ))}
    </div>
  )
}

// ═══════════════════════ 优惠券 TAB ═══════════════════════

interface CouponFormState {
  id: string | null
  title: string
  coupon_type: CouponType
  coupon_value: string
  coupon_min_amount: string
  coupon_code: string
  coupon_quantity: string
  valid_from: string
  valid_to: string
  coupon_scope: CouponScope
  coupon_product_ids: string
}

const emptyCouponForm = (): CouponFormState => ({
  id: null,
  title: "",
  coupon_type: "AMOUNT",
  coupon_value: "",
  coupon_min_amount: "",
  coupon_code: "",
  coupon_quantity: "1",
  valid_from: "",
  valid_to: "",
  coupon_scope: "ALL",
  coupon_product_ids: "",
})

function CouponsTab({ searchParams, router }: { searchParams: ReturnType<typeof useSearchParams>; router: ReturnType<typeof useRouter> }) {
  const { t } = useLocale()
  const [list, setList] = useState<MarketingCouponItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [keyword, setKeyword] = useState("")
  const [currentPage, setCurrentPage] = useState(1)
  const [formOpen, setFormOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form, setForm] = useState<CouponFormState>(emptyCouponForm())
  const [detail, setDetail] = useState<MarketingCouponItem | null>(null)
  const [products, setProducts] = useState<ProductOption[]>([])

  // 从客户管理「营销」跳转时预填受众 → 切到营销邮件 TAB（兼容旧行为）
  useEffect(() => {
    const audience = searchParams.get("audience")
    const targets = searchParams.get("targets")
    if (audience && targets) {
      router.replace(`/admin/marketing?email_audience=${audience}&email_targets=${encodeURIComponent(targets)}`)
    }
  }, [searchParams, router])

  useEffect(() => {
    (async () => {
      try {
        const data = await adminProductApi.getList({ page: 1, page_size: 100 })
        setProducts(data.list.map(p => ({ id: p.id, title: p.title })))
      } catch {
        setProducts([])
      }
    })()
  }, [])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const data = await adminMarketingApi.getCoupons({ page: currentPage, page_size: ITEMS_PER_PAGE, keyword: keyword || undefined })
      setList(data.list)
      setTotal(data.pagination.total)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载失败")
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [currentPage, keyword])

  useEffect(() => {
    const timer = setTimeout(() => setCurrentPage(1), 300)
    return () => clearTimeout(timer)
  }, [keyword])

  useEffect(() => { fetchList() }, [fetchList])

  const totalPages = Math.max(1, Math.ceil(total / ITEMS_PER_PAGE))

  const openCreate = () => {
    setForm(emptyCouponForm())
    setFormOpen(true)
  }

  const openEdit = (c: MarketingCouponItem) => {
    let productIds = ""
    if (c.coupon_product_ids) {
      try {
        productIds = (JSON.parse(c.coupon_product_ids) as string[]).join("\n")
      } catch { productIds = c.coupon_product_ids }
    }
    setForm({
      id: c.id,
      title: c.title,
      coupon_type: c.coupon_type || "AMOUNT",
      coupon_value: c.coupon_value != null ? String(c.coupon_value) : "",
      coupon_min_amount: c.coupon_min_amount != null ? String(c.coupon_min_amount) : "",
      coupon_code: c.coupon_code || "",
      coupon_quantity: String(c.coupon_quantity ?? 1),
      valid_from: toDateTimeLocal(c.coupon_valid_from),
      valid_to: toDateTimeLocal(c.coupon_valid_to),
      coupon_scope: c.coupon_scope === "SPECIFIC" ? "SPECIFIC" : "ALL",
      coupon_product_ids: productIds,
    })
    setFormOpen(true)
  }

  const buildPayload = (): CouponPayload => {
    const productIds = form.coupon_product_ids.split(/[\n,，;；]/).map(s => s.trim()).filter(Boolean)
    return {
      title: form.title.trim(),
      coupon_type: form.coupon_type,
      coupon_value: parseFloat(form.coupon_value) || 0,
      coupon_min_amount: parseFloat(form.coupon_min_amount) || 0,
      coupon_code: form.coupon_code.trim().toUpperCase() || null,
      coupon_quantity: parseInt(form.coupon_quantity) || 1,
      coupon_valid_from: form.valid_from ? `${form.valid_from}:00` : null,
      coupon_valid_to: form.valid_to ? `${form.valid_to}:00` : null,
      coupon_scope: form.coupon_scope,
      coupon_product_ids: form.coupon_scope === "SPECIFIC" && productIds.length > 0 ? JSON.stringify(productIds) : null,
    }
  }

  const handleSave = async () => {
    if (!form.title.trim()) {
      toast.error(t("admin.campaignTitle"))
      return
    }
    const v = parseFloat(form.coupon_value)
    if (Number.isNaN(v) || v <= 0) {
      toast.error(t("admin.couponValue"))
      return
    }
    if (form.coupon_scope === "SPECIFIC"
        && form.coupon_product_ids.split(/[\n,，;；]/).map(s => s.trim()).filter(Boolean).length === 0) {
      toast.error(t("admin.couponScopeSpecificHint"))
      return
    }
    if (form.valid_from && form.valid_to && form.valid_from > form.valid_to) {
      toast.error("生效时间不能晚于结束时间")
      return
    }
    setSaving(true)
    try {
      const payload = buildPayload()
      if (form.id) {
        await adminMarketingApi.updateCoupon(form.id, payload)
      } else {
        await adminMarketingApi.createCoupon(payload)
      }
      toast.success(t("admin.save"))
      setFormOpen(false)
      fetchList()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  const handleCancel = async (c: MarketingCouponItem) => {
    if (!window.confirm(t("admin.couponCancelConfirm"))) return
    try {
      await adminMarketingApi.cancelCoupon(c.id)
      toast.success(t("admin.save"))
      fetchList()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  const handleDelete = async (c: MarketingCouponItem) => {
    if (!window.confirm(t("admin.deleteMessage"))) return
    try {
      await adminMarketingApi.deleteCoupon(c.id)
      toast.success(t("admin.delete"))
      fetchList()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败")
    }
  }

  const copyClaimLink = (c: MarketingCouponItem) => {
    if (!c.coupon_code) return
    const url = `${window.location.origin}/coupons/claim?code=${encodeURIComponent(c.coupon_code)}`
    navigator.clipboard?.writeText(url)
    toast.success(t("admin.linkCopied"))
  }

  const statusBadge = (c: MarketingCouponItem) => (
    <span className={cn(
      "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium",
      c.is_canceled === 1 ? "bg-red-500/10 text-red-500" : "bg-emerald-500/10 text-emerald-600"
    )}>
      {c.is_canceled === 1 ? t("admin.couponCanceled") : t("admin.couponNormal")}
    </span>
  )

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="relative max-w-sm">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            placeholder={t("admin.searchProduct")}
            className="h-10 w-full rounded-lg border border-input bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
        </div>
        <button
          type="button"
          onClick={openCreate}
          className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
        >
          <Plus className="h-4 w-4" />
          {t("admin.newCoupon")}
        </button>
      </div>

      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.campaignTitle")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.couponType")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.couponCode")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.couponQuantity")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.couponClaimed")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.couponUsedCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.couponValidFrom")} ~ {t("admin.couponValidTo")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.couponScope")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.couponStatus")}</th>
                <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={10} className="py-12"><div className="flex items-center justify-center"><div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" /></div></td></tr>
              ) : list.length === 0 ? (
                <tr><td colSpan={10} className="py-8 text-center text-sm text-muted-foreground">{t("admin.couponNoData")}</td></tr>
              ) : (
                list.map((c) => (
                  <tr key={c.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <Ticket className="h-4 w-4 shrink-0 text-primary" />
                        <span className={cn("font-medium", c.is_canceled === 1 && "text-muted-foreground line-through")}>{c.title}</span>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-medium text-primary">
                        <Tag className="h-3 w-3" />
                        {c.coupon_type === "AMOUNT" ? `立减 ¥${c.coupon_value}` : `减免 ${c.coupon_value}%`}
                      </span>
                      {c.coupon_min_amount && Number(c.coupon_min_amount) > 0 && (
                        <span className="ml-1 text-xs text-muted-foreground">满 {c.coupon_min_amount}</span>
                      )}
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-foreground">{c.coupon_code || "—"}</td>
                    <td className="px-4 py-3 text-muted-foreground">{c.coupon_quantity > 0 ? c.coupon_quantity : "∞"}</td>
                    <td className="px-4 py-3 text-muted-foreground">{c.coupon_claimed}</td>
                    <td className="px-4 py-3 text-muted-foreground">{c.coupon_used}</td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {c.coupon_valid_from && c.coupon_valid_to
                        ? `${new Date(c.coupon_valid_from).toLocaleString()} ~ ${new Date(c.coupon_valid_to).toLocaleString()}`
                        : "长期"}
                    </td>
                    <td className="px-4 py-3">
                      {c.coupon_scope === "SPECIFIC" ? (
                        <span className="inline-flex items-center rounded-full bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-600">
                          {t("admin.couponScopeSpecific")}
                        </span>
                      ) : (
                        <span className="text-xs text-muted-foreground">{t("admin.couponScopeAll")}</span>
                      )}
                    </td>
                    <td className="px-4 py-3">{statusBadge(c)}</td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button type="button" onClick={() => setDetail(c)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground" title={t("admin.couponDetail")}>
                          <Eye className="h-4 w-4" />
                        </button>
                        {c.is_canceled !== 1 && (
                          <button type="button" onClick={() => openEdit(c)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground" title={t("admin.edit")}>
                            <Pencil className="h-4 w-4" />
                          </button>
                        )}
                        {c.coupon_code && (
                          <button type="button" onClick={() => copyClaimLink(c)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground" title={t("admin.copyClaimLink")}>
                            <Copy className="h-4 w-4" />
                          </button>
                        )}
                        {c.is_canceled !== 1 && (
                          <button type="button" onClick={() => handleCancel(c)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-amber-500/10 hover:text-amber-600" title={t("admin.couponCancel")}>
                            <Ban className="h-4 w-4" />
                          </button>
                        )}
                        <button type="button" onClick={() => handleDelete(c)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-red-500/10 hover:text-red-500" title={t("admin.delete")}>
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        {totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-border px-4 py-3">
            <span className="text-sm text-muted-foreground">{t("admin.totalRecords")} {total}</span>
            <Pager page={currentPage} totalPages={totalPages} onChange={setCurrentPage} />
          </div>
        )}
      </div>

      {/* Detail modal */}
      {detail && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50" onClick={() => setDetail(null)} />
          <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-lg font-bold text-foreground">{t("admin.couponDetail")}</h2>
              <button type="button" onClick={() => setDetail(null)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent"><X className="h-4 w-4" /></button>
            </div>
            <dl className="flex flex-col gap-3 text-sm">
              {[
                [t("admin.campaignTitle"), detail.title],
                [t("admin.couponType"), detail.coupon_type === "AMOUNT" ? `立减 ¥${detail.coupon_value}` : `减免 ${detail.coupon_value}%`],
                [t("admin.couponMinAmount"), detail.coupon_min_amount ? `¥${detail.coupon_min_amount}` : "无门槛"],
                [t("admin.couponCode"), detail.coupon_code || "自动生成"],
                [t("admin.couponQuantity"), detail.coupon_quantity > 0 ? String(detail.coupon_quantity) : "不限量"],
                [t("admin.couponClaimed"), `${detail.coupon_claimed}`],
                [t("admin.couponUsedCount"), `${detail.coupon_used}`],
                [t("admin.couponValidFrom"), detail.coupon_valid_from ? new Date(detail.coupon_valid_from).toLocaleString() : "长期"],
                [t("admin.couponValidTo"), detail.coupon_valid_to ? new Date(detail.coupon_valid_to).toLocaleString() : "长期"],
                [t("admin.couponScope"), detail.coupon_scope === "SPECIFIC" ? t("admin.couponScopeSpecific") : t("admin.couponScopeAll")],
              ].map(([k, v]) => (
                <div key={k} className="flex justify-between gap-4">
                  <dt className="shrink-0 text-muted-foreground">{k}</dt>
                  <dd className="text-right font-medium text-foreground">{v}</dd>
                </div>
              ))}
            </dl>
            <div className="mt-6 flex justify-end">
              <button type="button" onClick={() => setDetail(null)} className="h-10 rounded-lg border border-input px-4 text-sm font-medium text-foreground hover:bg-accent">{t("admin.cancel")}</button>
            </div>
          </div>
        </div>
      )}

      {/* Create / Edit modal */}
      {formOpen && (
        <CouponFormModal
          form={form}
          setForm={setForm}
          products={products}
          saving={saving}
          onClose={() => setFormOpen(false)}
          onSave={handleSave}
        />
      )}
    </div>
  )
}

function CouponFormModal({ form, setForm, products, saving, onClose, onSave }: {
  form: CouponFormState
  setForm: (f: CouponFormState) => void
  products: ProductOption[]
  saving: boolean
  onClose: () => void
  onSave: () => void
}) {
  const { t } = useLocale()
  const upd = <K extends keyof CouponFormState>(k: K, v: CouponFormState[K]) => setForm({ ...form, [k]: v })

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative max-h-[90vh] w-full max-w-3xl overflow-y-auto rounded-xl border border-border bg-card shadow-2xl">
        <div className="sticky top-0 z-10 flex items-center justify-between border-b border-border bg-card px-6 py-4">
          <h2 className="text-lg font-bold text-foreground">{form.id ? t("admin.edit") : t("admin.newCoupon")}</h2>
          <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"><X className="h-4 w-4" /></button>
        </div>
        <div className="flex flex-col gap-5 p-6">
          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.campaignTitle")}</label>
            <input type="text" value={form.title} onChange={(e) => upd("title", e.target.value)} className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponType")}</label>
              <select value={form.coupon_type} onChange={(e) => upd("coupon_type", e.target.value as CouponType)} className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring">
                <option value="AMOUNT">{t("admin.couponAmount")}</option>
                <option value="PERCENT">{t("admin.couponPercent")}</option>
              </select>
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponValue")}</label>
              <input type="number" min={0} step="0.01" value={form.coupon_value} onChange={(e) => upd("coupon_value", e.target.value)} className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
              <p className="mt-1 text-xs text-muted-foreground">{form.coupon_type === "AMOUNT" ? t("admin.couponAmountHint") : t("admin.couponPercentHint")}</p>
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponMinAmount")}</label>
              <input type="number" min={0} step="0.01" value={form.coupon_min_amount} onChange={(e) => upd("coupon_min_amount", e.target.value)} className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponCode")}</label>
              <input type="text" value={form.coupon_code} onChange={(e) => upd("coupon_code", e.target.value)} placeholder={t("admin.couponCodeHint")} className="h-10 w-full rounded-lg border border-input bg-background px-3 font-mono text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponQuantity")}</label>
              <input type="number" min={0} value={form.coupon_quantity} onChange={(e) => upd("coupon_quantity", e.target.value)} className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
              <p className="mt-1 text-xs text-muted-foreground">{t("admin.couponQuantityDefaultHint")} · {t("admin.couponQuantityHint2")}</p>
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponValidFrom")}</label>
              <input type="datetime-local" value={form.valid_from} onChange={(e) => upd("valid_from", e.target.value)} className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponValidTo")}</label>
              <input type="datetime-local" value={form.valid_to} onChange={(e) => upd("valid_to", e.target.value)} className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
            </div>
          </div>

          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponScope")}</label>
            <div className="flex flex-wrap gap-2">
              {([
                { v: "ALL" as CouponScope, label: t("admin.couponScopeAll") },
                { v: "SPECIFIC" as CouponScope, label: t("admin.couponScopeSpecific") },
              ]).map(opt => (
                <button key={opt.v} type="button" onClick={() => upd("coupon_scope", opt.v)} className={cn(
                  "rounded-md border px-3 py-1.5 text-sm font-medium transition-colors",
                  form.coupon_scope === opt.v ? "border-primary bg-primary/10 text-primary" : "border-border text-foreground hover:border-primary/30"
                )}>{opt.label}</button>
              ))}
            </div>
          </div>

          {form.coupon_scope === "SPECIFIC" && (
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponSelectProducts")}</label>
              <div className="max-h-40 overflow-y-auto rounded-lg border border-border bg-muted/20 p-3">
                {products.length === 0 ? (
                  <p className="text-xs text-muted-foreground">{t("admin.couponNoProducts")}</p>
                ) : (
                  <div className="grid gap-1.5 sm:grid-cols-2">
                    {products.map(p => {
                      const ids = form.coupon_product_ids.split(/[\n,，;；]/).map(s => s.trim()).filter(Boolean)
                      const checked = ids.includes(p.id)
                      return (
                        <label key={p.id} className="flex cursor-pointer items-center gap-2 rounded-md border border-border bg-background px-2.5 py-1.5 text-sm transition-colors hover:bg-accent">
                          <input
                            type="checkbox"
                            checked={checked}
                            onChange={(e) => {
                              const cur = new Set(ids)
                              if (e.target.checked) cur.add(p.id)
                              else cur.delete(p.id)
                              upd("coupon_product_ids", Array.from(cur).join("\n"))
                            }}
                            className="h-4 w-4 rounded border-input text-primary focus:ring-primary"
                          />
                          <span className="truncate text-foreground">{p.title}</span>
                        </label>
                      )
                    })}
                  </div>
                )}
              </div>
              <textarea value={form.coupon_product_ids} onChange={(e) => upd("coupon_product_ids", e.target.value)} placeholder={t("admin.couponProductIdsHint")} className="mt-2 h-20 w-full rounded-lg border border-input bg-background p-3 font-mono text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
            </div>
          )}
        </div>
        <div className="sticky bottom-0 flex items-center justify-end gap-3 border-t border-border bg-card px-6 py-4">
          <button type="button" onClick={onClose} className="h-10 rounded-lg border border-input px-4 text-sm font-medium text-foreground hover:bg-accent">{t("admin.cancel")}</button>
          <button type="button" disabled={saving} onClick={onSave} className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50">
            {saving ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" /> : <Save />}
            {t("admin.save")}
          </button>
        </div>
      </div>
    </div>
  )
}

// ═══════════════════════ 营销邮件 TAB ═══════════════════════

interface EmailFormState {
  id: string | null
  title: string
  subject: string
  content: string
  audience_type: AudienceType
  targets: string
  send_at: string
  coupon_ref_id: string
}

const emptyEmailForm = (): EmailFormState => ({
  id: null,
  title: "",
  subject: "",
  content: "",
  audience_type: "ALL_USERS",
  targets: "",
  send_at: "",
  coupon_ref_id: "",
})

function EmailsTab({ searchParams, router }: { searchParams: ReturnType<typeof useSearchParams>; router: ReturnType<typeof useRouter> }) {
  const { t } = useLocale()
  const [list, setList] = useState<MarketingEmailItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [keyword, setKeyword] = useState("")
  const [statusFilter, setStatusFilter] = useState("")
  const [currentPage, setCurrentPage] = useState(1)
  const [formOpen, setFormOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [sending, setSending] = useState<string | null>(null)
  const [form, setForm] = useState<EmailFormState>(emptyEmailForm())
  const [coupons, setCoupons] = useState<MarketingCouponItem[]>([])
  const [recipients, setRecipients] = useState<{ campaignId: string; title: string } | null>(null)

  // 支持从客户管理「营销」按钮跳转预填受众（?email_audience=&email_targets=）
  useEffect(() => {
    const audience = searchParams.get("email_audience")
    const targets = searchParams.get("email_targets")
    if (audience && targets) {
      setForm(f => ({
        ...f,
        audience_type: (audience as AudienceType) === "USER_IDS" ? "USER_IDS" : "EMAILS",
        targets,
      }))
      setFormOpen(true)
      router.replace("/admin/marketing")
    }
  }, [searchParams, router])

  // 加载优惠券列表（供邮件关联选择）
  useEffect(() => {
    adminMarketingApi.getCoupons({ page: 1, page_size: 100 })
      .then(data => setCoupons(data.list))
      .catch(() => setCoupons([]))
  }, [])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const data = await adminMarketingApi.getEmails({ page: currentPage, page_size: ITEMS_PER_PAGE, keyword: keyword || undefined, status: statusFilter || undefined })
      setList(data.list)
      setTotal(data.pagination.total)
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载失败")
      setList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [currentPage, keyword, statusFilter])

  useEffect(() => {
    const timer = setTimeout(() => setCurrentPage(1), 300)
    return () => clearTimeout(timer)
  }, [keyword, statusFilter])

  useEffect(() => { fetchList() }, [fetchList])

  const totalPages = Math.max(1, Math.ceil(total / ITEMS_PER_PAGE))

  const openCreate = () => {
    setForm(emptyEmailForm())
    setFormOpen(true)
  }

  const openEdit = (c: MarketingEmailItem) => {
    let targets = ""
    if (c.target_json) {
      try {
        targets = (JSON.parse(c.target_json) as string[]).join("\n")
      } catch { targets = c.target_json }
    }
    setForm({
      id: c.id,
      title: c.title,
      subject: c.subject || "",
      content: c.content || "",
      audience_type: c.audience_type,
      targets,
      send_at: toDateTimeLocal(c.send_at),
      coupon_ref_id: c.coupon_ref_id || "",
    })
    setFormOpen(true)
  }

  const handleSave = async (sendNow: boolean) => {
    if (!form.title.trim()) {
      toast.error(t("admin.campaignTitle"))
      return
    }
    if (form.audience_type !== "ALL_USERS" && !form.targets.trim()) {
      toast.error(t("admin.targetUsersHint"))
      return
    }
    setSaving(true)
    try {
      const targetList = form.targets.split(/[\n,，;；]/).map(s => s.trim()).filter(Boolean)
      const payload: EmailPayload = {
        title: form.title.trim(),
        subject: form.subject.trim() || null,
        content: form.content || null,
        audience_type: form.audience_type,
        target_json: targetList.length > 0 ? JSON.stringify(targetList) : null,
        send_at: form.send_at ? `${form.send_at}:00` : null,
        coupon_ref_id: form.coupon_ref_id || null,
      }
      let savedId: string | null = form.id
      if (form.id) {
        await adminMarketingApi.updateEmail(form.id, payload)
      } else {
        const created = await adminMarketingApi.createEmail(payload)
        savedId = created.id
      }
      if (sendNow && savedId) {
        const res = await adminMarketingApi.sendEmail(savedId)
        if (res.scheduled) {
          toast.success(t("admin.sendScheduledTip"))
        } else {
          toast.success(t("admin.sendEmail"))
        }
      } else {
        toast.success(t("admin.save"))
      }
      setFormOpen(false)
      fetchList()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  const handleSend = async (c: MarketingEmailItem) => {
    if (!window.confirm(t("admin.sendEmailConfirm"))) return
    setSending(c.id)
    try {
      const res = await adminMarketingApi.sendEmail(c.id)
      if (res.scheduled) {
        toast.success(t("admin.sendScheduledTip"))
      } else {
        toast.success(t("admin.sendEmail"))
      }
      fetchList()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "发送失败")
    } finally {
      setSending(null)
    }
  }

  const handleDelete = async (c: MarketingEmailItem) => {
    if (!window.confirm(t("admin.deleteMessage"))) return
    try {
      await adminMarketingApi.deleteEmail(c.id)
      toast.success(t("admin.delete"))
      fetchList()
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "删除失败")
    }
  }

  const statusBadge = (c: MarketingEmailItem) => {
    const map = {
      SENT: { cls: "bg-emerald-500/10 text-emerald-600", label: t("admin.emailSent") },
      SCHEDULED: { cls: "bg-blue-500/10 text-blue-600", label: t("admin.emailScheduled") },
      DRAFT: { cls: "bg-amber-500/10 text-amber-600", label: t("admin.emailDraft") },
    }[c.status] || { cls: "bg-muted text-muted-foreground", label: c.status }
    return <span className={cn("inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium", map.cls)}>{map.label}</span>
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="relative max-w-sm">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              placeholder={t("admin.searchProduct")}
              className="h-10 w-full rounded-lg border border-input bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
          </div>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="">{t("admin.statusLabel")}</option>
            <option value="DRAFT">{t("admin.emailDraft")}</option>
            <option value="SCHEDULED">{t("admin.emailScheduled")}</option>
            <option value="SENT">{t("admin.emailSent")}</option>
          </select>
        </div>
        <button
          type="button"
          onClick={openCreate}
          className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
        >
          <Plus className="h-4 w-4" />
          {t("admin.newEmailCampaign")}
        </button>
      </div>

      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.campaignTitle")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.campaignSubject")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.emailLinkedCoupon")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.emailRecipientCount")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.emailSendAt")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
                <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={7} className="py-12"><div className="flex items-center justify-center"><div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" /></div></td></tr>
              ) : list.length === 0 ? (
                <tr><td colSpan={7} className="py-8 text-center text-sm text-muted-foreground">{t("admin.campaignNoData")}</td></tr>
              ) : (
                list.map((c) => (
                  <tr key={c.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <Mail className="h-4 w-4 shrink-0 text-primary" />
                        <span className="font-medium text-foreground">{c.title}</span>
                      </div>
                      {c.recipient_count > 0 && (
                        <button
                          type="button"
                          onClick={() => setRecipients({ campaignId: c.id, title: c.title })}
                          className="mt-0.5 inline-flex items-center gap-1 text-xs text-primary hover:underline"
                        >
                          <Users className="h-3 w-3" />
                          {t("admin.emailViewRecipients")} ({c.recipient_count})
                        </button>
                      )}
                    </td>
                    <td className="max-w-[200px] truncate px-4 py-3 text-xs text-muted-foreground">{c.subject || "—"}</td>
                    <td className="px-4 py-3">
                      {c.coupon_ref_id ? (
                        <span className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-medium text-primary">
                          <Tag className="h-3 w-3" />
                          {c.coupon_title || "优惠券"}
                        </span>
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {c.sent_count > 0
                        ? <span>{t("admin.emailDeliveredCount")} {c.sent_count - (c.failed_count || 0)} / {t("admin.emailFailedCount")} {c.failed_count || 0}</span>
                        : (c.recipient_count > 0 ? c.recipient_count : "—")}
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {c.send_at ? new Date(c.send_at).toLocaleString() : "—"}
                    </td>
                    <td className="px-4 py-3">{statusBadge(c)}</td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        {c.status !== "SENT" && (
                          <button type="button" onClick={() => openEdit(c)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground" title={t("admin.edit")}>
                            <Pencil className="h-4 w-4" />
                          </button>
                        )}
                        {c.status !== "SENT" && (
                          <button
                            type="button"
                            disabled={sending === c.id}
                            onClick={() => handleSend(c)}
                            className="flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium text-primary hover:bg-primary/10 disabled:opacity-50"
                          >
                            <Send className="h-3.5 w-3.5" />
                            {t("admin.sendEmail")}
                          </button>
                        )}
                        {c.status === "SENT" && (
                          <span className="flex h-8 items-center gap-1 rounded-md px-2 text-xs text-emerald-600">
                            <CheckCircle2 className="h-3.5 w-3.5" />
                            {t("admin.emailSent")}
                          </span>
                        )}
                        <button type="button" onClick={() => handleDelete(c)} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-red-500/10 hover:text-red-500" title={t("admin.delete")}>
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        {totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-border px-4 py-3">
            <span className="text-sm text-muted-foreground">{t("admin.totalRecords")} {total}</span>
            <Pager page={currentPage} totalPages={totalPages} onChange={setCurrentPage} />
          </div>
        )}
      </div>

      {/* Recipients modal */}
      {recipients && (
        <RecipientsModal campaignId={recipients.campaignId} title={recipients.title} onClose={() => setRecipients(null)} />
      )}

      {/* Create / Edit modal */}
      {formOpen && (
        <EmailFormModal
          form={form}
          setForm={setForm}
          coupons={coupons}
          saving={saving}
          onClose={() => setFormOpen(false)}
          onSave={handleSave}
        />
      )}
    </div>
  )
}

function RecipientsModal({ campaignId, title, onClose }: { campaignId: string; title: string; onClose: () => void }) {
  const { t } = useLocale()
  const [data, setData] = useState<RecipientsResult | null>(null)
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    adminMarketingApi.recipients(campaignId, { page, page_size: 10 })
      .then(setData)
      .catch((err) => toast.error(err instanceof Error ? err.message : "加载失败"))
      .finally(() => setLoading(false))
  }, [campaignId, page])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative flex max-h-[85vh] w-full max-w-3xl flex-col rounded-xl border border-border bg-card shadow-2xl">
        <div className="flex items-center justify-between border-b border-border px-6 py-4">
          <div>
            <h2 className="text-lg font-bold text-foreground">{t("admin.recipientsTitle")}</h2>
            <p className="text-sm text-muted-foreground">{title}</p>
          </div>
          <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"><X className="h-4 w-4" /></button>
        </div>

        <div className="grid grid-cols-3 gap-3 border-b border-border px-6 py-4">
          <div className="rounded-lg bg-muted/50 p-3 text-center">
            <p className="text-xs text-muted-foreground">{t("admin.emailRecipientCount")}</p>
            <p className="text-xl font-bold text-foreground">{data?.total ?? "—"}</p>
          </div>
          <div className="rounded-lg bg-emerald-500/10 p-3 text-center">
            <p className="text-xs text-emerald-600">{t("admin.emailDeliveredCount")}</p>
            <p className="text-xl font-bold text-emerald-600">{data?.delivered ?? "—"}</p>
          </div>
          <div className="rounded-lg bg-red-500/10 p-3 text-center">
            <p className="text-xs text-red-500">{t("admin.emailFailedCount")}</p>
            <p className="text-xl font-bold text-red-500">{data?.failed ?? "—"}</p>
          </div>
        </div>

        <div className="overflow-y-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.recipientEmail")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.recipientUsername")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.recipientCode")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.recipientSentAt")}</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={5} className="py-10"><div className="flex items-center justify-center"><div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" /></div></td></tr>
              ) : !data || data.list.length === 0 ? (
                <tr><td colSpan={5} className="py-10 text-center text-sm text-muted-foreground">{t("admin.recipientNoData")}</td></tr>
              ) : (
                data.list.map((r, idx) => (
                  <tr key={idx} className="border-b border-border/50 last:border-0">
                    <td className="px-4 py-3 text-foreground">{r.email}</td>
                    <td className="px-4 py-3 text-muted-foreground">{r.username || "—"}</td>
                    <td className="px-4 py-3 font-mono text-xs text-foreground">{r.code || "—"}</td>
                    <td className="px-4 py-3">
                      {r.delivered === 1 ? (
                        <span className="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-600">
                          <CheckCircle2 className="h-3 w-3" /> {t("admin.recipientDelivered")}
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 rounded-full bg-red-500/10 px-2 py-0.5 text-xs font-medium text-red-500" title={r.error || ""}>
                          <XCircle className="h-3 w-3" /> {t("admin.recipientFailed")}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-xs text-muted-foreground">
                      {r.sent_at ? new Date(r.sent_at).toLocaleString() : "—"}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {(data?.total_pages ?? 1) > 1 && (
          <div className="flex items-center justify-between border-t border-border px-4 py-3">
            <span className="text-sm text-muted-foreground">{t("admin.totalRecords")} {data?.total}</span>
            <Pager page={page} totalPages={data?.total_pages ?? 1} onChange={setPage} />
          </div>
        )}
      </div>
    </div>
  )
}

function EmailFormModal({ form, setForm, coupons, saving, onClose, onSave }: {
  form: EmailFormState
  setForm: (f: EmailFormState) => void
  coupons: MarketingCouponItem[]
  saving: boolean
  onClose: () => void
  onSave: (sendNow: boolean) => void
}) {
  const { t } = useLocale()
  const upd = <K extends keyof EmailFormState>(k: K, v: EmailFormState[K]) => setForm({ ...form, [k]: v })
  const [preview, setPreview] = useState(false)
  const [htmlMode, setHtmlMode] = useState(false)

  const insertPlaceholder = (ph: string) => {
    upd("content", form.content + ph)
  }

  const previewHtml = useMemo(() => {
    const html = form.content || ""
    return html
      .replace(/\{site_url\}/g, window.location.origin)
      .replace(/\{claim_url\}/g, `${window.location.origin}/coupons/claim?code=${form.coupon_ref_id ? "COUPON" : ""}`)
      .replace(/\{coupon_code\}/g, "NK-XXXXXXXX")
      .replace(/\{username\}/g, "用户")
  }, [form.content, form.coupon_ref_id])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} />
      <div className="relative max-h-[92vh] w-full max-w-4xl overflow-y-auto rounded-xl border border-border bg-card shadow-2xl">
        <div className="sticky top-0 z-10 flex items-center justify-between border-b border-border bg-card px-6 py-4">
          <h2 className="text-lg font-bold text-foreground">{form.id ? t("admin.edit") : t("admin.newEmailCampaign")}</h2>
          <button type="button" onClick={onClose} className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"><X className="h-4 w-4" /></button>
        </div>
        <div className="flex flex-col gap-5 p-6">
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.campaignTitle")}</label>
              <input type="text" value={form.title} onChange={(e) => upd("title", e.target.value)} className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.campaignSubject")}</label>
              <input type="text" value={form.subject} onChange={(e) => upd("subject", e.target.value)} className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
            </div>
          </div>

          {/* Content (rich text) */}
          <div>
            <div className="mb-1.5 flex flex-wrap items-center justify-between gap-2">
              <label className="text-sm font-medium text-foreground">{t("admin.campaignContent")}</label>
              <div className="flex items-center gap-1">
                <button type="button" onClick={() => { setPreview(false); setHtmlMode(!htmlMode) }} className="flex items-center gap-1 rounded-md border border-input px-2 py-1 text-xs text-muted-foreground hover:bg-accent">
                  <Code2 className="h-3.5 w-3.5" />
                  {t("admin.htmlSource")}
                </button>
                <button type="button" onClick={() => setPreview(!preview)} className="flex items-center gap-1 rounded-md border border-input px-2 py-1 text-xs text-muted-foreground hover:bg-accent">
                  {preview ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                  {t("admin.contentPreview")}
                </button>
              </div>
            </div>

            {preview ? (
              <iframe
                title="preview"
                srcDoc={`<html><head><style>body{font-family:sans-serif;padding:16px;color:#333}</style></head><body>${previewHtml}</body></html>`}
                className="h-72 w-full rounded-lg border border-border bg-white"
                sandbox=""
              />
            ) : htmlMode ? (
              <textarea
                value={form.content}
                onChange={(e) => upd("content", e.target.value)}
                placeholder={t("admin.emailContentPlaceholder")}
                className="h-72 w-full rounded-lg border border-input bg-background p-3 font-mono text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
            ) : (
              <RichTextEditor value={form.content} onChange={(html) => upd("content", html)} />
            )}
            <p className="mt-1 text-xs text-muted-foreground">
              <CircleAlert className="mr-1 inline h-3 w-3" />
              {t("admin.footerAutoSite")}
            </p>
          </div>

          {/* Placeholders */}
          <div className="rounded-lg border border-border bg-muted/20 p-3">
            <p className="mb-2 text-xs font-medium text-muted-foreground">{t("admin.insertPlaceholder")}</p>
            <div className="flex flex-wrap gap-2">
              {[
                { ph: "{username}", desc: t("admin.placeholderUsernameDesc") },
                { ph: "{site_url}", desc: t("admin.placeholderSiteUrlDesc") },
                { ph: "{claim_url}", desc: t("admin.placeholderClaimUrlDesc") },
                { ph: "{coupon_code}", desc: t("admin.placeholderCouponCodeDesc") },
              ].map(x => (
                <button
                  key={x.ph}
                  type="button"
                  onClick={() => insertPlaceholder(x.ph)}
                  className="group flex items-center gap-1.5 rounded-md border border-input bg-background px-2 py-1 font-mono text-xs text-primary hover:bg-primary/10"
                  title={x.desc}
                >
                  {x.ph}
                </button>
              ))}
            </div>
          </div>

          {/* Audience */}
          <div>
            <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.audienceType")}</label>
            <div className="flex flex-wrap gap-2">
              {([
                { v: "ALL_USERS" as AudienceType, label: t("admin.audienceAll") },
                { v: "USER_IDS" as AudienceType, label: t("admin.audienceUsers") },
                { v: "EMAILS" as AudienceType, label: t("admin.audienceEmails") },
              ]).map(opt => (
                <button
                  key={opt.v}
                  type="button"
                  onClick={() => upd("audience_type", opt.v)}
                  className={cn(
                    "rounded-md border px-3 py-1.5 text-sm font-medium transition-colors",
                    form.audience_type === opt.v ? "border-primary bg-primary/10 text-primary" : "border-border text-foreground hover:border-primary/30"
                  )}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            {form.audience_type !== "ALL_USERS" && (
              <div className="mt-2">
                <textarea
                  value={form.targets}
                  onChange={(e) => upd("targets", e.target.value)}
                  placeholder={form.audience_type === "USER_IDS" ? t("admin.targetUsersHint") : t("admin.targetEmailsHint")}
                  className="h-24 w-full rounded-lg border border-input bg-background p-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
              </div>
            )}
          </div>

          {/* Schedule + linked coupon */}
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.emailSendAt")}</label>
              <input
                type="datetime-local"
                value={form.send_at}
                onChange={(e) => upd("send_at", e.target.value)}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
              <p className="mt-1 text-xs text-muted-foreground">{t("admin.emailSendAtHint")}</p>
            </div>
            <div>
              <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.emailLinkedCoupon")}</label>
              <select
                value={form.coupon_ref_id}
                onChange={(e) => upd("coupon_ref_id", e.target.value)}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              >
                <option value="">{t("admin.emailNoCoupon")}</option>
                {coupons.filter(c => c.is_canceled !== 1).map(c => (
                  <option key={c.id} value={c.id}>
                    {c.title} {c.coupon_type === "AMOUNT" ? `(立减¥${c.coupon_value})` : `(减免${c.coupon_value}%)`}
                  </option>
                ))}
              </select>
              <p className="mt-1 text-xs text-muted-foreground">{t("admin.emailLinkedCouponHint")}</p>
            </div>
          </div>
        </div>
        <div className="sticky bottom-0 flex items-center justify-end gap-3 border-t border-border bg-card px-6 py-4">
          <button type="button" onClick={onClose} className="h-10 rounded-lg border border-input px-4 text-sm font-medium text-foreground hover:bg-accent">{t("admin.cancel")}</button>
          {!form.id && (
            <button type="button" disabled={saving} onClick={() => onSave(true)} className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50">
              <Send className="h-4 w-4" />
              {t("admin.sendEmail")}
            </button>
          )}
          <button type="button" disabled={saving} onClick={() => onSave(false)} className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50">
            {saving ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary-foreground border-t-transparent" /> : <Save />}
            {t("admin.save")}
          </button>
        </div>
      </div>
    </div>
  )
}

// 简易富文本编辑器（contentEditable + execCommand，支持图片上传与排版）
function RichTextEditor({ value, onChange }: { value: string; onChange: (html: string) => void }) {
  const { t } = useLocale()
  const ref = useRef<HTMLDivElement>(null)
  const [uploading, setUploading] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)

  // 外部 value 变化（如切换草稿）时同步到编辑器，避免闪烁
  useEffect(() => {
    if (ref.current && document.activeElement !== ref.current && value !== ref.current.innerHTML) {
      ref.current.innerHTML = value
    }
  }, [value])

  const exec = (command: string, arg?: string) => {
    ref.current?.focus()
    document.execCommand(command, false, arg)
    if (ref.current) onChange(ref.current.innerHTML)
  }

  const handleFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setUploading(true)
    uploadApi.image(file)
      .then(res => exec("insertImage", res.url))
      .catch(err => toast.error(err instanceof Error ? err.message : t("admin.uploadError")))
      .finally(() => {
        setUploading(false)
        if (fileRef.current) fileRef.current.value = ""
      })
  }

  const insertLink = () => {
    const url = window.prompt(t("admin.linkPrompt"), "https://")
    if (url) exec("createLink", url)
  }

  const btn = "flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-40"

  return (
    <div className="overflow-hidden rounded-lg border border-input bg-background">
      <div className="flex flex-wrap items-center gap-1 border-b border-border bg-muted/40 px-2 py-1.5">
        <button type="button" title={t("admin.editorBold")} className={btn} onClick={() => exec("bold")}><Bold className="h-4 w-4" /></button>
        <button type="button" title={t("admin.editorItalic")} className={btn} onClick={() => exec("italic")}><Italic className="h-4 w-4" /></button>
        <button type="button" title={t("admin.editorUnderline")} className={btn} onClick={() => exec("underline")}><Underline className="h-4 w-4" /></button>
        <button type="button" title={t("admin.editorHeading")} className={btn} onClick={() => exec("formatBlock", "H2")}><Heading1 className="h-4 w-4" /></button>
        <button type="button" title={t("admin.editorLink")} className={btn} onClick={insertLink}><LinkIcon className="h-4 w-4" /></button>
        <button type="button" title={t("admin.editorList")} className={btn} onClick={() => exec("insertUnorderedList")}><List className="h-4 w-4" /></button>
        <button type="button" title={t("admin.insertImage")} disabled={uploading} className={btn} onClick={() => fileRef.current?.click()}>
          {uploading ? <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent" /> : <ImageIcon className="h-4 w-4" />}
        </button>
        <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={handleFile} />
      </div>
      <div
        ref={ref}
        contentEditable
        suppressContentEditableWarning
        onInput={(e) => onChange((e.target as HTMLDivElement).innerHTML)}
        className="min-h-56 max-h-96 overflow-y-auto p-3 text-sm text-foreground outline-none [&_img]:max-w-full [&_a]:text-primary [&_a]:underline"
      />
    </div>
  )
}

function Pager({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  const pages = useMemo(() => {
    const arr: (number | "…")[] = []
    if (totalPages <= 7) {
      for (let i = 1; i <= totalPages; i++) arr.push(i)
    } else {
      arr.push(1)
      if (page > 3) arr.push("…")
      for (let i = Math.max(2, page - 1); i <= Math.min(totalPages - 1, page + 1); i++) arr.push(i)
      if (page < totalPages - 2) arr.push("…")
      arr.push(totalPages)
    }
    return arr
  }, [page, totalPages])

  return (
    <div className="flex items-center gap-1">
      <button
        type="button"
        disabled={page <= 1}
        onClick={() => onChange(page - 1)}
        className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-40"
      >
        <ChevronLeft className="h-4 w-4" />
      </button>
      {pages.map((p, idx) =>
        p === "…" ? (
          <span key={`e${idx}`} className="px-1 text-sm text-muted-foreground">…</span>
        ) : (
          <button
            key={p}
            type="button"
            onClick={() => onChange(p)}
            className={cn(
              "h-8 min-w-8 rounded-md px-2 text-sm font-medium transition-colors",
              p === page ? "bg-primary text-primary-foreground" : "border border-input text-muted-foreground hover:bg-accent hover:text-foreground"
            )}
          >
            {p}
          </button>
        )
      )}
      <button
        type="button"
        disabled={page >= totalPages}
        onClick={() => onChange(page + 1)}
        className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:opacity-40"
      >
        <ChevronRight className="h-4 w-4" />
      </button>
    </div>
  )
}
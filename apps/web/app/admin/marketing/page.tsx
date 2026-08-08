"use client"

import { useState, useEffect, useMemo } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import {
  Megaphone, Plus, Search, Pencil, Trash2, Send, Eye, EyeOff, ChevronLeft, ChevronRight,
  Tag, Copy, X,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { useLocale } from "@/lib/context"
import { toast } from "sonner"
import { adminMarketingApi, adminProductApi, withMockFallback } from "@/services/api"
import { mockCampaignList, mockProducts } from "@/lib/mock-data"
import type { MarketingCampaignItem, CampaignPayload, CouponScope } from "@/types"

const ITEMS_PER_PAGE = 10

type AudienceType = "ALL_USERS" | "USER_IDS" | "EMAILS"
type CouponKind = "AMOUNT" | "PERCENT"

interface ProductOption { id: string; title: string }

interface FormState {
  id: string | null
  title: string
  subject: string
  content: string
  audience_type: AudienceType
  targets: string
  has_coupon: boolean
  coupon_type: CouponKind
  coupon_value: string
  coupon_min_amount: string
  coupon_code: string
  coupon_quantity: string
  valid_from: string
  valid_to: string
  coupon_scope: CouponScope
  coupon_product_ids: string
}

const emptyForm = (): FormState => ({
  id: null,
  title: "",
  subject: "",
  content: "",
  audience_type: "ALL_USERS",
  targets: "",
  has_coupon: false,
  coupon_type: "AMOUNT",
  coupon_value: "",
  coupon_min_amount: "",
  coupon_code: "",
  coupon_quantity: "0",
  valid_from: "",
  valid_to: "",
  coupon_scope: "ALL",
  coupon_product_ids: "",
})

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

  const [campaigns, setCampaigns] = useState<MarketingCampaignItem[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [keyword, setKeyword] = useState("")
  const [currentPage, setCurrentPage] = useState(1)
  const [formOpen, setFormOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [sending, setSending] = useState<string | null>(null)
  const [preview, setPreview] = useState(false)
  const [form, setForm] = useState<FormState>(emptyForm())
  const [products, setProducts] = useState<ProductOption[]>([])

  // 加载商品列表（优惠券「指定商品可用」时选择）
  useEffect(() => {
    (async () => {
      try {
        const data = await withMockFallback<{ list: ProductOption[]; pagination: { page: number; page_size: number; total: number } }>(
          () => adminProductApi.getList({ page: 1, page_size: 100 }),
          () => ({ list: mockProducts.map(p => ({ id: p.id, title: p.title })), pagination: { page: 1, page_size: 100, total: mockProducts.length } })
        )
        setProducts(data.list)
      } catch {
        setProducts([])
      }
    })()
  }, [])

  // 支持从客户管理「营销」按钮跳转预填受众（?audience=EMAILS&targets=a@b.com,c@d.com）
  useEffect(() => {
    const audience = searchParams.get("audience")
    const targets = searchParams.get("targets")
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

  const fetchCampaigns = async () => {
    setLoading(true)
    try {
      const data = await withMockFallback(
        () => adminMarketingApi.getCampaigns({ page: currentPage, page_size: ITEMS_PER_PAGE, keyword: keyword || undefined }),
        () => mockCampaignList({ keyword: keyword || undefined, page: currentPage, page_size: ITEMS_PER_PAGE })
      )
      setCampaigns(data.list)
      setTotal(data.pagination.total)
    } catch {
      setCampaigns([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    const timer = setTimeout(() => setCurrentPage(1), 300)
    return () => clearTimeout(timer)
  }, [keyword])

  useEffect(() => { fetchCampaigns() }, [currentPage, keyword])

  const totalPages = Math.max(1, Math.ceil(total / ITEMS_PER_PAGE))

  const openCreate = () => {
    setForm(emptyForm())
    setPreview(false)
    setFormOpen(true)
  }

  const openEdit = (c: MarketingCampaignItem) => {
    let targets = ""
    if (c.target_json) {
      try {
        const arr = JSON.parse(c.target_json) as string[]
        targets = arr.join("\n")
      } catch { targets = c.target_json }
    }
    let couponProductIds = ""
    if (c.coupon_product_ids) {
      try {
        const arr = JSON.parse(c.coupon_product_ids) as string[]
        couponProductIds = arr.join("\n")
      } catch { couponProductIds = c.coupon_product_ids }
    }
    setForm({
      id: c.id,
      title: c.title,
      subject: c.subject,
      content: c.content,
      audience_type: c.audience_type,
      targets,
      has_coupon: !!c.coupon_type,
      coupon_type: c.coupon_type || "AMOUNT",
      coupon_value: c.coupon_value != null ? String(c.coupon_value) : "",
      coupon_min_amount: c.coupon_min_amount != null ? String(c.coupon_min_amount) : "",
      coupon_code: c.coupon_code || "",
      coupon_quantity: String(c.coupon_quantity ?? 0),
      valid_from: toDateTimeLocal(c.coupon_valid_from),
      valid_to: toDateTimeLocal(c.coupon_valid_to),
      coupon_scope: c.coupon_scope === "SPECIFIC" ? "SPECIFIC" : "ALL",
      coupon_product_ids: couponProductIds,
    })
    setPreview(false)
    setFormOpen(true)
  }

  const buildPayload = (): CampaignPayload => {
    const targetList = form.targets.split(/[\n,，;；]/).map(s => s.trim()).filter(Boolean)
    const payload: CampaignPayload = {
      title: form.title.trim(),
      subject: form.subject.trim(),
      content: form.content,
      audience_type: form.audience_type,
      target_json: targetList.length > 0 ? JSON.stringify(targetList) : null,
      coupon_type: null,
    }
    if (form.has_coupon) {
      const v = parseFloat(form.coupon_value)
      if (!Number.isNaN(v) && v > 0) {
        payload.coupon_type = form.coupon_type
        payload.coupon_value = v
        payload.coupon_min_amount = parseFloat(form.coupon_min_amount) || 0
        payload.coupon_code = form.coupon_code.trim().toUpperCase() || null
        payload.coupon_quantity = parseInt(form.coupon_quantity) || 0
        payload.coupon_valid_from = form.valid_from ? `${form.valid_from}:00` : null
        payload.coupon_valid_to = form.valid_to ? `${form.valid_to}:00` : null
        payload.coupon_scope = form.coupon_scope
        const productIds = form.coupon_product_ids.split(/[\n,，;；]/).map(s => s.trim()).filter(Boolean)
        payload.coupon_product_ids = form.coupon_scope === "SPECIFIC" && productIds.length > 0
          ? JSON.stringify(productIds)
          : null
      }
    }
    return payload
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
    if (form.has_coupon && (!form.coupon_value || Number.isNaN(parseFloat(form.coupon_value)) || parseFloat(form.coupon_value) <= 0)) {
      toast.error(t("admin.couponValue"))
      return
    }
    if (form.has_coupon && form.coupon_scope === "SPECIFIC"
        && form.coupon_product_ids.split(/[\n,，;；]/).map(s => s.trim()).filter(Boolean).length === 0) {
      toast.error(t("admin.couponScopeSpecificHint"))
      return
    }
    setSaving(true)
    try {
      const payload = buildPayload()
      let savedId: string | null = form.id
      if (form.id) {
        await withMockFallback(
          () => adminMarketingApi.update(form.id!, payload),
          () => null as unknown as MarketingCampaignItem
        )
      } else {
        const created = await withMockFallback(
          () => adminMarketingApi.create(payload),
          () => ({ id: "new-" + Date.now() }) as unknown as MarketingCampaignItem
        )
        savedId = (created as MarketingCampaignItem).id
      }
      // 新建后立即发送（编辑态通过列表中的「发送」按钮操作）
      if (sendNow && savedId) {
        await withMockFallback(
          () => adminMarketingApi.send(savedId!),
          () => ({ sent: 1 })
        )
      }
      toast.success(sendNow ? t("admin.sendCampaign") : t("admin.save"))
      setFormOpen(false)
      fetchCampaigns()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  const handleSend = async (c: MarketingCampaignItem) => {
    if (!window.confirm(t("admin.sendCampaignConfirm"))) return
    setSending(c.id)
    try {
      await withMockFallback(
        () => adminMarketingApi.send(c.id),
        () => ({ sent: c.sent_count })
      )
      toast.success(t("admin.sendCampaign"))
      fetchCampaigns()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "发送失败")
    } finally {
      setSending(null)
    }
  }

  const handleDelete = async (c: MarketingCampaignItem) => {
    if (!window.confirm(t("admin.deleteMessage"))) return
    try {
      await withMockFallback(
        () => adminMarketingApi.delete(c.id),
        () => null
      )
      toast.success(t("admin.delete"))
      fetchCampaigns()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "删除失败")
    }
  }

  const insertPlaceholder = (ph: string) => {
    setForm(f => ({ ...f, content: f.content + ph }))
  }

  const previewHtml = useMemo(() => {
    const html = form.content || ""
    return html
      .replace(/\{site_url\}/g, window.location.origin)
      .replace(/\{claim_url\}/g, `${window.location.origin}/coupons/claim?code=${form.coupon_code || ""}`)
      .replace(/\{coupon_code\}/g, form.coupon_code || "")
      .replace(/\{site_name\}/g, "")
  }, [form.content, form.coupon_code])

  const audienceLabel = (c: MarketingCampaignItem) => {
    if (c.audience_type === "USER_IDS") return t("admin.audienceUsers")
    if (c.audience_type === "EMAILS") return t("admin.audienceEmails")
    return t("admin.audienceAll")
  }

  const statusBadge = (c: MarketingCampaignItem) => (
    <span className={cn(
      "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium",
      c.status === "SENT"
        ? "bg-emerald-500/10 text-emerald-600"
        : "bg-amber-500/10 text-amber-600"
    )}>
      {c.status === "SENT" ? t("admin.campaignSent") : t("admin.campaignDraft")}
    </span>
  )

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.marketing")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.marketingDesc")}</p>
        </div>
        <button
          type="button"
          onClick={openCreate}
          className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
        >
          <Plus className="h-4 w-4" />
          {t("admin.newCampaign")}
        </button>
      </div>

      {/* Search */}
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

      {/* Campaign table */}
      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.campaignTitle")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.audienceType")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">优惠券</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.statusLabel")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.couponClaimed")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.registeredAt")}</th>
                <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={7} className="py-12">
                    <div className="flex items-center justify-center">
                      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    </div>
                  </td>
                </tr>
              ) : campaigns.length === 0 ? (
                <tr>
                  <td colSpan={7} className="py-8 text-center text-sm text-muted-foreground">{t("admin.campaignNoData")}</td>
                </tr>
              ) : (
                campaigns.map((c) => (
                  <tr key={c.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <Megaphone className="h-4 w-4 shrink-0 text-primary" />
                        <span className="font-medium text-foreground">{c.title}</span>
                      </div>
                      {c.subject && <p className="mt-0.5 text-xs text-muted-foreground">{c.subject}</p>}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">{audienceLabel(c)}</td>
                    <td className="px-4 py-3">
                      {c.coupon_type ? (
                        <span className="inline-flex items-center gap-1.5 rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-medium text-primary">
                          <Tag className="h-3 w-3" />
                          {c.coupon_type === "AMOUNT" ? `立减 ¥${c.coupon_value}` : `减免 ${c.coupon_value}%`}
                          {c.coupon_code && <span className="font-mono">({c.coupon_code})</span>}
                        </span>
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                      {c.coupon_type && c.coupon_scope === "SPECIFIC" && (
                        <span className="ml-1.5 inline-flex items-center rounded-full bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-600">
                          {t("admin.couponScopeSpecific")}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3">{statusBadge(c)}</td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {c.coupon_type ? `${c.coupon_claimed}/${c.coupon_quantity > 0 ? c.coupon_quantity : "∞"}` : "—"}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {new Date(c.created_at).toLocaleString()}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => openEdit(c)}
                          className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
                          title={t("admin.edit")}
                        >
                          <Pencil className="h-4 w-4" />
                        </button>
                        {c.status !== "SENT" && (
                          <button
                            type="button"
                            disabled={sending === c.id}
                            onClick={() => handleSend(c)}
                            className="flex h-8 items-center gap-1 rounded-md px-2 text-xs font-medium text-primary hover:bg-primary/10 disabled:opacity-50"
                          >
                            <Send className="h-3.5 w-3.5" />
                            {t("admin.sendCampaign")}
                          </button>
                        )}
                        <button
                          type="button"
                          onClick={() => handleDelete(c)}
                          className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-red-500/10 hover:text-red-500"
                          title={t("admin.delete")}
                        >
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
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                disabled={currentPage === 1}
                className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              {Array.from({ length: totalPages }, (_, i) => i + 1).map((page) => (
                <button
                  key={page}
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
          </div>
        )}
      </div>

      {/* Create / Edit form modal */}
      {formOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50" onClick={() => setFormOpen(false)} />
          <div className="relative max-h-[90vh] w-full max-w-3xl overflow-y-auto rounded-xl border border-border bg-card shadow-2xl">
            {/* Modal header */}
            <div className="sticky top-0 z-10 flex items-center justify-between border-b border-border bg-card px-6 py-4">
              <h2 className="text-lg font-bold text-foreground">
                {form.id ? t("admin.edit") : t("admin.newCampaign")}
              </h2>
              <button
                type="button"
                onClick={() => setFormOpen(false)}
                className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="flex flex-col gap-5 p-6">
              {/* Title */}
              <div>
                <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.campaignTitle")}</label>
                <input
                  type="text"
                  value={form.title}
                  onChange={(e) => setForm(f => ({ ...f, title: e.target.value }))}
                  className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
              </div>

              {/* Subject */}
              <div>
                <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.campaignSubject")}</label>
                <input
                  type="text"
                  value={form.subject}
                  onChange={(e) => setForm(f => ({ ...f, subject: e.target.value }))}
                  className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
              </div>

              {/* Content */}
              <div>
                <div className="mb-1.5 flex items-center justify-between">
                  <label className="text-sm font-medium text-foreground">{t("admin.campaignContent")}</label>
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-muted-foreground">{t("admin.insertPlaceholder")}</span>
                    {[
                      { label: "site_url", ph: "{site_url}" },
                      { label: "claim_url", ph: "{claim_url}" },
                      { label: "coupon_code", ph: "{coupon_code}" },
                    ].map(x => (
                      <button
                        key={x.ph}
                        type="button"
                        onClick={() => insertPlaceholder(x.ph)}
                        className="rounded-md border border-input px-2 py-1 font-mono text-xs text-primary hover:bg-primary/10"
                      >
                        {x.ph}
                      </button>
                    ))}
                    <button
                      type="button"
                      onClick={() => setPreview(!preview)}
                      className="flex items-center gap-1 rounded-md border border-input px-2 py-1 text-xs text-muted-foreground hover:bg-accent"
                    >
                      {preview ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
                      {t("admin.contentPreview")}
                    </button>
                  </div>
                </div>
                {preview ? (
                  <iframe
                    title="preview"
                    srcDoc={`<html><head><style>body{font-family:sans-serif;padding:16px;color:#333}</style></head><body>${previewHtml}</body></html>`}
                    className="h-64 w-full rounded-lg border border-border bg-white"
                    sandbox=""
                  />
                ) : (
                  <textarea
                    value={form.content}
                    onChange={(e) => setForm(f => ({ ...f, content: e.target.value }))}
                    placeholder='<h2>欢迎光临 {site_url}</h2><p>点击 <a href="{claim_url}">这里</a> 领取优惠券，核销码：{coupon_code}</p>'
                    className="h-64 w-full rounded-lg border border-input bg-background p-3 font-mono text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  />
                )}
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
                      onClick={() => setForm(f => ({ ...f, audience_type: opt.v }))}
                      className={cn(
                        "rounded-md border px-3 py-1.5 text-sm font-medium transition-colors",
                        form.audience_type === opt.v
                          ? "border-primary bg-primary/10 text-primary"
                          : "border-border text-foreground hover:border-primary/30"
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
                      onChange={(e) => setForm(f => ({ ...f, targets: e.target.value }))}
                      placeholder={form.audience_type === "USER_IDS" ? t("admin.targetUsersHint") : t("admin.targetEmailsHint")}
                      className="h-24 w-full rounded-lg border border-input bg-background p-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                    />
                  </div>
                )}
              </div>

              {/* Coupon config */}
              <div className="rounded-lg border border-border p-4">
                <label className="flex items-center gap-2 text-sm font-medium text-foreground">
                  <input
                    type="checkbox"
                    checked={form.has_coupon}
                    onChange={(e) => setForm(f => ({ ...f, has_coupon: e.target.checked }))}
                    className="h-4 w-4 rounded border-input text-primary focus:ring-primary"
                  />
                  {t("admin.couponConfig")}
                </label>

                {form.has_coupon && (
                  <div className="mt-4 grid gap-4 sm:grid-cols-2">
                    <div>
                      <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponType")}</label>
                      <select
                        value={form.coupon_type}
                        onChange={(e) => setForm(f => ({ ...f, coupon_type: e.target.value as CouponKind }))}
                        className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                      >
                        <option value="AMOUNT">{t("admin.couponAmount")}</option>
                        <option value="PERCENT">{t("admin.couponPercent")}</option>
                      </select>
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponValue")}</label>
                      <input
                        type="number"
                        min={0}
                        step="0.01"
                        value={form.coupon_value}
                        onChange={(e) => setForm(f => ({ ...f, coupon_value: e.target.value }))}
                        className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                      />
                      <p className="mt-1 text-xs text-muted-foreground">
                        {form.coupon_type === "AMOUNT" ? t("admin.couponAmountHint") : t("admin.couponPercentHint")}
                      </p>
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponMinAmount")}</label>
                      <input
                        type="number"
                        min={0}
                        step="0.01"
                        value={form.coupon_min_amount}
                        onChange={(e) => setForm(f => ({ ...f, coupon_min_amount: e.target.value }))}
                        className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponCode")}</label>
                      <input
                        type="text"
                        value={form.coupon_code}
                        onChange={(e) => setForm(f => ({ ...f, coupon_code: e.target.value }))}
                        placeholder={t("admin.couponCodeHint")}
                        className="h-10 w-full rounded-lg border border-input bg-background px-3 font-mono text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponQuantity")}</label>
                      <input
                        type="number"
                        min={0}
                        value={form.coupon_quantity}
                        onChange={(e) => setForm(f => ({ ...f, coupon_quantity: e.target.value }))}
                        className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponValidFrom")}</label>
                      <input
                        type="datetime-local"
                        value={form.valid_from}
                        onChange={(e) => setForm(f => ({ ...f, valid_from: e.target.value }))}
                        className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponValidTo")}</label>
                      <input
                        type="datetime-local"
                        value={form.valid_to}
                        onChange={(e) => setForm(f => ({ ...f, valid_to: e.target.value }))}
                        className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                      />
                    </div>

                    {/* 适用范围：全部商品通用 / 指定商品可用 */}
                    <div className="sm:col-span-2">
                      <label className="mb-1.5 block text-sm font-medium text-foreground">{t("admin.couponScope")}</label>
                      <div className="flex flex-wrap gap-2">
                        {([
                          { v: "ALL" as CouponScope, label: t("admin.couponScopeAll") },
                          { v: "SPECIFIC" as CouponScope, label: t("admin.couponScopeSpecific") },
                        ]).map(opt => (
                          <button
                            key={opt.v}
                            type="button"
                            onClick={() => setForm(f => ({ ...f, coupon_scope: opt.v, coupon_product_ids: "" }))}
                            className={cn(
                              "rounded-md border px-3 py-1.5 text-sm font-medium transition-colors",
                              form.coupon_scope === opt.v
                                ? "border-primary bg-primary/10 text-primary"
                                : "border-border text-foreground hover:border-primary/30"
                            )}
                          >
                            {opt.label}
                          </button>
                        ))}
                      </div>
                    </div>

                    {form.coupon_scope === "SPECIFIC" && (
                      <div className="sm:col-span-2">
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
                                  <label
                                    key={p.id}
                                    className="flex cursor-pointer items-center gap-2 rounded-md border border-border bg-background px-2.5 py-1.5 text-sm transition-colors hover:bg-accent"
                                  >
                                    <input
                                      type="checkbox"
                                      checked={checked}
                                      onChange={(e) => {
                                        const cur = new Set(ids)
                                        if (e.target.checked) cur.add(p.id)
                                        else cur.delete(p.id)
                                        setForm(f => ({ ...f, coupon_product_ids: Array.from(cur).join("\n") }))
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
                        <textarea
                          value={form.coupon_product_ids}
                          onChange={(e) => setForm(f => ({ ...f, coupon_product_ids: e.target.value }))}
                          placeholder={t("admin.couponProductIdsHint")}
                          className="mt-2 h-20 w-full rounded-lg border border-input bg-background p-3 font-mono text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                        />
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>

            {/* Modal footer */}
            <div className="sticky bottom-0 flex items-center justify-end gap-3 border-t border-border bg-card px-6 py-4">
              {form.has_coupon && form.coupon_code && (
                <button
                  type="button"
                  onClick={() => {
                    navigator.clipboard?.writeText(`${window.location.origin}/coupons/claim?code=${form.coupon_code}`)
                    toast.success(t("admin.placeholderClaimUrl"))
                  }}
                  className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground"
                >
                  <Copy className="h-3.5 w-3.5" />
                  {t("admin.placeholderClaimUrl")}
                </button>
              )}
              <button
                type="button"
                onClick={() => setFormOpen(false)}
                className="h-10 rounded-lg border border-input px-4 text-sm font-medium text-foreground hover:bg-accent"
              >
                {t("admin.cancel")}
              </button>
              {!form.id && (
                <button
                  type="button"
                  disabled={saving}
                  onClick={() => handleSave(true)}
                  className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
                >
                  <Send className="h-4 w-4" />
                  {t("admin.sendCampaign")}
                </button>
              )}
              <button
                type="button"
                disabled={saving}
                onClick={() => handleSave(false)}
                className="h-10 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110 disabled:opacity-50"
              >
                {t("admin.save")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

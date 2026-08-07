"use client"

import { useState, useEffect, useCallback, useRef } from "react"
import { Save, AlertTriangle, Upload, Loader2, ImagePlus, Mail, Send } from "lucide-react"
import { cn } from "@/lib/utils"
import { toast } from "sonner"
import { adminConfigApi, adminProductApi, withMockFallback } from "@/services/api"
import { mockSiteConfigKVs } from "@/lib/mock-data"
import { useLocale } from "@/lib/context"
import type { SiteConfigKV } from "@/types"

const ALLOWED_IMAGE_TYPES = ["image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/svg+xml"]
const ALLOWED_IMAGE_ACCEPT = ".jpg,.jpeg,.png,.gif,.webp,.bmp,.svg"

function validateImageFile(file: File): string | null {
  if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
    return "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/BMP/SVG"
  }
  if (file.size > 10 * 1024 * 1024) {
    return "图片大小不能超过 10MB"
  }
  return null
}

type TabKey = "basic" | "announcement" | "points" | "contact" | "email" | "maintenance"

export default function AdminSiteConfigPage() {
  const { t } = useLocale()
  const [tab, setTab] = useState<TabKey>("basic")
  const [configMap, setConfigMap] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [logoUploading, setLogoUploading] = useState(false)
  const [popupUploading, setPopupUploading] = useState(false)
  const [testEmailTo, setTestEmailTo] = useState("")
  const [testSending, setTestSending] = useState(false)
  const popupTextareaRef = useRef<HTMLTextAreaElement>(null)

  const fetchConfig = useCallback(async () => {
    setLoading(true)
    try {
      const data = await withMockFallback(
        () => adminConfigApi.get(),
        () => [...mockSiteConfigKVs]
      )
      const map: Record<string, string> = {}
      data.forEach((kv: SiteConfigKV) => { map[kv.config_key] = kv.config_value })
      setConfigMap(map)
    } catch {
      const map: Record<string, string> = {}
      mockSiteConfigKVs.forEach((kv) => { map[kv.config_key] = kv.config_value })
      setConfigMap(map)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { fetchConfig() }, [fetchConfig])

  const getValue = (key: string) => configMap[key] ?? ""
  const setValue = (key: string, value: string) => {
    setConfigMap(prev => ({ ...prev, [key]: value }))
  }
  const getBool = (key: string) => configMap[key] === "true"
  const toggleBool = (key: string) => {
    setConfigMap(prev => ({ ...prev, [key]: prev[key] === "true" ? "false" : "true" }))
  }

  const handleSave = async () => {
    setSaving(true)
    try {
      // SMTP 密码：留空或 __SET__ 表示不修改，剔除后不提交（后端也跳过空值）
      const configs = Object.entries(configMap)
        .filter(([key, value]) => !(key === "smtp_password" && (value === "" || value === "__SET__")))
        .map(([config_key, config_value]) => ({
          config_key,
          config_value,
        }))
      await withMockFallback(
        () => adminConfigApi.update({ configs }),
        () => null
      )
      toast.success("保存成功")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  const handleToggleMaintenance = async () => {
    const newEnabled = !getBool("maintenance_enabled")
    try {
      await withMockFallback(
        () => adminConfigApi.toggleMaintenance(newEnabled),
        () => null
      )
      setValue("maintenance_enabled", String(newEnabled))
      toast.success(newEnabled ? "已开启维护模式" : "已关闭维护模式")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  // 先保存当前表单的邮件配置，再用最新配置发测试邮件
  const handleTestEmail = async () => {
    if (!testEmailTo.trim()) {
      toast.error("请输入测试收件邮箱")
      return
    }
    setTestSending(true)
    try {
      const configs = Object.entries(configMap)
        .filter(([key, value]) => !(key === "smtp_password" && (value === "" || value === "__SET__")))
        .map(([config_key, config_value]) => ({ config_key, config_value }))
      await withMockFallback(
        () => adminConfigApi.update({ configs }),
        () => null
      )
      await withMockFallback(
        () => adminConfigApi.testEmail(testEmailTo.trim()),
        () => null
      )
      toast.success("测试邮件已发送，请查收")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "测试邮件发送失败")
    } finally {
      setTestSending(false)
    }
  }

  if (loading) {
    return (
      <div className="flex flex-col gap-6">
        <div>
          <h1 className="text-2xl font-bold text-foreground">{t("admin.siteConfig")}</h1>
          <p className="text-sm text-muted-foreground">{t("admin.siteConfigDesc")}</p>
        </div>
        <div className="h-64 animate-pulse rounded-xl bg-muted" />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-foreground">{t("admin.siteConfig")}</h1>
        <p className="text-sm text-muted-foreground">{t("admin.siteConfigDesc")}</p>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 overflow-x-auto border-b border-border">
        {([
          { key: "basic" as const, label: t("admin.basicInfo") },
          { key: "announcement" as const, label: t("admin.announcementTab") },
          { key: "points" as const, label: t("admin.pointsSettings") },
          { key: "contact" as const, label: t("admin.contactTab") },
          { key: "email" as const, label: t("admin.emailSettings") },
          { key: "maintenance" as const, label: t("admin.maintenanceTab") },
        ]).map((tabItem) => (
          <button
            key={tabItem.key}
            type="button"
            className={cn(
              "whitespace-nowrap px-4 py-2.5 text-sm font-medium border-b-2 transition-colors",
              tab === tabItem.key
                ? "border-primary text-primary"
                : "border-transparent text-muted-foreground hover:text-foreground"
            )}
            onClick={() => setTab(tabItem.key)}
          >
            {tabItem.label}
          </button>
        ))}
      </div>

      {/* Basic Info */}
      {tab === "basic" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex flex-col gap-5 max-w-xl">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.siteName")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("site_name")}
                onChange={(e) => setValue("site_name", e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.siteSlogan")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("site_slogan")}
                onChange={(e) => setValue("site_slogan", e.target.value)}
                placeholder="Unlock Your AI Potential"
              />
              <p className="text-xs text-muted-foreground">{t("admin.siteSloganHint")}</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.siteDesc")}</label>
              <textarea
                className="min-h-20 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("site_description")}
                onChange={(e) => setValue("site_description", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">{t("admin.siteDescHint")}</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.logoUrl")}</label>
              <div className="flex gap-2">
                <input
                  type="text"
                  className="h-10 flex-1 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder="https://..."
                  value={getValue("logo_url")}
                  onChange={(e) => setValue("logo_url", e.target.value)}
                />
                <label className={cn("flex h-10 shrink-0 cursor-pointer items-center gap-1.5 rounded-lg border border-input bg-background px-3 text-sm font-medium text-foreground hover:bg-accent transition-colors", logoUploading && "pointer-events-none opacity-50")}>
                  {logoUploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
                  上传
                  <input
                    type="file"
                    accept={ALLOWED_IMAGE_ACCEPT}
                    className="hidden"
                    onChange={async (e) => {
                      const file = e.target.files?.[0]
                      if (!file) return
                      const err = validateImageFile(file)
                      if (err) { toast.error(err); e.target.value = ""; return }
                      setLogoUploading(true)
                      try {
                        const result = await adminProductApi.uploadImage(file)
                        setValue("logo_url", result.url)
                        toast.success("上传成功")
                      } catch (err: unknown) {
                        toast.error(err instanceof Error ? err.message : "上传失败")
                      } finally {
                        setLogoUploading(false)
                        e.target.value = ""
                      }
                    }}
                  />
                </label>
              </div>
              <p className="text-xs text-muted-foreground">建议使用正方形 Logo 图片，支持 JPG/PNG/GIF/WebP/SVG</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.footerText")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("footer_text")}
                onChange={(e) => setValue("footer_text", e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.githubUrl")}</label>
              <input
                type="url"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="https://github.com/..."
                value={getValue("github_url")}
                onChange={(e) => setValue("github_url", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">{t("admin.githubUrlHint")}</p>
            </div>
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}

      {/* Announcement */}
      {tab === "announcement" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex flex-col gap-5 max-w-xl">
            {/* ① 顶栏滚动公告开关 */}
            <div className="flex items-center justify-between">
              <label className="text-sm font-medium text-foreground">{t("admin.enableAnnouncement")}</label>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 rounded-full transition-colors",
                  getBool("announcement_enabled") ? "bg-primary" : "bg-muted"
                )}
                onClick={() => toggleBool("announcement_enabled")}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  getBool("announcement_enabled") && "translate-x-5"
                )} />
              </button>
            </div>
            {/* ② 顶栏滚动公告内容 */}
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.scrollAnnouncement")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("announcement")}
                onChange={(e) => setValue("announcement", e.target.value)}
              />
            </div>
            {/* ③ 弹窗公告开关 */}
            <div className="flex items-center justify-between">
              <label className="text-sm font-medium text-foreground">{t("admin.enablePopup")}</label>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 rounded-full transition-colors",
                  getBool("popup_enabled") ? "bg-primary" : "bg-muted"
                )}
                onClick={() => toggleBool("popup_enabled")}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  getBool("popup_enabled") && "translate-x-5"
                )} />
              </button>
            </div>
            {/* ④ 弹窗公告内容（Markdown 编辑器 + 图片上传） */}
            <div className="flex flex-col gap-1.5">
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium text-foreground">{t("admin.popupContent")}</label>
                <label className={cn("flex cursor-pointer items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-accent hover:text-foreground transition-colors", popupUploading && "pointer-events-none opacity-50")}>
                  {popupUploading ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <ImagePlus className="h-3.5 w-3.5" />}
                  插入图片
                  <input
                    type="file"
                    accept={ALLOWED_IMAGE_ACCEPT}
                    className="hidden"
                    onChange={async (e) => {
                      const file = e.target.files?.[0]
                      if (!file) return
                      const err = validateImageFile(file)
                      if (err) { toast.error(err); e.target.value = ""; return }
                      setPopupUploading(true)
                      try {
                        const result = await adminProductApi.uploadImage(file)
                        const textarea = popupTextareaRef.current
                        const mdImage = `![${file.name}](${result.url})`
                        if (textarea) {
                          const start = textarea.selectionStart
                          const end = textarea.selectionEnd
                          const text = getValue("popup_content")
                          const before = text.substring(0, start)
                          const after = text.substring(end)
                          const newText = before + (before.length > 0 && !before.endsWith("\n") ? "\n" : "") + mdImage + "\n" + after
                          setValue("popup_content", newText)
                          requestAnimationFrame(() => {
                            const newPos = before.length + (before.length > 0 && !before.endsWith("\n") ? 1 : 0) + mdImage.length + 1
                            textarea.selectionStart = textarea.selectionEnd = newPos
                            textarea.focus()
                          })
                        } else {
                          const cur = getValue("popup_content")
                          setValue("popup_content", cur + (cur ? "\n" : "") + mdImage + "\n")
                        }
                        toast.success("图片已插入")
                      } catch (err: unknown) {
                        toast.error(err instanceof Error ? err.message : "上传失败")
                      } finally {
                        setPopupUploading(false)
                        e.target.value = ""
                      }
                    }}
                  />
                </label>
              </div>
              <textarea
                ref={popupTextareaRef}
                className="min-h-32 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground font-mono focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder={"支持 Markdown 格式编辑\n# 标题  ## 二级标题  ### 三级标题\n**粗体**  *斜体*  空一行为段落换行\n![图片描述](图片URL) — 可点击上方「插入图片」自动生成"}
                value={getValue("popup_content")}
                onChange={(e) => setValue("popup_content", e.target.value)}
              />
            </div>
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}

      {/* Points Setting */}
      {tab === "points" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex flex-col gap-5 max-w-xl">
            <div className="flex items-center justify-between">
              <label className="text-sm font-medium text-foreground">{t("admin.enablePointsSystem")}</label>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 rounded-full transition-colors",
                  getBool("points_enabled") ? "bg-primary" : "bg-muted"
                )}
                onClick={() => toggleBool("points_enabled")}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  getBool("points_enabled") && "translate-x-5"
                )} />
              </button>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.pointsRate")}</label>
              <input
                type="number"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("points_rate")}
                onChange={(e) => setValue("points_rate", e.target.value)}
              />
            </div>
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}

      {/* Contact */}
      {tab === "contact" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex flex-col gap-5 max-w-xl">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.contactEmail")}</label>
              <input
                type="email"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("contact_email")}
                onChange={(e) => setValue("contact_email", e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.contactTelegram")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("contact_telegram")}
                onChange={(e) => setValue("contact_telegram", e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.contactTelegramGroup")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="https://t.me/..."
                value={getValue("contact_telegram_group")}
                onChange={(e) => setValue("contact_telegram_group", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">{t("admin.contactTelegramGroupHint")}</p>
            </div>
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}

      {/* Email Settings */}
      {tab === "email" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex flex-col gap-5 max-w-xl">
            <div className="rounded-lg border border-blue-500/30 bg-blue-500/5 p-4">
              <div className="flex items-start gap-3">
                <Mail className="mt-0.5 h-5 w-5 shrink-0 text-blue-500" />
                <div>
                  <p className="text-sm font-medium text-foreground">邮箱发件（SMTP）配置</p>
                  <p className="mt-1 text-xs text-muted-foreground leading-relaxed">
                    买家下单支付成功并自动发货后，系统会把<b>订单信息 + 卡密</b>发送到买家填写的邮箱。
                    常见服务商：QQ 邮箱 <code className="rounded bg-muted px-1">smtp.qq.com</code>（465/587）、
                    163 <code className="rounded bg-muted px-1">smtp.163.com</code>（465/587）、
                    阿里云企业邮箱 <code className="rounded bg-muted px-1">smtp.qiye.aliyun.com</code>（465）等。
                    <b>密码请填邮箱的 SMTP 授权码</b>（登录密码无法用于 SMTP）。
                  </p>
                </div>
              </div>
            </div>

            {/* 启用开关 */}
            <div className="flex items-center justify-between">
              <div>
                <label className="text-sm font-medium text-foreground">启用邮件自动发货通知</label>
                <p className="text-xs text-muted-foreground">关闭后买家不会收到发货邮件（卡密仍可在订单查询页查看）</p>
              </div>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 rounded-full transition-colors",
                  (configMap.mail_enabled === undefined ? true : getBool("mail_enabled")) ? "bg-primary" : "bg-muted"
                )}
                onClick={() => toggleBool("mail_enabled")}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  (configMap.mail_enabled === undefined ? true : getBool("mail_enabled")) && "translate-x-5"
                )} />
              </button>
            </div>

            {/* SMTP 服务器 + 端口 */}
            <div className="flex gap-3">
              <div className="flex flex-1 flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">SMTP 服务器</label>
                <input
                  type="text"
                  className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder="smtp.qq.com"
                  value={getValue("smtp_host")}
                  onChange={(e) => setValue("smtp_host", e.target.value)}
                />
              </div>
              <div className="flex w-28 flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">端口</label>
                <input
                  type="number"
                  className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder="465"
                  value={getValue("smtp_port")}
                  onChange={(e) => setValue("smtp_port", e.target.value)}
                />
              </div>
            </div>

            {/* 账号 */}
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">SMTP 账号</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="noreply@qq.com"
                value={getValue("smtp_username")}
                onChange={(e) => setValue("smtp_username", e.target.value)}
              />
            </div>

            {/* 授权码 */}
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">SMTP 授权码 / 密码</label>
              <input
                type="password"
                autoComplete="new-password"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder={getValue("smtp_password") === "__SET__" ? "已设置，留空则不修改" : "邮箱的 SMTP 授权码"}
                value={getValue("smtp_password") === "__SET__" ? "" : getValue("smtp_password")}
                onChange={(e) => setValue("smtp_password", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">出于安全考虑不显示已保存的授权码；留空表示不修改</p>
            </div>

            {/* 发件人信息 */}
            <div className="flex flex-col gap-5 border-t border-border pt-5">
              <p className="text-sm font-medium text-foreground">发件人信息</p>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">发件人邮箱</label>
                <input
                  type="email"
                  className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder="noreply@qq.com"
                  value={getValue("mail_from")}
                  onChange={(e) => setValue("mail_from", e.target.value)}
                />
                <p className="text-xs text-muted-foreground">留空则使用 SMTP 账号作为发件人</p>
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">发件人名称</label>
                <input
                  type="text"
                  className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder="Nova key"
                  value={getValue("mail_from_name")}
                  onChange={(e) => setValue("mail_from_name", e.target.value)}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">站点地址（邮件中订单详情链接使用）</label>
                <input
                  type="url"
                  className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder="https://noepay.cn"
                  value={getValue("mail_site_url")}
                  onChange={(e) => setValue("mail_site_url", e.target.value)}
                />
              </div>
            </div>

            {/* 测试邮件 */}
            <div className="rounded-lg border border-border p-4">
              <p className="text-sm font-medium text-foreground">发送测试邮件</p>
              <p className="mt-1 text-xs text-muted-foreground">填一个收件邮箱，使用以上配置立即发送测试邮件，验证配置是否正确</p>
              <div className="mt-3 flex gap-2">
                <input
                  type="email"
                  className="h-10 flex-1 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder="收件邮箱，如 you@example.com"
                  value={testEmailTo}
                  onChange={(e) => setTestEmailTo(e.target.value)}
                />
                <button
                  type="button"
                  className="flex h-10 shrink-0 items-center gap-1.5 rounded-lg bg-primary px-4 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                  onClick={handleTestEmail}
                  disabled={testSending}
                >
                  {testSending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                  {testSending ? "发送中..." : "发送测试邮件"}
                </button>
              </div>
            </div>

            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}

      {/* Maintenance */}
      {tab === "maintenance" && (
        <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
          <div className="flex flex-col gap-5 max-w-xl">
            <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-4">
              <div className="flex items-start gap-3">
                <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-500" />
                <div>
                  <p className="text-sm font-medium text-foreground">{t("admin.maintenanceWarning")}</p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {t("admin.maintenanceWarningDesc")}
                  </p>
                </div>
              </div>
            </div>
            <div className="flex items-center justify-between">
              <div>
                <label className="text-sm font-medium text-foreground">{t("admin.maintenanceLabel")}</label>
                <p className="text-xs text-muted-foreground">{t("admin.maintenanceLabelDesc")}</p>
              </div>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 rounded-full transition-colors",
                  getBool("maintenance_enabled") ? "bg-red-500" : "bg-muted"
                )}
                onClick={handleToggleMaintenance}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  getBool("maintenance_enabled") && "translate-x-5"
                )} />
              </button>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.maintenanceMessage")}</label>
              <textarea
                className="min-h-20 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={getValue("maintenance_message")}
                onChange={(e) => setValue("maintenance_message", e.target.value)}
              />
            </div>
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSave}
              disabled={saving}
            >
              <Save className="h-4 w-4" />
              {saving ? t("admin.saving") : t("admin.saveSettings")}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

"use client"

import { useState, useEffect, useCallback, useRef } from "react"
import { Save, AlertTriangle, Upload, Loader2, ImagePlus, Mail, Send, Bell, Webhook, CheckCircle2, XCircle, Plus, ChevronLeft, ChevronRight, ChevronDown } from "lucide-react"
import { cn } from "@/lib/utils"
import { toast } from "sonner"
import { adminConfigApi, adminProductApi, adminNotificationApi, adminWechatMpApi, withMockFallback } from "@/services/api"
import { mockSiteConfigKVs } from "@/lib/mock-data"
import { useLocale } from "@/lib/context"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose } from "@/components/ui/dialog"
import type { SiteConfigKV, NotificationTemplateItem, NotificationChannelItem, NotificationTestResult } from "@/types"

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

type TabKey = "basic" | "announcement" | "points" | "contact" | "email" | "maintenance" | "notify" | "wechatMp"

export default function AdminSiteConfigPage() {
  const { t } = useLocale()
  const [tab, setTab] = useState<TabKey>("basic")
  const [configMap, setConfigMap] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [logoUploading, setLogoUploading] = useState(false)
  const [popupUploading, setPopupUploading] = useState(false)
  const [wechatUploading, setWechatUploading] = useState(false)
  const [testEmailTo, setTestEmailTo] = useState("")
  const [testSending, setTestSending] = useState(false)
  const popupTextareaRef = useRef<HTMLTextAreaElement>(null)

  // 消息通知（notify tab）独立数据
  const NOTIFY_PAGE_SIZE = 10
  const [notifyChannels, setNotifyChannels] = useState<NotificationChannelItem[]>([])
  const [notifyTemplates, setNotifyTemplates] = useState<NotificationTemplateItem[]>([])
  const [notifyLoading, setNotifyLoading] = useState(false)
  const [notifySaved, setNotifySaved] = useState(false)
  const [notifyTestResult, setNotifyTestResult] = useState<NotificationTestResult | null>(null)
  const [notifyTesting, setNotifyTesting] = useState(false)
  // 模板分页 / 筛选
  const [notifyPage, setNotifyPage] = useState(1)
  const [notifyTemplateTotal, setNotifyTemplateTotal] = useState(0)
  const [notifyCategoryFilter, setNotifyCategoryFilter] = useState("")
  const [notifyEnabledFilter, setNotifyEnabledFilter] = useState("")
  // 新增模板弹窗
  const [notifyCreateOpen, setNotifyCreateOpen] = useState(false)
  const [notifyCreating, setNotifyCreating] = useState(false)
  const [createForm, setCreateForm] = useState({
    code: "",
    name: "",
    category: "USER",
    title: "",
    content: "",
    channels: "DINGTALK,WECOM,EMAIL",
    enabled: false,
    auto_trigger: false,
  })

  const loadNotifyTemplates = useCallback(async (page: number, category: string, enabledFilter: string) => {
    setNotifyLoading(true)
    try {
      const data = await adminNotificationApi.getTemplates({
        page,
        page_size: NOTIFY_PAGE_SIZE,
        category: category || undefined,
        enabled: enabledFilter === "enabled" ? true : enabledFilter === "disabled" ? false : undefined,
      })
      setNotifyTemplates(data.list)
      setNotifyTemplateTotal(data.pagination.total)
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "通知模板加载失败")
    } finally {
      setNotifyLoading(false)
    }
  }, [])

  // 首次切换到「消息通知」tab 时加载渠道，模板列表按分页/筛选加载
  const notifyLoadedRef = useRef(false)
  useEffect(() => {
    if (tab !== "notify") return
    if (!notifyLoadedRef.current) {
      notifyLoadedRef.current = true
      adminNotificationApi.getChannels().then(setNotifyChannels).catch((err: unknown) => {
        toast.error(err instanceof Error ? err.message : "通知渠道加载失败")
      })
    }
    loadNotifyTemplates(notifyPage, notifyCategoryFilter, notifyEnabledFilter)
  }, [tab, notifyPage, notifyCategoryFilter, notifyEnabledFilter, loadNotifyTemplates])

  // ─── 公众号配置（wechatMp tab）数据 ───
  const [mpConfig, setMpConfig] = useState({ appid: "", appsecret: "", follow_qr: "", template_id: "", configured: false })
  const [mpSaving, setMpSaving] = useState(false)
  const [mpTesting, setMpTesting] = useState(false)
  const [mpFollowUploading, setMpFollowUploading] = useState(false)
  const [mpTestResult, setMpTestResult] = useState<{ passed: boolean; items: { name: string; status: string; message: string }[] } | null>(null)
  const mpLoadedRef = useRef(false)

  useEffect(() => {
    if (tab !== "wechatMp") return
    if (!mpLoadedRef.current) {
      mpLoadedRef.current = true
      adminWechatMpApi.getConfig()
        .then((cfg: any) => setMpConfig({
          appid: cfg?.appid || "",
          appsecret: cfg?.appsecret || "",
          follow_qr: cfg?.follow_qr || "",
          template_id: cfg?.template_id || "",
          configured: !!cfg?.configured,
        }))
        .catch((err: unknown) => toast.error(err instanceof Error ? err.message : "公众号配置加载失败"))
    }
  }, [tab])

  const handleSaveMp = async () => {
    setMpSaving(true)
    try {
      await adminWechatMpApi.updateConfig({
        appid: mpConfig.appid.trim(),
        appsecret: mpConfig.appsecret.trim(),
        follow_qr: mpConfig.follow_qr.trim(),
        template_id: mpConfig.template_id.trim(),
      })
      setMpTestResult(null)
      toast.success("公众号配置已保存")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setMpSaving(false)
    }
  }

  const handleTestMp = async () => {
    setMpTesting(true)
    setMpTestResult(null)
    try {
      const result = await adminWechatMpApi.test()
      setMpTestResult(result)
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "测试失败")
    } finally {
      setMpTesting(false)
    }
  }

  const handleMpFollowUpload = async (file: File) => {
    const err = validateImageFile(file)
    if (err) { toast.error(err); return }
    setMpFollowUploading(true)
    try {
      const result = await adminProductApi.uploadImage(file)
      setMpConfig((c) => ({ ...c, follow_qr: result.url }))
      toast.success("上传成功")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "上传失败")
    } finally {
      setMpFollowUploading(false)
    }
  }

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
      // SMTP 密码：留空或 __SET__ 表示不修改，剔除后不提交（后端也跳过空值）；system_version 为只读展示，不提交
      const configs = Object.entries(configMap)
        .filter(([key, value]) => key !== "system_version" && !(key === "smtp_password" && (value === "" || value === "__SET__")))
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
        .filter(([key, value]) => key !== "system_version" && !(key === "smtp_password" && (value === "" || value === "__SET__")))
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

  // ─── 消息通知（notify tab）操作 ───

  const updateChannel = (type: string, patch: Partial<NotificationChannelItem>) => {
    setNotifyChannels(prev => prev.map(c => (c.channel_type === type ? { ...c, ...patch } : c)))
  }
  const toggleChannelEnabled = (type: string) => {
    const ch = notifyChannels.find(c => c.channel_type === type)
    if (ch) updateChannel(type, { enabled: !ch.enabled })
  }
  const updateTemplate = (id: string, patch: Partial<NotificationTemplateItem>) => {
    setNotifyTemplates(prev => prev.map(t => (t.id === id ? { ...t, ...patch } : t)))
  }
  const toggleTemplateEnabled = (id: string) => {
    const t = notifyTemplates.find(x => x.id === id)
    if (t) updateTemplate(id, { enabled: !t.enabled })
  }
  const toggleTemplateChannel = (id: string, chType: string) => {
    const t = notifyTemplates.find(x => x.id === id)
    if (!t) return
    const cur = (t.channels || "").split(",").map(s => s.trim()).filter(Boolean)
    const next = cur.includes(chType) ? cur.filter(c => c !== chType) : [...cur, chType]
    updateTemplate(id, { channels: next.join(",") })
  }

  const handleSaveNotify = async () => {
    setNotifySaved(true)
    try {
      for (const ch of notifyChannels) {
        await adminNotificationApi.saveChannel(ch.channel_type, {
          name: ch.name,
          enabled: ch.enabled,
          ...(ch.channel_type === "EMAIL"
            ? { email_to: ch.email_to ?? "" }
            : {
                webhook_url: ch.webhook_url ?? "",
                ...(ch.channel_type === "DINGTALK" ? { secret: ch.secret ?? "" } : {}),
              }),
        })
      }
      for (const t of notifyTemplates) {
        await adminNotificationApi.updateTemplate(t.id, {
          enabled: t.enabled,
          channels: t.channels,
          title: t.title,
          content: t.content,
        })
      }
      toast.success("通知配置已保存")
      loadNotifyTemplates(notifyPage, notifyCategoryFilter, notifyEnabledFilter)
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setNotifySaved(false)
    }
  }

  const handleTestNotify = async (code: string) => {
    setNotifyTesting(true)
    setNotifyTestResult(null)
    try {
      const result = await adminNotificationApi.testSend(code)
      setNotifyTestResult(result)
      if (result.passed) {
        toast.success("通知测试发送通过")
      } else {
        toast.error("部分渠道发送失败，请根据 ❌ 项检查配置")
      }
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "测试发送失败")
    } finally {
      setNotifyTesting(false)
    }
  }

  // ─── 新增模板弹窗 ───

  const openCreateDialog = () => {
    setCreateForm({
      code: "",
      name: "",
      category: "USER",
      title: "",
      content: "",
      channels: "DINGTALK,WECOM,EMAIL",
      enabled: false,
      auto_trigger: false,
    })
    setNotifyCreateOpen(true)
  }

  const toggleCreateChannel = (chType: string) => {
    setCreateForm(prev => {
      const cur = (prev.channels || "").split(",").map(s => s.trim()).filter(Boolean)
      const next = cur.includes(chType) ? cur.filter(c => c !== chType) : [...cur, chType]
      return { ...prev, channels: next.join(",") }
    })
  }

  const handleCreateTemplate = async () => {
    if (!createForm.code.trim() || !createForm.name.trim()) {
      toast.error("模板编码与名称不能为空")
      return
    }
    setNotifyCreating(true)
    try {
      await adminNotificationApi.createTemplate({
        code: createForm.code,
        name: createForm.name,
        category: createForm.category,
        title: createForm.title,
        content: createForm.content,
        channels: createForm.channels,
        enabled: createForm.enabled,
        auto_trigger: createForm.auto_trigger,
      })
      toast.success("模板创建成功")
      setNotifyCreateOpen(false)
      setNotifyTestResult(null)
      if (notifyPage !== 1) {
        setNotifyPage(1)
      } else {
        loadNotifyTemplates(1, notifyCategoryFilter, notifyEnabledFilter)
      }
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "创建失败")
    } finally {
      setNotifyCreating(false)
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
          { key: "notify" as const, label: "消息通知" },
          { key: "wechatMp" as const, label: "公众号配置" },
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
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.copyright")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="© 2026 Nova key 版权所有"
                value={getValue("copyright")}
                onChange={(e) => setValue("copyright", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">{t("admin.copyrightHint")}</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.icpNumber")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="粤ICP备XXXXXXXX号"
                value={getValue("icp_number")}
                onChange={(e) => setValue("icp_number", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">{t("admin.icpNumberHint")}</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.policeNumber")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="粤公网安备440XXXXXXXXXXXX号"
                value={getValue("police_number")}
                onChange={(e) => setValue("police_number", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">{t("admin.policeNumberHint")}</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.systemVersion")}</label>
              <div className="flex h-10 items-center rounded-lg border border-input bg-muted/40 px-3 text-sm text-foreground">
                {getValue("system_version") || "1.0.0"}
              </div>
              <p className="text-xs text-muted-foreground">{t("admin.systemVersionHint")}</p>
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
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.contactPhone")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="400-888-8888"
                value={getValue("contact_phone")}
                onChange={(e) => setValue("contact_phone", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">显示在首页底部「联系我们」弹窗中，留空则不显示</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.contactAddress")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="广东省深圳市南山区科技园"
                value={getValue("contact_address")}
                onChange={(e) => setValue("contact_address", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">显示在首页底部「联系我们」弹窗中，留空则不显示</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.wechatKefuLink")}</label>
              <input
                type="text"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                placeholder="https://work.weixin.qq.com/kfid/... 或 https://wx.qq.com/..."
                value={getValue("wechat_kefu_link")}
                onChange={(e) => setValue("wechat_kefu_link", e.target.value)}
              />
              <p className="text-xs text-muted-foreground">{t("admin.wechatKefuLinkHint")}</p>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">{t("admin.wechatQrcode")}</label>
              <div className="flex gap-2">
                <input
                  type="text"
                  className="h-10 flex-1 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  placeholder="https://..."
                  value={getValue("wechat_qrcode")}
                  onChange={(e) => setValue("wechat_qrcode", e.target.value)}
                />
                <label className={cn("flex h-10 shrink-0 cursor-pointer items-center gap-1.5 rounded-lg border border-input bg-background px-3 text-sm font-medium text-foreground hover:bg-accent transition-colors", wechatUploading && "pointer-events-none opacity-50")}>
                  {wechatUploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
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
                      setWechatUploading(true)
                      try {
                        const result = await adminProductApi.uploadImage(file)
                        setValue("wechat_qrcode", result.url)
                        toast.success("上传成功")
                      } catch (err: unknown) {
                        toast.error(err instanceof Error ? err.message : "上传失败")
                      } finally {
                        setWechatUploading(false)
                        e.target.value = ""
                      }
                    }}
                  />
                </label>
              </div>
              <p className="text-xs text-muted-foreground">{t("admin.wechatQrcodeHint")}</p>
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

      {/* 消息通知 */}
      {tab === "notify" && (
        <div className="flex flex-col gap-6">
          {/* ① 渠道配置 */}
          <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
            <div className="flex items-center gap-2">
              <Webhook className="h-5 w-5 text-primary" />
              <h2 className="text-base font-semibold text-foreground">通知渠道配置</h2>
            </div>
            <p className="mt-1 text-xs text-muted-foreground leading-relaxed">
              配置第三方通知渠道与管理员通知邮箱。渠道启用后，模板中勾选了该渠道才会向外发送；渠道未启用则一律不发送。
            </p>
            {notifyLoading ? (
              <div className="mt-4 flex h-24 items-center justify-center">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              </div>
            ) : (
              <div className="mt-4 flex flex-col gap-4">
                {notifyChannels.map((ch) => (
                  <div key={ch.channel_type} className="rounded-lg border border-border p-4">
                    <div className="flex items-center justify-between gap-3">
                      <div className="flex items-center gap-2">
                        <span className="text-sm font-medium text-foreground">{ch.name}</span>
                        <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">{ch.channel_type}</span>
                      </div>
                      <button
                        type="button"
                        className={cn(
                          "relative h-6 w-11 shrink-0 rounded-full transition-colors",
                          ch.enabled ? "bg-primary" : "bg-muted"
                        )}
                        onClick={() => toggleChannelEnabled(ch.channel_type)}
                      >
                        <span className={cn(
                          "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                          ch.enabled && "translate-x-5"
                        )} />
                      </button>
                    </div>
                    {ch.channel_type === "EMAIL" ? (
                      <div className="mt-3 flex flex-col gap-1.5">
                        <label className="text-xs font-medium text-muted-foreground">通知接收邮箱（多个用英文逗号分隔）</label>
                        <input
                          type="text"
                          placeholder="admin@example.com,ops@example.com"
                          className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                          value={ch.email_to ?? ""}
                          onChange={(e) => updateChannel(ch.channel_type, { email_to: e.target.value })}
                        />
                        <p className="text-xs text-muted-foreground">邮件将复用「邮箱设置」tab 中的 SMTP 发件配置</p>
                      </div>
                    ) : (
                      <div className="mt-3 flex flex-col gap-1.5">
                        <label className="text-xs font-medium text-muted-foreground">机器人 Webhook 地址</label>
                        <input
                          type="text"
                          placeholder={ch.channel_type === "DINGTALK"
                            ? "https://oapi.dingtalk.com/robot/send?access_token=..."
                            : "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=..."}
                          className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                          value={ch.webhook_url ?? ""}
                          onChange={(e) => updateChannel(ch.channel_type, { webhook_url: e.target.value })}
                        />
                        <p className="text-xs text-muted-foreground">
                          {ch.channel_type === "DINGTALK"
                            ? "钉钉群 → 群机器人 → 添加机器人，复制 Webhook 地址"
                            : "企业微信群 → 群机器人 → 添加机器人，复制 Webhook 地址"}
                        </p>
                        {ch.channel_type === "DINGTALK" && (
                          <>
                            <label className="mt-2 text-xs font-medium text-muted-foreground">加签密钥（Secret）</label>
                            <input
                              type="password"
                              autoComplete="new-password"
                              placeholder="钉钉机器人安全设置选择「加签」时，填写 SEC 密钥"
                              className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                              value={ch.secret ?? ""}
                              onChange={(e) => updateChannel(ch.channel_type, { secret: e.target.value })}
                            />
                            <p className="text-xs text-muted-foreground">
                              安全设置选「加签」则必须填写密钥，发送时会自动计算签名；选「自定义关键词」或「IP 白名单」可留空
                            </p>
                          </>
                        )}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* ② 模板列表 */}
          <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <Bell className="h-5 w-5 text-primary" />
                <h2 className="text-base font-semibold text-foreground">消息通知模板</h2>
                <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">共 {notifyTemplateTotal} 个</span>
              </div>
              <button
                type="button"
                className="flex items-center gap-1.5 rounded-lg bg-primary px-3 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors"
                onClick={openCreateDialog}
              >
                <Plus className="h-4 w-4" />
                新增模板
              </button>
            </div>
            <p className="mt-1 text-xs text-muted-foreground leading-relaxed">
              勾选「启用」后，对应事件发生时将按勾选的渠道通知管理员；不启用则不通知（系统消息铃铛始终记录，可在总览右上角查看）。
              「自动触发」模板由系统按周期/条件自动发送，无需业务事件。
            </p>

            {/* 筛选 */}
            <div className="mt-4 flex flex-wrap items-center gap-3">
              <div className="relative">
                <select
                  className="h-9 appearance-none rounded-lg border border-input bg-background pl-3 pr-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  value={notifyCategoryFilter}
                  onChange={(e) => { setNotifyCategoryFilter(e.target.value); setNotifyPage(1) }}
                >
                  <option value="">全部分类</option>
                  <option value="USER">用户</option>
                  <option value="ORDER">订单</option>
                  <option value="DISTRIBUTION">分销</option>
                  <option value="SYSTEM">系统</option>
                  <option value="REPORT">报表</option>
                </select>
                <ChevronDown className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              </div>
              <div className="relative">
                <select
                  className="h-9 appearance-none rounded-lg border border-input bg-background pl-3 pr-8 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  value={notifyEnabledFilter}
                  onChange={(e) => { setNotifyEnabledFilter(e.target.value); setNotifyPage(1) }}
                >
                  <option value="">全部状态</option>
                  <option value="enabled">已启用</option>
                  <option value="disabled">未启用</option>
                </select>
                <ChevronDown className="pointer-events-none absolute right-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              </div>
            </div>

            {notifyLoading ? (
              <div className="mt-4 flex h-24 items-center justify-center">
                <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
              </div>
            ) : notifyTemplates.length === 0 ? (
              <div className="mt-4 rounded-lg border border-dashed border-border py-10 text-center text-sm text-muted-foreground">
                暂无符合条件的模板
              </div>
            ) : (
              <div className="mt-4 flex flex-col gap-3">
                {notifyTemplates.map((t) => (
                  <div key={t.id} className={cn("rounded-lg border p-4 transition-colors", t.enabled ? "border-primary/40 bg-primary/5" : "border-border")}>
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-sm font-medium text-foreground">{t.name}</span>
                          <span className={cn(
                            "rounded px-1.5 py-0.5 text-xs font-medium",
                            t.category === "ORDER" && "bg-emerald-500/10 text-emerald-600",
                            t.category === "USER" && "bg-blue-500/10 text-blue-600",
                            t.category === "SYSTEM" && "bg-amber-500/10 text-amber-600",
                            t.category === "REPORT" && "bg-purple-500/10 text-purple-600",
                            !["ORDER", "USER", "SYSTEM", "REPORT"].includes(t.category) && "bg-muted text-muted-foreground"
                          )}>
                            {t.category === "ORDER" ? "订单" : t.category === "USER" ? "用户" : t.category === "SYSTEM" ? "系统" : t.category === "REPORT" ? "报表" : t.category}
                          </span>
                          {t.auto_trigger && (
                            <span className="rounded bg-amber-500/10 px-1.5 py-0.5 text-xs font-medium text-amber-600">自动触发</span>
                          )}
                          <span className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs text-muted-foreground">{t.code}</span>
                        </div>
                        <p className="mt-1 text-xs leading-relaxed text-muted-foreground line-clamp-2">{t.content}</p>
                      </div>
                      <div className="flex shrink-0 flex-col items-end gap-2">
                        <button
                          type="button"
                          className={cn(
                            "relative h-6 w-11 rounded-full transition-colors",
                            t.enabled ? "bg-primary" : "bg-muted"
                          )}
                          onClick={() => toggleTemplateEnabled(t.id)}
                          title={t.enabled ? "已启用，点击关闭" : "已停用，点击启用"}
                        >
                          <span className={cn(
                            "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                            t.enabled && "translate-x-5"
                          )} />
                        </button>
                        <button
                          type="button"
                          className="flex h-7 items-center gap-1 rounded-md border border-input bg-background px-2.5 text-xs font-medium text-foreground hover:bg-accent transition-colors disabled:opacity-50"
                          onClick={() => handleTestNotify(t.code)}
                          disabled={notifyTesting}
                        >
                          {notifyTesting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Send className="h-3.5 w-3.5" />}
                          测试发送
                        </button>
                      </div>
                    </div>
                    <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-2 border-t border-border pt-3">
                      <span className="text-xs text-muted-foreground">发送渠道：</span>
                      {notifyChannels.map((ch) => {
                        const checked = (t.channels || "").split(",").map(s => s.trim()).filter(Boolean).includes(ch.channel_type)
                        return (
                          <label key={ch.channel_type} className="flex cursor-pointer items-center gap-1.5 text-xs text-foreground">
                            <input
                              type="checkbox"
                              className="h-3.5 w-3.5 rounded border-input accent-primary"
                              checked={checked}
                              onChange={() => toggleTemplateChannel(t.id, ch.channel_type)}
                            />
                            {ch.name}
                          </label>
                        )
                      })}
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* 分页 */}
            {Math.ceil(notifyTemplateTotal / NOTIFY_PAGE_SIZE) > 1 && (
              <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-3">
                <span className="text-sm text-muted-foreground">
                  共 {notifyTemplateTotal} 条，每页 {NOTIFY_PAGE_SIZE} 条
                </span>
                <div className="flex items-center gap-1">
                  <button
                    type="button"
                    onClick={() => setNotifyPage(p => Math.max(1, p - 1))}
                    disabled={notifyPage === 1}
                    className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
                  >
                    <ChevronLeft className="h-4 w-4" />
                  </button>
                  {Array.from({ length: Math.ceil(notifyTemplateTotal / NOTIFY_PAGE_SIZE) }, (_, i) => i + 1)
                    .filter(page => page === 1 || page === Math.ceil(notifyTemplateTotal / NOTIFY_PAGE_SIZE) || Math.abs(page - notifyPage) <= 1)
                    .map((page, index, array) => (
                      <div key={page} className="flex items-center gap-1">
                        {index > 0 && array[index - 1] !== page - 1 && (
                          <span className="px-1 text-muted-foreground">...</span>
                        )}
                        <button
                          type="button"
                          onClick={() => setNotifyPage(page)}
                          className={cn(
                            "flex h-8 w-8 items-center justify-center rounded-md text-sm font-medium",
                            notifyPage === page
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
                    onClick={() => setNotifyPage(p => Math.min(Math.ceil(notifyTemplateTotal / NOTIFY_PAGE_SIZE), p + 1))}
                    disabled={notifyPage === Math.ceil(notifyTemplateTotal / NOTIFY_PAGE_SIZE)}
                    className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
                  >
                    <ChevronRight className="h-4 w-4" />
                  </button>
                </div>
              </div>
            )}
          </div>

          {/* ③ 测试结果 */}
          {notifyTestResult && (
            <div className={cn("rounded-xl border bg-card p-6 shadow-sm", notifyTestResult.passed ? "border-emerald-500/40" : "border-red-500/40")}>
              <div className="flex items-center gap-2">
                {notifyTestResult.passed
                  ? <CheckCircle2 className="h-5 w-5 text-emerald-500" />
                  : <XCircle className="h-5 w-5 text-red-500" />}
                <h2 className="text-base font-semibold text-foreground">
                  测试结果：{notifyTestResult.template_name}
                  {notifyTestResult.passed ? "（通过）" : "（未通过）"}
                </h2>
              </div>
              <p className={cn("mt-1 text-xs", notifyTestResult.passed ? "text-emerald-600" : "text-red-500")}>
                {notifyTestResult.message}
              </p>
              <div className="mt-4 flex flex-col gap-2">
                {notifyTestResult.items.map((item, idx) => (
                  <div key={idx} className="flex items-center gap-2 rounded-lg border border-border px-3 py-2">
                    {item.status
                      ? <CheckCircle2 className="h-4 w-4 shrink-0 text-emerald-500" />
                      : <XCircle className="h-4 w-4 shrink-0 text-red-500" />}
                    <span className="text-sm font-medium text-foreground">{item.name}</span>
                    <span className={cn("text-xs", item.status ? "text-emerald-600" : "text-red-500")}>{item.message}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* ④ 保存 */}
          <div className="flex items-center gap-3">
            <button
              type="button"
              className="flex w-fit items-center gap-2 rounded-lg bg-primary px-4 py-2.5 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleSaveNotify}
              disabled={notifySaved}
            >
              <Save className="h-4 w-4" />
              {notifySaved ? "保存中..." : "保存通知配置"}
            </button>
            {notifySaved && <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />}
          </div>
        </div>
      )}

      {/* 公众号配置 */}
      {tab === "wechatMp" && (
        <div className="flex flex-col gap-4">
          <div className="rounded-xl border border-border bg-card p-6 shadow-sm">
            <div className="flex items-center gap-2">
              <Bell className="h-5 w-5 text-primary" />
              <h2 className="text-base font-semibold text-foreground">微信公众号（服务号）配置</h2>
            </div>
            <p className="mt-1 text-sm text-muted-foreground">
              绑定微信服务号后，分销员可在「分销中心 → 提现记录」扫码绑定微信（收款到零钱），并可上传公众号二维码引导客户关注。
            </p>
            <div className="mt-5 flex flex-col gap-4 max-w-xl">
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">公众号 AppID</label>
                <input
                  type="text"
                  placeholder="wx 开头的一串字符（公众号后台 → 设置与开发 → 基本配置）"
                  className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  value={mpConfig.appid}
                  onChange={(e) => setMpConfig((c) => ({ ...c, appid: e.target.value }))}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">公众号 AppSecret</label>
                <input
                  type="password"
                  placeholder="AppSecret（注意不要带空格或换行）"
                  className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  value={mpConfig.appsecret}
                  onChange={(e) => setMpConfig((c) => ({ ...c, appsecret: e.target.value }))}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">公众号消息模板 ID（可选）</label>
                <input
                  type="text"
                  placeholder="在微信公众平台申请「模板消息」后填写模板 ID（如 R34xxxxx）"
                  className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                  value={mpConfig.template_id}
                  onChange={(e) => setMpConfig((c) => ({ ...c, template_id: e.target.value }))}
                />
                <p className="text-xs text-muted-foreground">申请模板消息需满足微信公众平台条件，配置后可用于向用户发送通知。</p>
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-medium text-foreground">公众号关注二维码</label>
                <div className="flex items-start gap-4">
                  {mpConfig.follow_qr ? (
                    <img src={mpConfig.follow_qr} alt="公众号二维码" className="h-28 w-28 rounded-lg border border-border object-cover" />
                  ) : (
                    <div className="flex h-28 w-28 items-center justify-center rounded-lg border border-dashed border-input bg-muted/40">
                      <ImagePlus className="h-6 w-6 text-muted-foreground" />
                    </div>
                  )}
                  <div className="flex flex-col gap-2">
                    <label className="inline-flex h-9 cursor-pointer items-center gap-1.5 rounded-lg border border-input bg-background px-3 text-sm font-medium text-foreground hover:bg-accent">
                      {mpFollowUploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
                      上传二维码
                      <input
                        type="file"
                        accept={ALLOWED_IMAGE_ACCEPT}
                        className="hidden"
                        onChange={async (e) => {
                          const file = e.target.files?.[0]
                          e.target.value = ""
                          if (file) await handleMpFollowUpload(file)
                        }}
                      />
                    </label>
                    {mpConfig.follow_qr && (
                      <button
                        type="button"
                        onClick={() => setMpConfig((c) => ({ ...c, follow_qr: "" }))}
                        className="text-left text-xs text-red-500 hover:underline"
                      >
                        移除二维码
                      </button>
                    )}
                    <p className="text-xs text-muted-foreground">上传公众号二维码后，分销中心将展示「关注公众号」引导。</p>
                  </div>
                </div>
              </div>
              <div className="flex items-center gap-3 pt-2">
                <button
                  type="button"
                  onClick={handleSaveMp}
                  disabled={mpSaving}
                  className="inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-4 text-sm font-medium text-primary-foreground hover:brightness-110 disabled:opacity-50"
                >
                  {mpSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
                  保存公众号配置
                </button>
                <button
                  type="button"
                  onClick={handleTestMp}
                  disabled={mpTesting || mpSaving}
                  className="inline-flex h-10 items-center gap-2 rounded-lg border border-input px-4 text-sm font-medium text-foreground hover:bg-accent disabled:opacity-50"
                >
                  {mpTesting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                  测试连接
                </button>
              </div>
            </div>
          </div>

          {/* 测试结果 */}
          {mpTestResult && (
            <div className={cn("rounded-xl border bg-card p-6 shadow-sm", mpTestResult.passed ? "border-emerald-500/40" : "border-red-500/40")}>
              <div className="flex items-center gap-2">
                {mpTestResult.passed
                  ? <CheckCircle2 className="h-5 w-5 text-emerald-500" />
                  : <XCircle className="h-5 w-5 text-red-500" />}
                <h2 className="text-base font-semibold text-foreground">
                  公众号连接测试：{mpTestResult.passed ? "通过" : "未通过"}
                </h2>
              </div>
              <div className="mt-4 flex flex-col gap-2">
                {mpTestResult.items.map((item, idx) => (
                  <div key={idx} className="flex items-start gap-2 rounded-lg border border-border px-3 py-2">
                    {item.status === "PASS"
                      ? <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />
                      : item.status === "INFO"
                        ? <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
                        : <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-red-500" />}
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-foreground">{item.name}</p>
                      <p className={cn("text-xs", item.status === "PASS" ? "text-emerald-600" : item.status === "INFO" ? "text-amber-600" : "text-red-500")}>
                        {item.message}
                      </p>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* 新增模板弹窗 */}
      <Dialog open={notifyCreateOpen} onOpenChange={setNotifyCreateOpen}>
        <DialogContent className="max-h-[85vh] max-w-lg overflow-y-auto">
          <DialogHeader>
            <DialogTitle>新增通知模板</DialogTitle>
            <DialogDescription>创建自定义管理员通知模板，编码创建后不可修改。</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">模板编码 <span className="text-red-500">*</span></label>
              <input
                type="text"
                placeholder="如 CUSTOM_ALERT（自动转为大写）"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground uppercase placeholder:normal-case focus:outline-none focus:ring-2 focus:ring-ring"
                value={createForm.code}
                onChange={(e) => setCreateForm(f => ({ ...f, code: e.target.value }))}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">模板名称 <span className="text-red-500">*</span></label>
              <input
                type="text"
                placeholder="如 自定义预警通知"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={createForm.name}
                onChange={(e) => setCreateForm(f => ({ ...f, name: e.target.value }))}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">模板分类</label>
              <select
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={createForm.category}
                onChange={(e) => setCreateForm(f => ({ ...f, category: e.target.value }))}
              >
                <option value="USER">用户</option>
                <option value="ORDER">订单</option>
                <option value="DISTRIBUTION">分销</option>
                <option value="SYSTEM">系统</option>
                <option value="REPORT">报表</option>
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">标题模板</label>
              <input
                type="text"
                placeholder="支持 {site_name} 等变量，留空则使用模板名称"
                className="h-10 rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={createForm.title}
                onChange={(e) => setCreateForm(f => ({ ...f, title: e.target.value }))}
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-medium text-foreground">正文模板</label>
              <textarea
                placeholder={"支持 {site_name} / {time} 等变量，{变量} 由触发事件传入实际值替换"}
                className="min-h-24 rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                value={createForm.content}
                onChange={(e) => setCreateForm(f => ({ ...f, content: e.target.value }))}
              />
            </div>
            <div className="flex flex-col gap-2">
              <label className="text-sm font-medium text-foreground">发送渠道</label>
              <div className="flex flex-wrap gap-4">
                {notifyChannels.map((ch) => (
                  <label key={ch.channel_type} className="flex cursor-pointer items-center gap-1.5 text-sm text-foreground">
                    <input
                      type="checkbox"
                      className="h-3.5 w-3.5 rounded border-input accent-primary"
                      checked={createForm.channels.split(",").map(s => s.trim()).filter(Boolean).includes(ch.channel_type)}
                      onChange={() => toggleCreateChannel(ch.channel_type)}
                    />
                    {ch.name}
                  </label>
                ))}
              </div>
            </div>
            <div className="flex items-center justify-between rounded-lg border border-border px-4 py-3">
              <div>
                <label className="text-sm font-medium text-foreground">启用模板</label>
                <p className="text-xs text-muted-foreground">启用后按勾选渠道通知管理员，不启用仅记录系统消息</p>
              </div>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 shrink-0 rounded-full transition-colors",
                  createForm.enabled ? "bg-primary" : "bg-muted"
                )}
                onClick={() => setCreateForm(f => ({ ...f, enabled: !f.enabled }))}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  createForm.enabled && "translate-x-5"
                )} />
              </button>
            </div>
            <div className="flex items-center justify-between rounded-lg border border-border px-4 py-3">
              <div>
                <label className="text-sm font-medium text-foreground">自动触发</label>
                <p className="text-xs text-muted-foreground">由系统按周期/条件自动发送，无需业务事件触发</p>
              </div>
              <button
                type="button"
                className={cn(
                  "relative h-6 w-11 shrink-0 rounded-full transition-colors",
                  createForm.auto_trigger ? "bg-primary" : "bg-muted"
                )}
                onClick={() => setCreateForm(f => ({ ...f, auto_trigger: !f.auto_trigger }))}
              >
                <span className={cn(
                  "absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform",
                  createForm.auto_trigger && "translate-x-5"
                )} />
              </button>
            </div>
          </div>
          <DialogFooter>
            <DialogClose asChild>
              <button
                type="button"
                className="rounded-lg border border-input bg-background px-4 py-2 text-sm font-medium text-foreground hover:bg-accent transition-colors"
              >
                取消
              </button>
            </DialogClose>
            <button
              type="button"
              className="flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
              onClick={handleCreateTemplate}
              disabled={notifyCreating}
            >
              {notifyCreating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
              {notifyCreating ? "创建中..." : "创建模板"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

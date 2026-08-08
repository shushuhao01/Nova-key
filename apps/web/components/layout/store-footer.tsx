"use client"

import { useState } from "react"
import { MapPin, Phone, Mail, Send, MessageCircle, QrCode, Headset } from "lucide-react"
import { useLocale, useSiteConfig } from "@/lib/context"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"

function GithubIcon({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" className={className}>
      <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z" />
    </svg>
  )
}

export function StoreFooter() {
  const { t } = useLocale()
  const { config } = useSiteConfig()
  const [contactOpen, setContactOpen] = useState(false)

  // 联系方式弹窗的展示数据（任一有值才显示「联系我们」入口）
  const contactItems = [
    { key: "address", label: t("footer.contactAddress"), icon: MapPin, render: config?.contact_address },
    { key: "phone", label: t("footer.contactPhone"), icon: Phone, render: config?.contact_phone },
    { key: "email", label: t("footer.contactEmail"), icon: Mail, render: config?.contact_email ? (
      <a href={`mailto:${config.contact_email}`} className="text-primary hover:underline break-all">
        {config.contact_email}
      </a>
    ) : null },
    { key: "telegram", label: t("footer.contactTelegram"), icon: Send, render: (config?.contact_telegram || config?.contact_telegram_group) ? (
      <a
        href={config?.contact_telegram_group && config.contact_telegram_group.startsWith("http")
          ? config.contact_telegram_group
          : `https://t.me/${String(config?.contact_telegram || "").replace(/^@/, "")}`}
        target="_blank"
        rel="noopener noreferrer"
        className="text-primary hover:underline break-all"
      >
        {config?.contact_telegram_group && config.contact_telegram_group.startsWith("http")
          ? config.contact_telegram_group
          : config?.contact_telegram || ""}
      </a>
    ) : null },
  ]
  const hasContact = contactItems.some(i => i.render) || !!config?.wechat_kefu_link || !!config?.wechat_qrcode

  return (
    <footer className="border-t border-border bg-muted/40">
      <div className="mx-auto flex max-w-7xl flex-col items-center gap-2 px-4 py-4 lg:px-6">
        {/* 主行：页脚文案 / 联系我们 / GitHub */}
        <div className="flex items-center justify-center gap-4">
          {config?.footer_text && (
            <p className="text-sm text-muted-foreground">{config.footer_text}</p>
          )}
          {hasContact && (
            <button
              type="button"
              onClick={() => setContactOpen(true)}
              className="flex items-center gap-1.5 rounded-md border border-border bg-background px-2.5 py-1 text-xs font-medium text-foreground transition-colors hover:bg-accent"
            >
              <Headset className="h-3.5 w-3.5 text-muted-foreground" />
              {t("footer.contactUs")}
            </button>
          )}
          {config?.github_url && (
            <a
              href={config.github_url}
              target="_blank"
              rel="noopener noreferrer"
              className="text-muted-foreground transition-colors hover:text-foreground"
              title="GitHub"
            >
              <GithubIcon className="h-5 w-5" />
            </a>
          )}
        </div>

        {/* 版权 / 备案行：不填则不显示 */}
        {(config?.copyright || config?.icp_number || config?.police_number) && (
          <div className="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
            {config?.copyright && <span>{config.copyright}</span>}
            {config?.icp_number && (
              <a
                href="https://beian.miit.gov.cn"
                target="_blank"
                rel="noopener noreferrer"
                className="transition-colors hover:text-foreground"
              >
                {t("footer.icp")}：{config.icp_number}
              </a>
            )}
            {config?.police_number && (
              <a
                href={`https://beian.mps.gov.cn/#/query/webSearch?code=${encodeURIComponent(config.police_number)}`}
                target="_blank"
                rel="noopener noreferrer"
                className="transition-colors hover:text-foreground"
              >
                {t("footer.police")}：{config.police_number}
              </a>
            )}
          </div>
        )}
      </div>

      {/* 联系我们弹窗 */}
      <Dialog open={contactOpen} onOpenChange={setContactOpen}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Headset className="h-5 w-5 text-primary" />
              {t("footer.contactUsTitle")}
            </DialogTitle>
          </DialogHeader>
          <div className="flex flex-col gap-3">
            {contactItems.map((item) => (
              item.render ? (
                <div key={item.key} className="flex items-start gap-3 rounded-lg border border-border bg-muted/30 px-3 py-2.5">
                  <item.icon className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
                  <div className="min-w-0">
                    <p className="text-xs text-muted-foreground">{item.label}</p>
                    <div className="mt-0.5 text-sm text-foreground">{item.render}</div>
                  </div>
                </div>
              ) : null
            ))}
            {config?.wechat_kefu_link && (
              <div className="flex items-start gap-3 rounded-lg border border-border bg-muted/30 px-3 py-2.5">
                <MessageCircle className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
                <div className="min-w-0">
                  <p className="text-xs text-muted-foreground">{t("footer.wechatKefu")}</p>
                  <a
                    href={config.wechat_kefu_link}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="mt-0.5 inline-flex items-center gap-1 rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
                  >
                    <MessageCircle className="h-4 w-4" />
                    {t("footer.wechatKefu")}
                  </a>
                </div>
              </div>
            )}
            {config?.wechat_qrcode && (
              <div className="flex flex-col items-center gap-2 rounded-lg border border-border bg-muted/30 px-3 py-4">
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  <QrCode className="h-4 w-4" />
                  {t("footer.wechatQrcode")}
                </div>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={config.wechat_qrcode}
                  alt={t("footer.wechatQrcode")}
                  className="h-40 w-40 rounded-lg border border-border bg-white object-contain"
                />
              </div>
            )}
          </div>
        </DialogContent>
      </Dialog>
    </footer>
  )
}

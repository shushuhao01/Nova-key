"use client"

import { useEffect, useState } from "react"
import { Headset, X, MapPin, Phone, Mail, Send, MessageCircle, QrCode } from "lucide-react"
import { useLocale, useSiteConfig } from "@/lib/context"
import { cn } from "@/lib/utils"

/**
 * 前台右下角悬浮「联系客服」入口
 * 点击展开面板，展示管理后台「网站设置 → 联系我们」配置的全部联系方式
 * 链接可点击，二维码直接展示并引导长按识别
 */
export function FloatingContact() {
  const { t } = useLocale()
  const siteCfg = useSiteConfig()
  const config = siteCfg?.config ?? null
  const [open, setOpen] = useState(false)
  const [mounted, setMounted] = useState(false)

  // 仅在客户端挂载后渲染，SSR 阶段不输出任何内容，杜绝 SSR/hydration 边界异常
  useEffect(() => {
    setMounted(true)
  }, [])

  const hasContact = !!(
    config?.contact_email ||
    config?.contact_phone ||
    config?.contact_address ||
    config?.contact_telegram ||
    config?.contact_telegram_group ||
    config?.wechat_kefu_link ||
    config?.wechat_qrcode
  )

  // Esc 关闭
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false)
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [open])

  if (!mounted) return null
  if (!hasContact) return null

  const telegramHref =
    config?.contact_telegram_group && config.contact_telegram_group.startsWith("http")
      ? config.contact_telegram_group
      : `https://t.me/${String(config?.contact_telegram || "").replace(/^@/, "")}`
  const telegramText =
    config?.contact_telegram_group && config.contact_telegram_group.startsWith("http")
      ? config.contact_telegram_group
      : config?.contact_telegram || ""

  return (
    <div className="fixed bottom-16 right-5 z-50 flex flex-col items-end sm:bottom-20 sm:right-6">
      {open && (
        <>
          {/* 点击遮罩关闭 */}
          <div className="fixed inset-0 z-40" onClick={() => setOpen(false)} aria-hidden="true" />
          <div className="relative z-50 mb-3 w-[320px] max-w-[calc(100vw-2.5rem)] rounded-2xl border border-border bg-card p-4 shadow-xl">
            {/* 头部 */}
            <div className="mb-3 flex items-center justify-between">
              <div className="flex items-center gap-2 text-sm font-semibold text-foreground">
                <Headset className="h-4 w-4 text-primary" />
                {t("footer.contactUsTitle")}
              </div>
              <button
                type="button"
                onClick={() => setOpen(false)}
                className="rounded-md p-1 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                aria-label="Close"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            <div className="flex max-h-[58vh] flex-col gap-2.5 overflow-y-auto pr-0.5">
              {/* 地址 */}
              {config?.contact_address && (
                <div className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/30 px-3 py-2.5">
                  <MapPin className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
                  <div className="min-w-0">
                    <p className="text-xs text-muted-foreground">{t("footer.contactAddress")}</p>
                    <p className="mt-0.5 break-all text-sm text-foreground">{config.contact_address}</p>
                  </div>
                </div>
              )}
              {/* 电话 */}
              {config?.contact_phone && (
                <div className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/30 px-3 py-2.5">
                  <Phone className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
                  <div className="min-w-0">
                    <p className="text-xs text-muted-foreground">{t("footer.contactPhone")}</p>
                    <a
                      href={`tel:${config.contact_phone}`}
                      className="mt-0.5 block break-all text-sm text-primary hover:underline"
                    >
                      {config.contact_phone}
                    </a>
                  </div>
                </div>
              )}
              {/* 邮箱 */}
              {config?.contact_email && (
                <div className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/30 px-3 py-2.5">
                  <Mail className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
                  <div className="min-w-0">
                    <p className="text-xs text-muted-foreground">{t("footer.contactEmail")}</p>
                    <a
                      href={`mailto:${config.contact_email}`}
                      className="mt-0.5 block break-all text-sm text-primary hover:underline"
                    >
                      {config.contact_email}
                    </a>
                  </div>
                </div>
              )}
              {/* Telegram */}
              {(config?.contact_telegram || config?.contact_telegram_group) && (
                <div className="flex items-start gap-2.5 rounded-lg border border-border bg-muted/30 px-3 py-2.5">
                  <Send className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
                  <div className="min-w-0">
                    <p className="text-xs text-muted-foreground">{t("footer.contactTelegram")}</p>
                    <a
                      href={telegramHref}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="mt-0.5 block break-all text-sm text-primary hover:underline"
                    >
                      {telegramText}
                    </a>
                  </div>
                </div>
              )}
              {/* 微信客服链接 */}
              {config?.wechat_kefu_link && (
                <div className="flex flex-col gap-1">
                  <a
                    href={config.wechat_kefu_link}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="flex items-center justify-center gap-2 rounded-lg bg-primary px-3 py-2.5 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90"
                  >
                    <MessageCircle className="h-4 w-4 shrink-0" />
                    {t("footer.wechatKefu")}
                  </a>
                  <p className="text-center text-xs text-muted-foreground">{t("floating.wechatKefuHint")}</p>
                </div>
              )}
              {/* 微信二维码 */}
              {config?.wechat_qrcode && (
                <div className="flex flex-col items-center gap-2 rounded-lg border border-border bg-muted/30 px-3 py-3">
                  <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    <QrCode className="h-4 w-4" />
                    {t("footer.wechatQrcode")} · {t("floating.scanHint")}
                  </div>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img
                    src={config.wechat_qrcode}
                    alt={t("footer.wechatQrcode")}
                    className="h-40 w-40 rounded-lg border border-border bg-white object-contain"
                  />
                  <p className="text-xs text-muted-foreground">{t("floating.longPressHint")}</p>
                </div>
              )}
            </div>
          </div>
        </>
      )}

      {/* 悬浮按钮 */}
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className={cn(
          "relative flex h-14 w-14 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-lg transition-transform hover:scale-105",
          open && "scale-105"
        )}
        aria-label={t("footer.contactUs")}
      >
        {open ? <X className="h-6 w-6" /> : <Headset className="h-6 w-6" />}
        {!open && (
          <span className="absolute inset-0 -z-10 animate-ping rounded-full bg-primary opacity-40" />
        )}
      </button>
    </div>
  )
}

"use client"

import { useState } from "react"
import { toast } from "sonner"
import { FileText, Copy, AlertTriangle } from "lucide-react"
import { cn } from "@/lib/utils"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog"
import {
  SMS_TEMPLATE_COMMON_NOTES,
  buildSmsTemplateApplyText,
  getSmsTemplateGuide,
} from "@/lib/sms-templates"

interface SmsTemplateGuideButtonProps {
  /** 模板指引 key，见 lib/sms-templates.ts（如 sms_tpl_login_code） */
  guideKey: string
  /** 按钮文案，默认「申请模板」 */
  label?: string
  className?: string
}

/**
 * 「申请模板」入口：点击弹窗查看模板正文 / 变量表 / 注意事项，并支持一键复制申请信息。
 * 纯前端只读展示，不依赖 sms_enabled（未启用短信时同样可查看）。
 */
export function SmsTemplateGuideButton({ guideKey, label = "申请模板", className }: SmsTemplateGuideButtonProps) {
  const [open, setOpen] = useState(false)
  const guide = getSmsTemplateGuide(guideKey)

  if (!guide) return null

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(buildSmsTemplateApplyText(guide))
      toast.success("申请信息已复制")
    } catch {
      toast.error("复制失败，请手动复制")
    }
  }

  return (
    <>
      <button
        type="button"
        className={cn(
          "flex h-10 shrink-0 items-center gap-1 whitespace-nowrap rounded-lg border border-input bg-background px-3 text-xs font-medium text-foreground transition-colors hover:bg-accent",
          className
        )}
        onClick={() => setOpen(true)}
      >
        <FileText className="h-3.5 w-3.5" />
        {label}
      </button>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[85vh] max-w-lg overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{guide.title} · 申请模板</DialogTitle>
            <DialogDescription>
              在阿里云短信控制台「国内消息 → 模板管理」提交申请，审核通过后把模板 CODE 填回此处。
            </DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-4">
            <div className="grid grid-cols-2 gap-3">
              <div className="rounded-lg border border-border p-3">
                <p className="text-xs text-muted-foreground">场景</p>
                <p className="mt-1 text-sm font-medium text-foreground">{guide.scene}</p>
              </div>
              <div className="rounded-lg border border-border p-3">
                <p className="text-xs text-muted-foreground">建议模板类型</p>
                <p className="mt-1 text-sm font-medium text-foreground">{guide.kind}</p>
              </div>
            </div>

            <div className="rounded-lg border border-border p-3">
              <p className="text-xs text-muted-foreground">触发时机</p>
              <p className="mt-1 text-sm text-foreground">{guide.trigger}</p>
            </div>

            <div className="rounded-lg border border-blue-500/30 bg-blue-500/5 p-3">
              <div className="flex items-center justify-between gap-2">
                <p className="text-xs font-medium text-foreground">模板正文示例</p>
                <button
                  type="button"
                  className="flex items-center gap-1 rounded-md border border-input bg-background px-2 py-1 text-xs font-medium text-foreground hover:bg-accent"
                  onClick={() => handleCopy()}
                >
                  <Copy className="h-3 w-3" />
                  复制申请信息
                </button>
              </div>
              <p className="mt-2 select-all whitespace-pre-wrap break-all font-mono text-sm text-foreground">
                {guide.content}
              </p>
            </div>

            <div>
              <p className="text-sm font-medium text-foreground">变量说明（TemplateParam）</p>
              <div className="mt-2 overflow-hidden rounded-lg border border-border">
                <table className="w-full text-left text-xs">
                  <thead className="bg-muted/50 text-muted-foreground">
                    <tr>
                      <th className="px-3 py-2 font-medium">变量名</th>
                      <th className="px-3 py-2 font-medium">含义</th>
                      <th className="px-3 py-2 font-medium">示例值</th>
                    </tr>
                  </thead>
                  <tbody>
                    {guide.variables.map((v) => (
                      <tr key={v.name} className="border-t border-border">
                        <td className="px-3 py-2 font-mono text-foreground">{v.name}</td>
                        <td className="px-3 py-2 text-muted-foreground">{v.desc}</td>
                        <td className="px-3 py-2 font-mono text-muted-foreground">{v.example}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-3">
              <div className="flex items-center gap-2">
                <AlertTriangle className="h-4 w-4 shrink-0 text-amber-500" />
                <p className="text-sm font-medium text-foreground">注意事项</p>
              </div>
              <ul className="mt-2 flex list-disc flex-col gap-1 pl-5 text-xs leading-relaxed text-muted-foreground">
                {[...SMS_TEMPLATE_COMMON_NOTES, ...guide.notes].map((note, idx) => (
                  <li key={idx}>{note}</li>
                ))}
              </ul>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}

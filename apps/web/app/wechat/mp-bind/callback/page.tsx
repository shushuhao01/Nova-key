"use client"

import { useEffect, useState } from "react"
import { wechatMpApi } from "@/services/api"
import { CheckCircle2, AlertCircle, Loader2, MessageCircle } from "lucide-react"

/** 公众号 OAuth 回调页：用授权 code 完成账号绑定 */
export default function MpBindCallbackPage() {
  const [status, setStatus] = useState<"binding" | "success" | "error">("binding")
  const [msg, setMsg] = useState("")

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const code = params.get("code")
    const state = params.get("state")
    const savedState = sessionStorage.getItem("mp_bind_state")
    sessionStorage.removeItem("mp_bind_state")
    if (savedState && (!state || savedState !== state)) {
      setStatus("error")
      setMsg("绑定链接已过期或不匹配，请重新打开绑定链接")
      return
    }
    if (!code || !state) {
      setStatus("error")
      setMsg("微信授权失败：缺少必要参数")
      return
    }
    wechatMpApi.bind(code, state)
      .then(() => setStatus("success"))
      .catch((err: unknown) => {
        setStatus("error")
        setMsg(err instanceof Error ? err.message : "绑定失败，请稍后重试")
      })
  }, [])

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-b from-background to-muted/40 p-4">
      <div className="w-full max-w-sm rounded-2xl border border-border bg-card p-8 text-center shadow-xl">
        {status === "binding" ? (
          <>
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-primary/10">
              <Loader2 className="h-7 w-7 animate-spin text-primary" />
            </div>
            <h1 className="text-lg font-bold text-foreground">正在绑定…</h1>
            <p className="mt-2 text-sm text-muted-foreground">正在完成公众号账号绑定，请稍候</p>
          </>
        ) : status === "success" ? (
          <>
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-emerald-500/10">
              <CheckCircle2 className="h-8 w-8 text-emerald-500" />
            </div>
            <h1 className="text-lg font-bold text-foreground">绑定成功</h1>
            <p className="mt-2 text-sm text-muted-foreground">
              您的账号已与公众号绑定，之后订单发货、佣金到账、提现结果等通知将通过公众号推送给您
            </p>
            <div className="mt-4 flex items-center justify-center gap-2 rounded-lg bg-primary/5 px-3 py-2.5 text-xs text-muted-foreground">
              <MessageCircle className="h-4 w-4 shrink-0 text-primary" />
              请保持关注公众号，取消关注后将无法接收通知
            </div>
            <button
              type="button"
              onClick={() => window.location.href = "/"}
              className="mt-6 inline-flex h-11 w-full items-center justify-center rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
            >
              返回商城
            </button>
          </>
        ) : (
          <>
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-red-500/10">
              <AlertCircle className="h-7 w-7 text-red-500" />
            </div>
            <h1 className="text-lg font-bold text-foreground">绑定失败</h1>
            <p className="mt-2 text-sm text-muted-foreground">{msg || "发生未知错误，请稍后重试"}</p>
            <button
              type="button"
              onClick={() => window.location.href = "/wechat/mp-bind"}
              className="mt-6 inline-flex h-11 w-full items-center justify-center rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
            >
              重新绑定
            </button>
          </>
        )}
      </div>
    </div>
  )
}

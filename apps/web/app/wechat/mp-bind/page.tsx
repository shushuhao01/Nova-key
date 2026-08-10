"use client"

import { useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { wechatMpApi } from "@/services/api"
import { Link2, Loader2, LogIn, AlertCircle } from "lucide-react"

/**
 * 公众号账号绑定入口（公开链接，可放入服务号菜单 / 关注自动回复）。
 * 客户在微信内点击打开：已登录 → 跳转微信授权 → 回调页完成绑定；
 * 未登录 → 引导先登录账号再绑定。
 */
export default function MpBindPage() {
  const router = useRouter()
  const [status, setStatus] = useState<"checking" | "redirecting" | "needLogin" | "notInWechat" | "error">("checking")
  const [msg, setMsg] = useState("")

  useEffect(() => {
    // 非微信浏览器提示（公众号菜单/关注回复内打开均为微信内浏览器）
    if (!/MicroMessenger/i.test(navigator.userAgent)) {
      setStatus("notInWechat")
      return
    }
    const token = localStorage.getItem("auth_token")
    if (!token) {
      setStatus("needLogin")
      return
    }
    wechatMpApi.bindUrl()
      .then((r) => {
        if (r?.oauth_url && r?.state) {
          // 以服务端返回的 state 为准（防 CSRF），存到 sessionStorage 供回调页校验
          sessionStorage.setItem("mp_bind_state", r.state)
          setStatus("redirecting")
          window.location.href = r.oauth_url
        } else {
          setStatus("error")
          setMsg("生成授权链接失败，请稍后重试")
        }
      })
      .catch((err: unknown) => {
        setStatus("error")
        setMsg(err instanceof Error ? err.message : "生成授权链接失败，请稍后重试")
      })
  }, [])

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-b from-background to-muted/40 p-4">
      <div className="w-full max-w-sm rounded-2xl border border-border bg-card p-8 text-center shadow-xl">
        {status === "checking" || status === "redirecting" ? (
          <>
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-primary/10">
              <Loader2 className="h-7 w-7 animate-spin text-primary" />
            </div>
            <h1 className="text-lg font-bold text-foreground">
              {status === "redirecting" ? "正在跳转微信授权…" : "正在准备绑定…"}
            </h1>
            <p className="mt-2 text-sm text-muted-foreground">请稍候，即将完成公众号账号绑定</p>
          </>
        ) : status === "needLogin" ? (
          <>
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-amber-500/10">
              <LogIn className="h-7 w-7 text-amber-500" />
            </div>
            <h1 className="text-lg font-bold text-foreground">请先登录账号</h1>
            <p className="mt-2 text-sm text-muted-foreground">
              登录后即可绑定公众号，绑定成功后在公众号内即可接收订单、佣金等消息通知
            </p>
            <button
              type="button"
              onClick={() => router.push(`/login?redirect=${encodeURIComponent("/wechat/mp-bind")}`)}
              className="mt-6 inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-primary text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
            >
              <LogIn className="h-4 w-4" />
              去登录
            </button>
          </>
        ) : status === "notInWechat" ? (
          <>
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-primary/10">
              <Link2 className="h-7 w-7 text-primary" />
            </div>
            <h1 className="text-lg font-bold text-foreground">请在微信中打开</h1>
            <p className="mt-2 text-sm text-muted-foreground">
              请通过公众号菜单或消息进入本页面完成绑定，以便接收订单、佣金等通知
            </p>
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
              onClick={() => window.location.reload()}
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

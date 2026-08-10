"use client"

import { useEffect, useRef, useState } from "react"
import Link from "next/link"
import { useSearchParams } from "next/navigation"
import { ShieldCheck, AlertCircle, Loader2 } from "lucide-react"
import { distributorApi } from "@/services/api"

type BindState = "processing" | "success" | "error"

export default function BindWechatCallbackPage() {
  const searchParams = useSearchParams()
  const [state, setState] = useState<BindState>("processing")
  const [error, setError] = useState("")
  const done = useRef(false)

  useEffect(() => {
    if (done.current) return
    done.current = true
    const code = searchParams.get("code") || ""
    const stateParam = searchParams.get("state") || ""
    if (!code) {
      setError("微信授权失败：缺少 code 参数")
      setState("error")
      return
    }
    distributorApi
      .wechatCallback(code, stateParam)
      .then(() => {
        setState("success")
        // 绑定成功后自动跳回分销中心
        setTimeout(() => {
          window.location.href = "/my/distribution"
        }, 1500)
      })
      .catch((err: unknown) => {
        setError((err as any)?.message || "微信绑定失败，请重试")
        setState("error")
      })
  }, [searchParams])

  return (
    <div className="mx-auto flex min-h-[60vh] max-w-md items-center justify-center p-6">
      <div className="w-full rounded-xl border border-border bg-card p-8 text-center shadow-sm">
        {state === "processing" && (
          <>
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-primary/10">
              <Loader2 className="h-7 w-7 animate-spin text-primary" />
            </div>
            <h1 className="text-lg font-bold text-foreground">正在绑定微信…</h1>
            <p className="mt-2 text-sm text-muted-foreground">正在与微信确认授权信息，请稍候</p>
          </>
        )}
        {state === "success" && (
          <>
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-emerald-500/10">
              <ShieldCheck className="h-7 w-7 text-emerald-600" />
            </div>
            <h1 className="text-lg font-bold text-foreground">微信绑定成功</h1>
            <p className="mt-2 text-sm text-muted-foreground">正在跳转回分销中心…</p>
          </>
        )}
        {state === "error" && (
          <>
            <div className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-full bg-red-500/10">
              <AlertCircle className="h-7 w-7 text-red-500" />
            </div>
            <h1 className="text-lg font-bold text-foreground">微信绑定失败</h1>
            <p className="mt-2 text-sm text-muted-foreground">{error}</p>
            <Link
              href="/my/distribution"
              className="mt-5 inline-flex h-10 items-center gap-2 rounded-lg bg-primary px-5 text-sm font-semibold text-primary-foreground transition-all hover:brightness-110"
            >
              返回分销中心
            </Link>
          </>
        )}
      </div>
    </div>
  )
}

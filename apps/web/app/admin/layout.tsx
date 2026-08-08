import React from "react"
import { AdminShell } from "@/components/layout/admin-shell"

// 后台必须实时 SSR：纯 client 页面默认会被 Next 静态预渲染并带上
// `Cache-Control: s-maxage=31536000`（缓存一年），导致更新后用户仍看到旧页面
// （如支付渠道「测试连接」按钮时有时无）。强制动态渲染即可根治。
// 注意：route segment config（dynamic）必须在 server 组件（无 "use client"）中导出才生效。
export const dynamic = "force-dynamic"

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return <AdminShell>{children}</AdminShell>
}

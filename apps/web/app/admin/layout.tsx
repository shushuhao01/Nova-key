"use client"

// 后台必须实时 SSR：纯 client 页面默认会被 Next 静态预渲染并带上
// `Cache-Control: s-maxage=31536000`（缓存一年），导致更新后用户仍看到旧页面
// （如支付渠道「测试连接」按钮时有时无）。强制动态渲染即可根治。
export const dynamic = "force-dynamic"

import React from "react"
import { AdminSidebar } from "@/components/layout/admin-sidebar"
import { useRequireAdmin } from "@/lib/hooks"

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  const user = useRequireAdmin()

  if (!user) return null

  return (
    <div className="min-h-screen bg-background">
      <AdminSidebar />
      <main className="md:ml-60 min-h-screen">
        <div className="p-6 lg:p-8">{children}</div>
      </main>
    </div>
  )
}

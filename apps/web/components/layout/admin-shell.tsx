"use client"

import React from "react"
import { AdminSidebar } from "@/components/layout/admin-sidebar"
import { useRequireAdmin } from "@/lib/hooks"

/** 管理后台的客户端外壳：登录校验 + 侧边栏 + 内容区 */
export function AdminShell({ children }: { children: React.ReactNode }) {
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

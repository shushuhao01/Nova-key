"use client"

import { useEffect } from "react"
import { useRouter, usePathname } from "next/navigation"
import { useAuth } from "./context"

/**
 * Redirects to login if user is not authenticated.
 * Passes current path as redirect param so login page can redirect back.
 */
export function useRequireAuth() {
  const { user, isLoggedIn, authLoaded } = useAuth()
  const router = useRouter()
  const pathname = usePathname()

  useEffect(() => {
    if (!authLoaded) return
    if (!isLoggedIn) {
      router.replace(`/login?redirect=${encodeURIComponent(pathname)}`)
    }
  }, [authLoaded, isLoggedIn, router, pathname])

  return authLoaded ? user : null
}

/**
 * Requires backend access (ADMIN or STAFF with BACKEND_ACCESS permission).
 * - Not logged in → redirect to /login?redirect=currentPath
 * - Logged in but no backend access → redirect to /
 */
const hasBackendAccess = (user: { role?: string; permissions?: string[] } | null): boolean => {
  if (!user) return false
  if (user.role === "ADMIN") return true
  return (user.permissions ?? []).includes("BACKEND_ACCESS")
}

export function useRequireAdmin() {
  const { user, isLoggedIn, authLoaded } = useAuth()
  const router = useRouter()
  const pathname = usePathname()

  useEffect(() => {
    if (!authLoaded) return
    if (!isLoggedIn) {
      router.replace(`/login?redirect=${encodeURIComponent(pathname)}`)
    } else if (!hasBackendAccess(user)) {
      router.replace("/")
    }
  }, [authLoaded, isLoggedIn, user, router, pathname])

  // 同步判断：未加载完 或 无后台访问权限均返回 null，阻止子组件渲染
  if (!authLoaded || !isLoggedIn || !hasBackendAccess(user)) return null
  return user
}

"use client"

import { useState, useEffect, useCallback } from "react"
import {
  Search, ChevronLeft, ChevronRight, Plus, Pencil, Trash2, Eye, KeyRound,
  Ban, CheckCircle2, UserRound, ShieldCheck, Users, X, Shield,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { useLocale, useAuth } from "@/lib/context"
import { toast } from "sonner"
import { adminSystemApi, withMockFallback } from "@/services/api"
import { mockSystemStaffList, mockSystemRoleList, mockPermissionList } from "@/lib/mock-data"
import type { SystemStaffItem, SystemRoleItem, PermissionItem } from "@/types"

const ITEMS_PER_PAGE = 10
type Tab = "users" | "roles"

interface StaffForm {
  username: string
  email: string
  password: string
  role_id: string
}

interface RoleForm {
  code: string
  name: string
  description: string
  permissions: string[]
}

const emptyStaffForm: StaffForm = { username: "", email: "", password: "", role_id: "" }
const emptyRoleForm: RoleForm = { code: "", name: "", description: "", permissions: [] }

export default function AdminSystemPage() {
  const { t } = useLocale()
  const { user: me } = useAuth()

  const [tab, setTab] = useState<Tab>("users")
  const [staffList, setStaffList] = useState<SystemStaffItem[]>([])
  const [roleList, setRoleList] = useState<SystemRoleItem[]>([])
  const [permissions, setPermissions] = useState<PermissionItem[]>(mockPermissionList)
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState("")
  const [page, setPage] = useState(1)

  // ── 员工相关弹窗 ──
  const [staffModal, setStaffModal] = useState<null | { mode: "create" | "edit"; staff?: SystemStaffItem }>(null)
  const [staffForm, setStaffForm] = useState<StaffForm>(emptyStaffForm)
  const [saving, setSaving] = useState(false)
  const [resetOpen, setResetOpen] = useState<SystemStaffItem | null>(null)
  const [newPassword, setNewPassword] = useState("")
  const [detailOpen, setDetailOpen] = useState<SystemStaffItem | null>(null)
  const [detailPermNames, setDetailPermNames] = useState<string[]>([])

  // ── 角色相关弹窗 ──
  const [roleModal, setRoleModal] = useState<null | { mode: "create" | "edit"; role?: SystemRoleItem }>(null)
  const [roleForm, setRoleForm] = useState<RoleForm>(emptyRoleForm)

  // 权限清单（角色配置勾选用）
  const fetchPermissions = useCallback(async () => {
    try {
      const data = await withMockFallback(
        () => adminSystemApi.getPermissions(),
        () => mockPermissionList
      )
      if (Array.isArray(data) && data.length > 0) setPermissions(data)
    } catch {
      // keep mock
    }
  }, [])

  useEffect(() => { fetchPermissions() }, [fetchPermissions])

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      if (tab === "users") {
        const data = await withMockFallback(
          () => adminSystemApi.getStaff({ page, page_size: ITEMS_PER_PAGE, keyword: search || undefined }),
          () => mockSystemStaffList({ page, page_size: ITEMS_PER_PAGE, keyword: search || undefined })
        )
        setStaffList(data.list)
        setTotal(data.pagination.total)
      } else {
        const data = await withMockFallback(
          () => adminSystemApi.getRoles({ page, page_size: ITEMS_PER_PAGE, keyword: search || undefined }),
          () => mockSystemRoleList({ page, page_size: ITEMS_PER_PAGE, keyword: search || undefined })
        )
        setRoleList(data.list)
        setTotal(data.pagination.total)
      }
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "加载失败")
      if (tab === "users") setStaffList([])
      else setRoleList([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [tab, page, search])

  useEffect(() => {
    const timer = setTimeout(() => setPage(1), 300)
    return () => clearTimeout(timer)
  }, [search, tab])

  useEffect(() => { fetchList() }, [fetchList])

  const totalPages = Math.max(1, Math.ceil(total / ITEMS_PER_PAGE))

  const permName = (code: string) =>
    permissions.find(p => p.code === code)?.name || code

  // ── 员工操作 ──
  const openCreateStaff = () => {
    setStaffForm(emptyStaffForm)
    setStaffModal({ mode: "create" })
  }

  const openEditStaff = (staff: SystemStaffItem) => {
    setStaffForm({ username: staff.username, email: staff.email, password: "", role_id: staff.role_id || "" })
    setStaffModal({ mode: "edit", staff })
  }

  const saveStaff = async () => {
    if (!staffModal) return
    if (!staffForm.username.trim()) return toast.error(t("admin.staffUsername") + " *")
    if (staffModal.mode === "create") {
      if (!staffForm.email.trim()) return toast.error(t("admin.staffEmail") + " *")
      if (!staffForm.password || staffForm.password.length < 6) return toast.error(t("admin.staffPasswordHint"))
      if (!staffForm.role_id) return toast.error(t("admin.selectRole"))
    } else if (!staffForm.role_id && staffModal.staff?.role !== "ADMIN") {
      return toast.error(t("admin.selectRole"))
    }
    setSaving(true)
    try {
      if (staffModal.mode === "create") {
        await withMockFallback(
          () => adminSystemApi.createStaff({
            username: staffForm.username.trim(),
            email: staffForm.email.trim(),
            password: staffForm.password,
            role_id: staffForm.role_id,
          }),
          () => ({ id: "mock" })
        )
        toast.success(t("admin.createStaff"))
      } else if (staffModal.staff) {
        await withMockFallback(
          () => adminSystemApi.updateStaff(staffModal.staff!.id, {
            username: staffForm.username.trim(),
            role_id: staffForm.role_id || undefined,
          }),
          () => null
        )
        toast.success(t("admin.editStaff"))
      }
      setStaffModal(null)
      fetchList()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  const toggleStaff = async (staff: SystemStaffItem) => {
    const next: 0 | 1 = staff.is_deleted ? 0 : 1
    const ok = window.confirm(
      t("admin.toggleStaffMsg").replace("{action}", next === 1 ? t("admin.disable") : t("admin.enable"))
    )
    if (!ok) return
    try {
      await withMockFallback(
        () => adminSystemApi.toggleStaff(staff.id, next),
        () => null
      )
      toast.success(next === 1 ? t("admin.disable") : t("admin.enable"))
      fetchList()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "操作失败")
    }
  }

  const deleteStaff = async (staff: SystemStaffItem) => {
    if (!window.confirm(t("admin.deleteStaffMsg"))) return
    try {
      await withMockFallback(
        () => adminSystemApi.deleteStaff(staff.id),
        () => null
      )
      toast.success(t("admin.deleteStaff"))
      fetchList()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "删除失败")
    }
  }

  const openDetail = async (staff: SystemStaffItem) => {
    setDetailOpen(staff)
    setDetailPermNames([])
    try {
      const data = await withMockFallback(
        () => adminSystemApi.staffDetail(staff.id),
        () => ({ ...staff, permissions: [] })
      )
      setDetailPermNames((data.permissions ?? []).map(permName))
    } catch {
      setDetailPermNames([])
    }
  }

  const submitResetPassword = async () => {
    if (!resetOpen) return
    if (!newPassword || newPassword.length < 6) return toast.error(t("admin.newPasswordPlaceholder"))
    setSaving(true)
    try {
      await withMockFallback(
        () => adminSystemApi.resetPassword(resetOpen.id, newPassword),
        () => null
      )
      toast.success(t("admin.resetPassword"))
      setResetOpen(null)
      setNewPassword("")
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "重置失败")
    } finally {
      setSaving(false)
    }
  }

  // ── 角色操作 ──
  const openCreateRole = () => {
    setRoleForm(emptyRoleForm)
    setRoleModal({ mode: "create" })
  }

  const openEditRole = (role: SystemRoleItem) => {
    setRoleForm({ code: role.code, name: role.name, description: role.description || "", permissions: [...role.permissions] })
    setRoleModal({ mode: "edit", role })
  }

  const togglePerm = (code: string) => {
    setRoleForm(f => ({
      ...f,
      permissions: f.permissions.includes(code)
        ? f.permissions.filter(c => c !== code)
        : [...f.permissions, code],
    }))
  }

  const saveRole = async () => {
    if (!roleModal) return
    if (!roleForm.name.trim()) return toast.error(t("admin.roleName") + " *")
    if (roleModal.mode === "create" && !roleForm.code.trim()) return toast.error(t("admin.roleCode") + " *")
    setSaving(true)
    try {
      if (roleModal.mode === "create") {
        await withMockFallback(
          () => adminSystemApi.createRole({
            code: roleForm.code.trim(),
            name: roleForm.name.trim(),
            description: roleForm.description.trim() || undefined,
            permissions: roleForm.permissions,
          }),
          () => ({ id: "mock" })
        )
        toast.success(t("admin.createRole"))
      } else if (roleModal.role) {
        await withMockFallback(
          () => adminSystemApi.updateRole(roleModal.role!.id, {
            name: roleForm.name.trim(),
            description: roleForm.description.trim() || undefined,
            permissions: roleForm.permissions,
          }),
          () => null
        )
        toast.success(t("admin.editRole"))
      }
      setRoleModal(null)
      fetchList()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "保存失败")
    } finally {
      setSaving(false)
    }
  }

  const deleteRole = async (role: SystemRoleItem) => {
    if (!window.confirm(t("admin.deleteRoleMsg"))) return
    try {
      await withMockFallback(
        () => adminSystemApi.deleteRole(role.id),
        () => null
      )
      toast.success(t("admin.deleteRole"))
      fetchList()
    } catch (err: unknown) {
      toast.error(err instanceof Error ? err.message : "删除失败")
    }
  }

  const renderStaffRow = (s: SystemStaffItem) => {
    const isSelf = s.id === me?.id
    return (
      <tr key={s.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
        <td className="px-4 py-3">
          <div className="flex items-center gap-2">
            <span className={cn(
              "flex h-7 w-7 items-center justify-center rounded-full",
              s.role === "ADMIN" ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground"
            )}>
              {s.role === "ADMIN" ? <ShieldCheck className="h-4 w-4" /> : <UserRound className="h-4 w-4" />}
            </span>
            <span className="font-medium text-foreground">
              {s.username}
              {isSelf && <span className="ml-1.5 text-xs text-muted-foreground">(me)</span>}
            </span>
          </div>
        </td>
        <td className="px-4 py-3 text-muted-foreground">{s.email}</td>
        <td className="px-4 py-3 text-foreground">{s.role_name || s.role}</td>
        <td className="px-4 py-3">
          <span className={cn(
            "inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-medium",
            s.is_deleted ? "bg-red-500/10 text-red-500" : "bg-emerald-500/10 text-emerald-600"
          )}>
            {s.is_deleted ? <Ban className="h-3 w-3" /> : <CheckCircle2 className="h-3 w-3" />}
            {s.is_deleted ? t("admin.staffDisabled") : t("admin.staffEnabled")}
          </span>
        </td>
        <td className="px-4 py-3 text-muted-foreground">{new Date(s.created_at).toLocaleDateString()}</td>
        <td className="px-4 py-3">
          <div className="flex items-center justify-end gap-1">
            <button
              type="button"
              onClick={() => openDetail(s)}
              className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
              title={t("admin.staffDetail")}
            >
              <Eye className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={() => openEditStaff(s)}
              className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
              title={t("admin.editStaff")}
            >
              <Pencil className="h-4 w-4" />
            </button>
            <button
              type="button"
              onClick={() => { setResetOpen(s); setNewPassword("") }}
              className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-amber-500/10 hover:text-amber-600"
              title={t("admin.resetPassword")}
            >
              <KeyRound className="h-4 w-4" />
            </button>
            {!isSelf && (
              <>
                <button
                  type="button"
                  onClick={() => toggleStaff(s)}
                  className={cn(
                    "flex h-8 w-8 items-center justify-center rounded-md",
                    s.is_deleted
                      ? "text-emerald-600 hover:bg-emerald-500/10"
                      : "text-red-500 hover:bg-red-500/10"
                  )}
                  title={s.is_deleted ? t("admin.enable") : t("admin.disable")}
                >
                  {s.is_deleted ? <CheckCircle2 className="h-4 w-4" /> : <Ban className="h-4 w-4" />}
                </button>
                <button
                  type="button"
                  onClick={() => deleteStaff(s)}
                  className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                  title={t("admin.deleteStaff")}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </>
            )}
          </div>
        </td>
      </tr>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-foreground">{t("admin.system")}</h1>
        <p className="text-sm text-muted-foreground">{t("admin.systemDesc")}</p>
      </div>

      {/* Tabs + search + create */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex rounded-lg border border-border bg-card p-1">
          {([
            { v: "users" as Tab, label: t("admin.systemUsers"), icon: Users },
            { v: "roles" as Tab, label: t("admin.systemRoles"), icon: ShieldCheck },
          ]).map(tabItem => (
            <button
              key={tabItem.v}
              type="button"
              onClick={() => setTab(tabItem.v)}
              className={cn(
                "flex items-center gap-1.5 rounded-md px-4 py-2 text-sm font-medium transition-colors",
                tab === tabItem.v
                  ? "bg-primary text-primary-foreground"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              <tabItem.icon className="h-4 w-4" />
              {tabItem.label}
            </button>
          ))}
        </div>
        <div className="flex flex-1 items-center justify-end gap-2">
          <div className="relative max-w-sm flex-1 sm:max-w-xs">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <input
              type="text"
              placeholder={tab === "users" ? t("admin.searchStaff") : t("admin.roleSearch")}
              className="h-10 w-full rounded-lg border border-input bg-background pl-9 pr-4 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <button
            type="button"
            onClick={tab === "users" ? openCreateStaff : openCreateRole}
            className="flex h-10 shrink-0 items-center gap-1.5 rounded-lg bg-primary px-4 text-sm font-medium text-primary-foreground shadow-sm transition-colors hover:bg-primary/90"
          >
            <Plus className="h-4 w-4" />
            {tab === "users" ? t("admin.createStaff") : t("admin.createRole")}
          </button>
        </div>
      </div>

      {tab === "users" ? (
        <p className="text-xs text-muted-foreground">{t("admin.systemUserDesc")}</p>
      ) : (
        <p className="text-xs text-muted-foreground">{t("admin.systemRoleDesc")}</p>
      )}

      {/* Table */}
      <div className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                {tab === "users" ? (
                  <>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.staffUsername")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.staffEmail")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.staffRole")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.staffStatus")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.createdAt")}</th>
                    <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
                  </>
                ) : (
                  <>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.roleName")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.roleCode")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.roleDesc")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.rolePermissions")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("admin.roleUsers")}</th>
                    <th className="px-4 py-3 text-right font-medium text-muted-foreground">{t("admin.actions")}</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={8} className="py-12">
                    <div className="flex items-center justify-center">
                      <div className="h-6 w-6 animate-spin rounded-full border-2 border-primary border-t-transparent" />
                    </div>
                  </td>
                </tr>
              ) : tab === "users" ? (
                staffList.length === 0 ? (
                  <tr>
                    <td colSpan={8} className="py-8 text-center text-sm text-muted-foreground">{t("admin.noStaffData")}</td>
                  </tr>
                ) : staffList.map(renderStaffRow)
              ) : roleList.length === 0 ? (
                <tr>
                  <td colSpan={8} className="py-8 text-center text-sm text-muted-foreground">{t("admin.noRoleData")}</td>
                </tr>
              ) : roleList.map(r => (
                <tr key={r.id} className="border-b border-border/50 last:border-0 hover:bg-muted/20 transition-colors">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <span className={cn(
                        "flex h-7 w-7 items-center justify-center rounded-full",
                        r.is_system ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground"
                      )}>
                        <Shield className="h-4 w-4" />
                      </span>
                      <span className="font-medium text-foreground">{r.name}</span>
                      {r.is_system === 1 && (
                        <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">{t("admin.roleSystem")}</span>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{r.code}</td>
                  <td className="max-w-[180px] truncate px-4 py-3 text-muted-foreground">{r.description || "—"}</td>
                  <td className="px-4 py-3">
                    <div className="flex max-w-[260px] flex-wrap gap-1">
                      {r.permissions.slice(0, 4).map(p => (
                        <span key={p} className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">{permName(p)}</span>
                      ))}
                      {r.permissions.length > 4 && (
                        <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">+{r.permissions.length - 4}</span>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-foreground">{r.user_count}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center justify-end gap-1">
                      <button
                        type="button"
                        onClick={() => openEditRole(r)}
                        disabled={r.is_system === 1}
                        className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-40 disabled:hover:bg-transparent"
                        title={r.is_system === 1 ? t("admin.roleSystemMsg") : t("admin.editRole")}
                      >
                        <Pencil className="h-4 w-4" />
                      </button>
                      <button
                        type="button"
                        onClick={() => deleteRole(r)}
                        disabled={r.is_system === 1}
                        className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-destructive/10 hover:text-destructive disabled:opacity-40 disabled:hover:bg-transparent"
                        title={r.is_system === 1 ? t("admin.roleSystemMsg") : t("admin.deleteRole")}
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-border px-4 py-3">
            <span className="text-sm text-muted-foreground">{t("admin.totalRecords")} {total}{t("admin.totalStaff")}</span>
            <div className="flex items-center gap-1">
              <button
                type="button"
                onClick={() => setPage(p => Math.max(1, p - 1))}
                disabled={page === 1}
                className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              {Array.from({ length: totalPages }, (_, i) => i + 1).map(pg => (
                <button
                  key={pg}
                  type="button"
                  onClick={() => setPage(pg)}
                  className={cn(
                    "flex h-8 w-8 items-center justify-center rounded-md text-sm font-medium",
                    page === pg
                      ? "bg-primary text-primary-foreground"
                      : "border border-input text-foreground hover:bg-accent"
                  )}
                >
                  {pg}
                </button>
              ))}
              <button
                type="button"
                onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                disabled={page === totalPages}
                className="flex h-8 w-8 items-center justify-center rounded-md border border-input text-muted-foreground hover:bg-accent disabled:opacity-50"
              >
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        )}
      </div>

      {/* ── 员工创建/编辑弹窗 ── */}
      {staffModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50" onClick={() => setStaffModal(null)} />
          <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-lg font-bold text-foreground">
                {staffModal.mode === "create" ? t("admin.createStaff") : t("admin.editStaff")}
              </h2>
              <button
                type="button"
                onClick={() => setStaffModal(null)}
                className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="flex flex-col gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">{t("admin.staffUsername")} *</label>
                <input
                  type="text"
                  value={staffForm.username}
                  onChange={(e) => setStaffForm(f => ({ ...f, username: e.target.value }))}
                  className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
              </div>
              {staffModal.mode === "create" ? (
                <>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-foreground">{t("admin.staffEmail")} *</label>
                    <input
                      type="email"
                      value={staffForm.email}
                      onChange={(e) => setStaffForm(f => ({ ...f, email: e.target.value }))}
                      className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                    />
                  </div>
                  <div>
                    <label className="mb-1 block text-sm font-medium text-foreground">{t("admin.staffPassword")} *</label>
                    <input
                      type="password"
                      value={staffForm.password}
                      onChange={(e) => setStaffForm(f => ({ ...f, password: e.target.value }))}
                      placeholder={t("admin.staffPasswordHint")}
                      className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                    />
                  </div>
                </>
              ) : (
                <div>
                  <label className="mb-1 block text-sm font-medium text-foreground">{t("admin.staffEmail")}</label>
                  <input
                    type="text"
                    value={staffForm.email}
                    disabled
                    className="h-10 w-full rounded-lg border border-input bg-muted px-3 text-sm text-muted-foreground"
                  />
                </div>
              )}
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">{t("admin.staffRole")} *</label>
                <select
                  value={staffForm.role_id}
                  disabled={staffModal.mode === "edit" && staffModal.staff?.id === me?.id}
                  onChange={(e) => setStaffForm(f => ({ ...f, role_id: e.target.value }))}
                  className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:bg-muted disabled:text-muted-foreground"
                >
                  <option value="">{t("admin.selectRole")}</option>
                  {roleList.map(r => (
                    <option key={r.id} value={r.id}>{r.name} ({r.code})</option>
                  ))}
                </select>
                {staffModal.mode === "edit" && staffModal.staff?.id === me?.id && (
                  <p className="mt-1 text-xs text-muted-foreground">{t("admin.roleSystemMsg")}</p>
                )}
              </div>
            </div>
            <div className="mt-6 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setStaffModal(null)}
                className="rounded-lg border border-input px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-accent"
              >
                {t("admin.cancel")}
              </button>
              <button
                type="button"
                onClick={saveStaff}
                disabled={saving}
                className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-60"
              >
                {saving ? t("admin.saving") : t("admin.save")}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── 重置密码弹窗 ── */}
      {resetOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50" onClick={() => setResetOpen(null)} />
          <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-lg font-bold text-foreground">{t("admin.resetPasswordTitle")}</h2>
              <button
                type="button"
                onClick={() => setResetOpen(null)}
                className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <p className="mb-3 text-sm text-muted-foreground">
              {resetOpen.username} · {t("admin.resetPasswordHint")}
            </p>
            <div>
              <label className="mb-1 block text-sm font-medium text-foreground">{t("admin.newPassword")} *</label>
              <input
                type="password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                placeholder={t("admin.newPasswordPlaceholder")}
                className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            <div className="mt-6 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setResetOpen(null)}
                className="rounded-lg border border-input px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-accent"
              >
                {t("admin.cancel")}
              </button>
              <button
                type="button"
                onClick={submitResetPassword}
                disabled={saving}
                className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-60"
              >
                {saving ? t("admin.saving") : t("admin.resetPassword")}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── 员工详情弹窗 ── */}
      {detailOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50" onClick={() => setDetailOpen(null)} />
          <div className="relative w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-lg font-bold text-foreground">{t("admin.staffDetail")}</h2>
              <button
                type="button"
                onClick={() => setDetailOpen(null)}
                className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="flex flex-col gap-3">
              <div className="rounded-lg border border-border bg-muted/20 p-3">
                <p className="text-xs text-muted-foreground">{t("admin.staffUsername")}</p>
                <p className="mt-1 font-medium text-foreground">{detailOpen.username}</p>
              </div>
              <div className="rounded-lg border border-border bg-muted/20 p-3">
                <p className="text-xs text-muted-foreground">{t("admin.staffEmail")}</p>
                <p className="mt-1 font-medium text-foreground">{detailOpen.email}</p>
              </div>
              <div className="rounded-lg border border-border bg-muted/20 p-3">
                <p className="text-xs text-muted-foreground">{t("admin.staffRole")}</p>
                <p className="mt-1 font-medium text-foreground">{detailOpen.role_name || detailOpen.role}</p>
              </div>
              <div className="rounded-lg border border-border bg-muted/20 p-3">
                <p className="text-xs text-muted-foreground">{t("admin.staffPermissions")}</p>
                <div className="mt-2 flex flex-wrap gap-1">
                  {detailPermNames.length === 0 ? (
                    <span className="text-sm text-muted-foreground">—</span>
                  ) : detailPermNames.map(name => (
                    <span key={name} className="rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">{name}</span>
                  ))}
                </div>
              </div>
            </div>
            <div className="mt-6 flex justify-end">
              <button
                type="button"
                onClick={() => setDetailOpen(null)}
                className="rounded-lg border border-input px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-accent"
              >
                {t("admin.cancel")}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── 角色创建/编辑弹窗 ── */}
      {roleModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/50" onClick={() => setRoleModal(null)} />
          <div className="relative max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-xl border border-border bg-card p-6 shadow-2xl">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-lg font-bold text-foreground">
                {roleModal.mode === "create" ? t("admin.createRole") : t("admin.editRole")}
              </h2>
              <button
                type="button"
                onClick={() => setRoleModal(null)}
                className="flex h-8 w-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
              >
                <X className="h-4 w-4" />
              </button>
            </div>
            <div className="flex flex-col gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">{t("admin.roleName")} *</label>
                <input
                  type="text"
                  value={roleForm.name}
                  onChange={(e) => setRoleForm(f => ({ ...f, name: e.target.value }))}
                  className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">{t("admin.roleCode")} *</label>
                <input
                  type="text"
                  value={roleForm.code}
                  disabled={roleModal.mode === "edit"}
                  onChange={(e) => setRoleForm(f => ({ ...f, code: e.target.value }))}
                  placeholder={t("admin.roleCodeHint")}
                  className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring disabled:bg-muted disabled:text-muted-foreground"
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">{t("admin.roleDesc")}</label>
                <textarea
                  value={roleForm.description}
                  onChange={(e) => setRoleForm(f => ({ ...f, description: e.target.value }))}
                  rows={2}
                  className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-foreground">{t("admin.rolePermissions")}</label>
                <p className="mb-2 text-xs text-muted-foreground">{t("admin.rolePermissionsHint")}</p>
                <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-2">
                  {permissions.map(p => (
                    <label
                      key={p.code}
                      className={cn(
                        "flex cursor-pointer items-center gap-2 rounded-lg border px-3 py-2 text-sm transition-colors",
                        roleForm.permissions.includes(p.code)
                          ? "border-primary/40 bg-primary/5 text-foreground"
                          : "border-border text-muted-foreground hover:bg-accent"
                      )}
                    >
                      <input
                        type="checkbox"
                        checked={roleForm.permissions.includes(p.code)}
                        onChange={() => togglePerm(p.code)}
                        className="h-4 w-4 accent-primary"
                      />
                      <span className="flex-1">{p.name}</span>
                      <span className="font-mono text-[10px] text-muted-foreground">{p.code}</span>
                    </label>
                  ))}
                </div>
              </div>
            </div>
            <div className="mt-6 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setRoleModal(null)}
                className="rounded-lg border border-input px-4 py-2 text-sm font-medium text-muted-foreground hover:bg-accent"
              >
                {t("admin.cancel")}
              </button>
              <button
                type="button"
                onClick={saveRole}
                disabled={saving}
                className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-60"
              >
                {saving ? t("admin.saving") : t("admin.save")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

"use client"

import { useEffect, useState, type ReactNode } from "react"
import { AlertTriangle } from "lucide-react"
import { Modal } from "@/components/ui/modal"
import { cn } from "@/lib/utils"

export interface ConfirmDialogOptions {
  title: string
  description?: ReactNode
  confirmText?: string
  cancelText?: string
  /** 危险操作（删除/清空等不可恢复），确认按钮为红色 */
  danger?: boolean
  /** 传入后在弹窗中展示输入框（替代 window.prompt） */
  input?: {
    label?: string
    placeholder?: string
    defaultValue?: string
    required?: boolean
  }
}

interface DialogRequest {
  options: ConfirmDialogOptions
  mode: "confirm" | "prompt"
  resolve: (value: string | boolean | null) => void
}

let pending: DialogRequest | null = null
const listeners = new Set<(req: DialogRequest | null) => void>()

function dispatch(req: DialogRequest | null) {
  pending = req
  listeners.forEach((listener) => listener(req))
}

/** 全局确认弹窗，替代浏览器原生 window.confirm */
export function confirmDialog(options: ConfirmDialogOptions): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    dispatch({ mode: "confirm", options, resolve: (value) => resolve(value === true) })
  })
}

/** 全局输入弹窗，替代浏览器原生 window.prompt；取消或关闭返回 null */
export function promptDialog(
  options: ConfirmDialogOptions & { input: NonNullable<ConfirmDialogOptions["input"]> }
): Promise<string | null> {
  return new Promise<string | null>((resolve) => {
    dispatch({ mode: "prompt", options, resolve: (value) => resolve(typeof value === "string" ? value : null) })
  })
}

/** 弹窗宿主，需在根布局挂载一次 */
export function ConfirmDialogHost() {
  const [request, setRequest] = useState<DialogRequest | null>(null)
  const [value, setValue] = useState("")
  const [error, setError] = useState("")

  useEffect(() => {
    const listener = (req: DialogRequest | null) => {
      setRequest(req)
      setValue(req?.options.input?.defaultValue ?? "")
      setError("")
    }
    listeners.add(listener)
    if (pending) listener(pending)
    return () => {
      listeners.delete(listener)
    }
  }, [])

  if (!request) return null

  const { options, mode } = request
  const isPrompt = mode === "prompt"
  const danger = Boolean(options.danger)

  const finish = (result: string | boolean | null) => {
    const resolve = request.resolve
    dispatch(null)
    resolve(result)
  }

  const handleConfirm = () => {
    if (!isPrompt) {
      finish(true)
      return
    }
    const trimmed = value.trim()
    if (options.input?.required !== false && !trimmed) {
      setError(options.input?.label ? `请输入${options.input.label}` : "请输入内容")
      return
    }
    finish(trimmed)
  }

  return (
    <Modal open onClose={() => finish(isPrompt ? null : false)} className="max-w-md">
      <div className="p-6">
        <div className="flex items-start gap-3">
          <div
            className={cn(
              "flex h-10 w-10 shrink-0 items-center justify-center rounded-full",
              danger ? "bg-red-500/10 text-red-500" : "bg-primary/10 text-primary"
            )}
          >
            <AlertTriangle className="h-5 w-5" />
          </div>
          <div className="min-w-0 flex-1">
            <h2 className="text-base font-semibold text-foreground">{options.title}</h2>
            {options.description && (
              <div className="mt-1.5 text-sm leading-relaxed text-muted-foreground">{options.description}</div>
            )}
          </div>
        </div>

        {isPrompt && (
          <div className="mt-5">
            {options.input?.label && (
              <label className="mb-1.5 block text-sm font-medium text-foreground">{options.input.label}</label>
            )}
            <input
              autoFocus
              value={value}
              onChange={(e) => {
                setValue(e.target.value)
                setError("")
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter") handleConfirm()
              }}
              placeholder={options.input?.placeholder}
              className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
            {error && <p className="mt-1.5 text-xs text-red-500">{error}</p>}
          </div>
        )}

        <div className="mt-6 flex justify-end gap-3">
          <button
            type="button"
            onClick={() => finish(isPrompt ? null : false)}
            className="h-10 rounded-lg border border-input px-4 text-sm font-medium text-foreground transition-colors hover:bg-accent"
          >
            {options.cancelText || "取消"}
          </button>
          <button
            type="button"
            onClick={handleConfirm}
            className={cn(
              "h-10 rounded-lg px-4 text-sm font-semibold transition-all hover:brightness-110",
              danger ? "bg-red-500 text-white" : "bg-primary text-primary-foreground"
            )}
          >
            {options.confirmText || "确认"}
          </button>
        </div>
      </div>
    </Modal>
  )
}

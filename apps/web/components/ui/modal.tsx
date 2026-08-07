"use client"

import { useEffect, useRef, type ReactNode } from "react"
import { cn } from "@/lib/utils"

interface ModalProps {
  open: boolean
  onClose: () => void
  children: ReactNode
  className?: string
}

/**
 * 通用弹窗组件。
 * - 只有 mousedown + mouseup 都发生在背景蒙层上才会关闭（防止拖拽误关）
 * - 支持 ESC 键关闭
 */
export function Modal({ open, onClose, children, className }: ModalProps) {
  const mouseDownOnBackdrop = useRef(false)

  useEffect(() => {
    if (!open) return
    const handleEsc = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose()
    }
    document.addEventListener("keydown", handleEsc)
    return () => document.removeEventListener("keydown", handleEsc)
  }, [open, onClose])

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 overflow-y-auto bg-black/50"
      onMouseDown={(e) => {
        mouseDownOnBackdrop.current = e.target === e.currentTarget || (e.target as HTMLElement).dataset?.backdrop === "true"
      }}
      onClick={(e) => {
        const target = e.target as HTMLElement
        const isBackdrop = target === e.currentTarget || target.dataset?.backdrop === "true"
        if (isBackdrop && mouseDownOnBackdrop.current) {
          onClose()
        }
        mouseDownOnBackdrop.current = false
      }}
    >
      <div className="flex min-h-full items-center justify-center p-4" data-backdrop="true">
        <div
          className={cn(
            "w-full rounded-xl border border-border bg-card shadow-xl",
            className
          )}
        >
          {children}
        </div>
      </div>
    </div>
  )
}

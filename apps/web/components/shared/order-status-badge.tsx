"use client"

import { cn } from "@/lib/utils"
import type { OrderStatus } from "@/types"
import { useLocale } from "@/lib/context"
import type { TranslationKey } from "@/lib/i18n"

const statusStyles: Record<OrderStatus, string> = {
  PENDING: "bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400",
  PAID: "bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400",
  DELIVERED: "bg-emerald-100 text-emerald-800 dark:bg-emerald-900/30 dark:text-emerald-400",
  COMPLETED: "bg-teal-100 text-teal-800 dark:bg-teal-900/30 dark:text-teal-400",
  EXPIRED: "bg-zinc-100 text-zinc-600 dark:bg-zinc-800 dark:text-zinc-400",
  REFUNDED: "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400",
  PARTIALLY_REFUNDED: "bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400",
}

const statusKeys: Record<OrderStatus, TranslationKey> = {
  PENDING: "status.PENDING",
  PAID: "status.PAID",
  DELIVERED: "status.DELIVERED",
  COMPLETED: "status.COMPLETED",
  EXPIRED: "status.EXPIRED",
  REFUNDED: "status.REFUNDED",
  PARTIALLY_REFUNDED: "status.PARTIALLY_REFUNDED",
}

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  const { t } = useLocale()
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium",
        statusStyles[status]
      )}
    >
      {t(statusKeys[status])}
    </span>
  )
}

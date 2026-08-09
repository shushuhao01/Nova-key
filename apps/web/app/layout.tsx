import React from "react"
import type { Metadata, Viewport } from "next"
import { Inter } from "next/font/google"
import { Toaster } from "sonner"
import { AppProviders } from "@/lib/context"
import { ThemeScript } from "./theme-script"
import { DynamicFavicon } from "@/components/dynamic-favicon"
import "./globals.css"

const inter = Inter({ subsets: ["latin"], variable: "--font-inter" })

export const metadata: Metadata = {
  metadataBase: new URL(process.env.NEXT_PUBLIC_BASE_URL || "http://localhost:3000"),
  title: {
    template: "%s | Nova key",
    default: "Nova key - Auto Card Delivery",
  },
  description: "Automated virtual goods card delivery platform",
  robots: { index: true, follow: true },
  openGraph: {
    type: "website",
    locale: "zh_CN",
    siteName: "Nova key",
  },
  twitter: {
    card: "summary_large_image",
  },
}

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  maximumScale: 5,
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#09090b" },
  ],
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="zh" suppressHydrationWarning>
      <head>
        <ThemeScript />
      </head>
      <body className={`${inter.variable} font-sans antialiased`} suppressHydrationWarning>
        <AppProviders>
          <DynamicFavicon />
          {children}
          <Toaster position="top-center" richColors closeButton />
        </AppProviders>
      </body>
    </html>
  )
}

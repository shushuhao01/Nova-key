import { NextRequest, NextResponse } from "next/server"
import { renderToStaticMarkup } from "react-dom/server"
import { QRCodeSVG } from "qrcode.react"
import React from "react"

/**
 * 二维码图片生成 API Route
 *
 * 路径：/qr-image?url=xxx&size=400
 *
 * 用途：微信内置浏览器长按识别二维码要求图片为真实 HTTP URL（不支持 data URI）。
 * 用已有的 qrcode.react 库生成 SVG，以 <img src="/qr-image?url=xxx"> 加载。
 *
 * 注意：路径不用 /api/ 前缀，因为 Nginx 将 /api/ 代理到后端 8083。
 */
export async function GET(request: NextRequest) {
  const url = request.nextUrl.searchParams.get("url")
  if (!url || url.trim() === "" || url.length > 2048) {
    return NextResponse.json({ error: "invalid url" }, { status: 400 })
  }

  const sizeParam = request.nextUrl.searchParams.get("size")
  const size = Math.max(120, Math.min(Number(sizeParam) || 400, 800))

  const svg = renderToStaticMarkup(
    React.createElement(QRCodeSVG, {
      value: url,
      size,
      level: "M",
      marginSize: 2, // quiet zone，微信长按识别需要二维码周围留白
    })
  )

  // 确保 SVG 有 xmlns 属性（独立 SVG 文件需要）
  const svgWithNs = svg.includes('xmlns="http://www.w3.org/2000/svg"')
    ? svg
    : svg.replace('<svg', '<svg xmlns="http://www.w3.org/2000/svg"')

  return new NextResponse(svgWithNs, {
    headers: {
      "Content-Type": "image/svg+xml; charset=utf-8",
      "Cache-Control": "public, max-age=86400",
    },
  })
}

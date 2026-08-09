import { NextRequest, NextResponse } from "next/server"
import QRCode from "qrcode"

/**
 * 二维码图片生成 API Route
 *
 * 路径：/qr-image?url=xxx&size=400
 *
 * 用途：微信内置浏览器长按识别二维码要求图片为真实 HTTP URL（不支持 data URI）。
 * 用 qrcode npm 包生成 PNG 图片，以 <img src="/qr-image?url=xxx"> 加载。
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

  try {
    const buffer = await QRCode.toBuffer(url, {
      width: size,
      margin: 2, // quiet zone，微信长按识别需要二维码周围留白
      errorCorrectionLevel: "M",
    })

    return new NextResponse(buffer, {
      headers: {
        "Content-Type": "image/png",
        "Cache-Control": "public, max-age=86400",
      },
    })
  } catch (err) {
    console.error("QR code generation failed:", err)
    return NextResponse.json({ error: "generation failed" }, { status: 500 })
  }
}

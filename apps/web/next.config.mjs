// 屏蔽 Node 24+ 的 DEP0060 警告 (util._extend，来自内部依赖，无法从源头修复)
const _origWarn = process.emitWarning
process.emitWarning = function (warning, ...args) {
  if (args[0] === "DeprecationWarning" && args[1] === "DEP0060") return
  if (typeof warning === "object" && warning?.code === "DEP0060") return
  return _origWarn.call(this, warning, ...args)
}

/** @type {import('next').NextConfig} */
const nextConfig = {
  // Docker 部署必须开 standalone（Dockerfile.web 依赖 .next/standalone 的 server.js）。
  // 生产实际用 pm2/宝塔守护 + `next start`，此时开 standalone 会报
  // "next start does not work with output: standalone" 且错误页静态文件 ENOENT 崩溃
  // （.next/server/pages/*.html 不生成）→ 进程反复重启。故按构建环境区分。
  output: process.env.NEXT_OUTPUT_STANDALONE === "1" ? "standalone" : undefined,
  typescript: {
    ignoreBuildErrors: true,
  },
  images: {
    unoptimized: true,
  },
  // 开发模式: 将 /api/* 代理到 Spring Boot 后端（含上传文件 /api/uploads/*）
  async rewrites() {
    const backendUrl = process.env.BACKEND_URL || "http://localhost:8083"
    return [
      {
        source: "/api/:path*",
        destination: `${backendUrl}/api/:path*`,
      },
    ]
  },
}

export default nextConfig

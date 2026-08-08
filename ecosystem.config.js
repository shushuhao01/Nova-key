// ════════════════════════════════════════════════════════════════
// Nova key - PM2 进程管理配置
// ════════════════════════════════════════════════════════════════
// 用法：
//   首次启动 / 服务器重启后： pm2 start ecosystem.config.js
//   日常重启前端：           pm2 restart noepay.cn-web
//   日常重启后端：           pm2 restart noepay.cn-api
//   全部重启：               pm2 restart ecosystem.config.js
//   一键更新：               bash update.sh
//
// 环境变量：通过 bash 先 source 项目根目录 .env 再启动。
// 注意：不用 PM2 的 env_file——实测 PM2 6.0.14 的 env_file 未注入环境变量，
// 导致后端拿默认 DB_URL 连 "db" 库失败、无限崩溃重启（↺N）。
// .env 不提交到 Git（见 .gitignore），部署时由 update.sh 自动创建。
// ════════════════════════════════════════════════════════════════

module.exports = {
  apps: [
    // ── 后端 API（Spring Boot，端口 8083）──
    {
      name: "noepay.cn-api",
      cwd: "/www/wwwroot/nova-key/apps/api",
      script: "bash",
      args: [
        "-c",
        "set -a; source /www/wwwroot/nova-key/.env; set +a; exec java -Xms512m -Xmx1280m -Duser.timezone=Asia/Shanghai -jar target/nova-key-1.0.0-SNAPSHOT.jar --server.port=8083",
      ],
      autorestart: true, // 崩溃自动重启
      // 内存超限自动重启。上限必须高于 JVM 堆(-Xmx1280m)+堆外开销，否则内存一涨就重启，
      // 表现为"网站久不久崩一下"。服务器 7G 内存，给后端 2G 余量，前端 1536M。
      // 注意: PM2 的 max_memory_restart 只接受整数 (如 2G/1536M)，不接受小数 (1.5G 会校验失败)。
      max_memory_restart: "2G",
      time: true, // 日志带时间戳
      out_file: "/www/wwwroot/nova-key/logs/api.out.log",
      error_file: "/www/wwwroot/nova-key/logs/api.err.log",
      merge_logs: true,
      max_size: "50M", // 单个日志文件超过 50M 自动轮转（避免日志爆满磁盘）
      retain: 5, // 保留最近 5 份轮转日志
      kill_timeout: 15000,
    },

    // ── 前端（Next.js，端口 3001）──
    {
      name: "noepay.cn-web",
      cwd: "/www/wwwroot/nova-key/apps/web",
      script: "bash",
      args: [
        "-c",
        "set -a; source /www/wwwroot/nova-key/.env; set +a; exec node node_modules/next/dist/bin/next start -p 3001",
      ],
      autorestart: true,
      max_memory_restart: "1536M",
      time: true,
      out_file: "/www/wwwroot/nova-key/logs/web.out.log",
      error_file: "/www/wwwroot/nova-key/logs/web.err.log",
      merge_logs: true,
      max_size: "50M", // 单个日志文件超过 50M 自动轮转（避免日志爆满磁盘）
      retain: 5, // 保留最近 5 份轮转日志
    },
  ],
}

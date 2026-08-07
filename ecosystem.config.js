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
// 环境变量统一从项目根目录 .env 读取（PM2 env_file），
// .env 不提交到 Git（见 .gitignore），部署时由 update.sh 自动创建。
// ════════════════════════════════════════════════════════════════

module.exports = {
  apps: [
    // ── 后端 API（Spring Boot，端口 8083）──
    {
      name: "noepay.cn-api",
      cwd: "/www/wwwroot/nova-key/apps/api",
      script: "java",
      args:
        "-Duser.timezone=Asia/Shanghai -jar target/nova-key-1.0.0-SNAPSHOT.jar --server.port=8083",
      env_file: "/www/wwwroot/nova-key/.env",
      autorestart: true, // 崩溃自动重启
      max_memory_restart: "768M", // 内存超限自动重启
      time: true, // 日志带时间戳
      out_file: "/www/wwwroot/nova-key/logs/api.out.log",
      error_file: "/www/wwwroot/nova-key/logs/api.err.log",
      merge_logs: true,
      kill_timeout: 15000,
    },

    // ── 前端（Next.js，端口 3001）──
    {
      name: "noepay.cn-web",
      cwd: "/www/wwwroot/nova-key/apps/web",
      script: "node",
      args: "node_modules/next/dist/bin/next start -p 3001",
      env_file: "/www/wwwroot/nova-key/.env",
      env: {
        BACKEND_URL: "http://127.0.0.1:8083",
      },
      autorestart: true,
      max_memory_restart: "768M",
      time: true,
      out_file: "/www/wwwroot/nova-key/logs/web.out.log",
      error_file: "/www/wwwroot/nova-key/logs/web.err.log",
      merge_logs: true,
    },
  ],
}

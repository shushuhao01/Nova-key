# Nova key 维护文档

> 面向线上运行后的日常运维：备份恢复、升级发布、日志与监控、故障排查、安全维护。
> 建议配合 [DEPLOYMENT.md](DEPLOYMENT.md)（部署文档）阅读。

---

## 1. 架构速览

```
浏览器 → Nginx(443, HTTPS) → Next.js(3000) ──/api 代理──→ Spring Boot(8083) → PostgreSQL(5432)
                                     └──────────────→ 微信/支付宝/易支付/USDT 网关(HTTPS 回调)
```

- **后端**：Spring Boot 3.4，端口 `8083`，context-path `/api`
- **前端**：Next.js 16，端口 `3000`
- **数据库**：PostgreSQL `nova_key`
- **上传目录**：`./uploads`（卡密导入模板、站点 logo 等）

---

## 2. 日常维护任务

### 2.1 数据库备份（必须定时）

PostgreSQL 备份（宝塔或 crontab 每日执行）：

```bash
# 每日 02:30 备份到 /backup/nova_key
30 2 * * * PGPASSWORD='<数据库密码>' pg_dump -U novakey -h 127.0.0.1 -d nova_key -F c -f /backup/nova_key_$(date +\%F).dump
# 保留最近 14 天
find /backup -name 'nova_key_*.dump' -mtime +14 -delete
```

恢复：

```bash
PGPASSWORD='<数据库密码>' pg_restore -U novakey -h 127.0.0.1 -d nova_key --clean --if-exists /backup/nova_key_2026-08-07.dump
```

> 卡密、订单、用户是核心数据，**建议同时异地备份**（对象存储/另一台机器）。

### 2.2 上传目录备份

```bash
rsync -avz /www/wwwroot/nova-key/uploads/ /backup/uploads/
```

### 2.3 站点配置与支付渠道

- 站点名称、页脚、公告等在「管理后台 → 站点配置」维护。
- 支付渠道在「管理后台 → 支付渠道」维护，敏感字段（私钥/密钥）保存时自动脱敏显示。
- 原生微信/支付宝回调地址由 `APP_BASE_URL` 自动生成；**变更域名后需同步更新微信商户平台 / 支付宝开放平台中的回调配置**。

---

## 3. 升级发布

### 3.1 从 Git 拉取更新

```bash
cd /www/wwwroot/nova-key
git pull
# 后端
cd apps/api && mvn -q package -DskipTests
# 重启后端进程（Supervisor/PM2 或 kill 后重启 java -jar）
# 前端
cd ../web && pnpm install && BACKEND_URL=http://127.0.0.1:8083 pnpm build
# 重启前端进程
```

### 3.2 升级前

1. 执行 `pg_dump` 完整备份数据库；
2. 阅读本次发布的 `docs/DEPLOYMENT.md` 变化与数据库变更说明；
3. 测试环境验证后再上生产。

### 3.3 数据库结构变更

`spring.jpa.hibernate.ddl-auto: update` 会自动加列/建表，但**不会删除字段或改类型**。
涉及破坏性变更时，按发布说明手动执行迁移 SQL，并先备份。

---

## 4. 日志与监控

### 4.1 日志位置

- 后端：`nohup ... > logs/api.log`（或 Supervisor 输出），也可在宝塔「日志」查看。
- 前端：Next.js 启动日志；`pm2 logs` 或 supervisor 日志。
- 关键事件日志：
  - 支付回调：搜索 `webhook` / `wxpay` / `alipay` / `settleByActiveQuery`
  - 发货：搜索 `deliver` / `Delivered` / 卡密发放
  - 定时任务：订单过期 `Order expired`、查单节流清理

### 4.2 健康检查

```bash
curl -s http://127.0.0.1:8083/api/health
```

建议用监控（宝塔监控 / Uptime Kuma / 阿里云云监控）定时探测，异常告警。

### 4.3 常用排查命令

```bash
# 端口与进程
ss -lntp | grep -E '8083|3000'
ps aux | grep -E 'nova-key|next'

# 数据库连接
PGPASSWORD='<密码>' psql -U novakey -h 127.0.0.1 -d nova_key -c '\dt'
```

---

## 5. 故障排查手册

### 5.1 订单支付成功但状态未更新

1. 看后端日志是否有 webhook 报错（验签失败/金额不符/重复回调）；
2. 确认 `APP_BASE_URL` 与微信商户平台/支付宝配置的回调地址一致且公网可达；
3. 后端有**主动查单兜底**：前端支付页轮询 `GET /orders/{id}/status` 时，服务端会按节流
   （每订单 20 秒）主动查询微信/支付宝网关，已支付会自动标记并发货；
4. 仍失败可在后台手动将订单标记为已支付。

### 5.2 卡密未发货

1. 确认订单状态为已支付（`SELECT status FROM orders WHERE id='...'`）；
2. 确认该商品有可用库存（`card_keys.status='AVAILABLE'`）；
3. 查看 `deliver` 相关日志；邮件未收到时检查 `MAIL_ENABLED` 与 SMTP 配置，
   或让用户登录后通过「订单查询」获取卡密（查询接口不依赖邮件）。

### 5.3 支付回调验签失败

- 微信：确认渠道配置的 APIv3 密钥、证书序列号、商户私钥正确；确认商户号与 `mchid` 一致。
- 支付宝：确认应用私钥/支付宝公钥正确、密钥与平台展示的指纹匹配。

### 5.4 前端白屏 / 502

- Nginx 是否指向正确的 3000 端口；
- Next.js 进程是否存活；`BACKEND_URL` 是否指向后端 8083；
- 查看前端日志是否有 SSR 错误。

### 5.5 时间偏差

订单过期判断、支付超时、支付宝签名时间戳均依赖北京时间（微信签名用 Unix 时间戳，无时区问题）。
确认进程时区与启动参数：

```bash
date +%Z                          # 应输出 CST 或 +0800
ps -ef | grep java                # 确认启动命令含 -Duser.timezone=Asia/Shanghai
```

---

## 6. 安全维护

- 默认管理员 `admin/admin123`：**上线后立即改密**；生产建议 `PASSWORD_PLAIN=false`（按部署文档 2.5 流程切换）。
- `JWT_SECRET`：使用随机密钥；泄露后**所有登录态可被伪造**，务必妥善保管。
- 环境变量 `.env`：不入 Git（已在 `.gitignore` 中排除），服务器上权限收紧 `chmod 600 .env`。
- 支付私钥：只存服务器，不外传；后台脱敏展示。
- 定期更新：关注上游 [Orion Key](https://github.com/RivenLau/orion-key) 的安全修复与本项目 CI 状态。
- 风控：后台「风控配置」可调登录防爆破、下单限流、IP 限流阈值。

---

## 7. 数据速查

| 表 | 说明 |
|----|------|
| `orders` / `order_items` | 订单与明细 |
| `card_keys` | 卡密（`AVAILABLE`/`LOCKED`/`SOLD`/`INVALID`） |
| `payment_channels` | 支付渠道配置（config_data 含商户密钥） |
| `webhook_events` | 支付回调幂等记录（验签成功事件的去重依据） |
| `site_configs` | 站点/风控/货币配置（key-value） |
| `users` | 用户与管理员 |

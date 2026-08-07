# Nova key 部署文档

> 基于开源 Orion Key 二开的自动发卡平台。本文档覆盖：宝塔面板部署（重点）、
> Docker 部署、非 Docker 部署、支付渠道配置与上线检查清单。

---

## 1. 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | **22+**（推荐 22 或更高） | 后端运行环境（Spring Boot 3.4 要求 22+，用 21 无法按 `--release 22` 编译） |
| Maven | 3.9+ | 后端构建工具 |
| Node.js | 20+ | 前端运行环境 |
| pnpm | 9+ | 前端包管理（`npm i -g pnpm`） |
| PostgreSQL | 18+ | 数据库（**与 MySQL 无关，独立使用**） |
| Nginx | 任意 | 反向代理 + HTTPS（宝塔自带） |
| Docker | 可选 | 仅使用 Docker 部署方式 / USDT 的 BEpusdt 时安装 |

> **重要**：本项目使用 PostgreSQL。如果你的服务器已有 MySQL（例如其他项目在用），
> 互不影响——只需为 Nova key 单独创建 PostgreSQL 数据库与账号即可。

---

## 2. 宝塔面板部署（推荐）

> 本章面向"服务器上已有其他生产项目（如 Node.js + MySQL 的 CRM）"的场景。
> **结论：可以安全共存，互不影响。** 原因：
>
> 1. **技术栈隔离**：其他项目（Node.js/MySQL）与 Nova key（Java 22/PostgreSQL）
>    使用完全不同的运行时和数据库，资源互不冲突。
> 2. **数据库隔离**：PostgreSQL 与 MySQL 是两套独立的数据库服务，各自建库互不可见。
> 3. **端口隔离**：Nova key 后端默认 `8083`、前端默认 `3000`，均可按需修改；
>    Nginx 通过**不同域名 + 不同 server 块**分别反代两个项目。
> 4. **目录隔离**：建议部署目录 `/www/wwwroot/nova-key`，与其他项目完全独立。

### 2.1 安装环境（宝塔软件商店）

1. **Java 22+**：宝塔「软件商店 → Java 项目部署」若无 22+，可：
   - 使用宝塔的 OpenJDK 17 + 手动更换 JDK 22（下载 temurin 22 解压，修改 `JAVA_HOME`）；或
   - 直接用高版本 JDK（如 22/23/25）均可编译运行本项目。
   - 检查：`java -version`（需 22 及以上）。
2. **Maven**：`d:/Projects/.../.tools/apache-maven-3.9.9` 或服务器下载 apache-maven-3.9+ 解压，配 `MAVEN_HOME`。
3. **Node.js 20+**：宝塔「软件商店 → Node.js」安装 ≥20，并全局安装 pnpm：
   ```bash
   npm i -g pnpm
   ```
4. **PostgreSQL 18+**：宝塔「软件商店 → PostgreSQL」安装，创建数据库与用户：
   ```sql
   CREATE USER novakey WITH PASSWORD '<强密码>';
   CREATE DATABASE nova_key OWNER novakey;
   GRANT ALL PRIVILEGES ON DATABASE nova_key TO novakey;
   ```
5. **Nginx**：宝塔自带，创建站点后配置反代（见 2.4）。

### 2.2 获取代码与构建后端

```bash
cd /www/wwwroot
git clone https://github.com/shushuhao01/Nova-key.git nova-key
cd nova-key/apps/api

# 配置环境变量后编译
export JAVA_HOME=/path/to/jdk-22
mvn -q clean package -DskipTests
# 产物：target/nova-key-1.0.0-SNAPSHOT.jar
```

### 2.3 后端运行（宝塔 PM2 / 系统服务）

推荐用宝塔「Supervisor 进程守护」或 systemd 运行 jar，并写入以下环境变量：

```ini
# 数据库（必须）
DB_URL=jdbc:postgresql://127.0.0.1:5432/nova_key
DB_USERNAME=novakey
DB_PASSWORD=<数据库密码>

# 公网地址（原生微信/支付宝回调地址自动生成，必须）
APP_BASE_URL=https://你的域名

# JWT（必须，随机生成：openssl rand -base64 48）
JWT_SECRET=<随机密钥>

# 密码模式：生产建议先保持 true 登录一次，后台改密后再切 false（见 2.5）
PASSWORD_PLAIN=true

# 邮件（可选，配置后发货自动发邮件）
MAIL_ENABLED=false
MAIL_HOST=smtp.example.com
MAIL_PORT=465
MAIL_USERNAME=your@email.com
MAIL_PASSWORD=你的SMTP授权码
MAIL_SITE_URL=https://你的域名

# 时区（必须，否则订单时间偏差 8 小时；同时影响支付宝签名时间戳，偏差超 5 分钟会被拒绝）
TZ=Asia/Shanghai
```
> **为什么必须北京时间**：微信支付签名用 Unix 时间戳（与时区无关，天然正确）；
> 支付宝签名中的 `timestamp` 为 `yyyy-MM-dd HH:mm:ss` 格式、按 JVM 时区生成，
> 若服务器时区不是 `Asia/Shanghai`，时间戳偏差超 5 分钟会被支付宝直接拒单。
> 因此除 `TZ` 环境变量外，启动命令还需显式加 `-Duser.timezone=Asia/Shanghai`（见下）。

启动命令：

```bash
nohup java -Duser.timezone=Asia/Shanghai -jar target/nova-key-1.0.0-SNAPSHOT.jar --server.port=8083 > logs/api.log 2>&1 &
```

> **端口冲突检查**：若 8083/3000 被占用（`ss -lntp | grep -E '8083|3000'`），
> 修改 `--server.port` 与前端启动端口即可，Nginx 反代地址同步调整。

首次启动后执行初始化 SQL（写入管理员 `admin` 与站点配置）：

```bash
cd /www/wwwroot/nova-key/apps/api/src/main/resources
psql -U novakey -d nova_key -h 127.0.0.1 -f data.sql
```

### 2.4 前端构建与运行 + Nginx

```bash
cd /www/wwwroot/nova-key/apps/web
pnpm install
# 前端 SSR 调用后端地址（本机直接跑后端时用 127.0.0.1）
BACKEND_URL=http://127.0.0.1:8083 pnpm build
pnpm start -- --port 3000   # 或 nohup pnpm start -- --port 3000 &
```

> 前端 `next.config.mjs` 已配置 `/api/*` rewrites 到 `BACKEND_URL`，浏览器请求 `/api` 由 Next.js 转发到后端，**Nginx 只需反代到 3000 即可**。

Nginx 站点配置（宝塔「网站 → 添加站点」后编辑配置文件）：

```nginx
server {
    listen 443 ssl http2;
    server_name 你的域名;

    ssl_certificate     /www/server/panel/vhost/cert/你的域名/fullchain.pem;
    ssl_certificate_key /www/server/panel/vhost/cert/你的域名/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

> **必须**为域名配置 HTTPS（微信/支付宝回调要求 https）。宝塔可一键申请 Let's Encrypt 证书。

### 2.5 上线安全配置

1. **登录改密**：用 `admin / admin123` 登录后台，立即修改密码。
2. **切换 BCrypt（可选但推荐）**：修改所有用户密码后，将 `PASSWORD_PLAIN=false` 重启。
   ⚠️ 注意：`data.sql` 中 admin 是**明文**密码；若直接切 `false` 会导致明文密码无法登录。
   正确流程：先 `true` 启动 → 后台改密（存储为 BCrypt）→ 重启改为 `false`。
3. **配置支付渠道**：后台「支付渠道」添加/编辑原生微信、支付宝，填入真实商户信息
   （回调地址由系统根据 `APP_BASE_URL` 自动生成，无需手动填写）。
4. **修改 `JWT_SECRET`**：确认使用随机密钥，不要使用默认值。
5. **关闭 DEBUG 日志**（可选）：`application.yml` 中 `logging.level.com.orionkey` 由 `DEBUG` 改为 `INFO`。

---

## 3. Docker 部署

仓库根目录 `docker-compose.yml` 编排 **api / web / bepusdt**（不含 PostgreSQL 与 Nginx，需自行准备）。

```bash
cp .env.example .env
# 编辑 .env：DB_URL / DB_USERNAME / DB_PASSWORD / APP_BASE_URL / JWT_SECRET ...
docker compose build     # 或镜像发布 GHCR 后 docker compose pull
docker compose up -d
docker compose logs -f
```

> - 容器时区已固定 `TZ=Asia/Shanghai`。
> - 上传文件挂载 `./uploads` 持久化。
> - 前置 Nginx 反向代理：`domain → 127.0.0.1:3000`，`/api` 由 Next.js 转发到 `http://api:8083`（Compose 网络内互通）。

---

## 4. 支付渠道配置

### 4.1 原生微信支付（APIv3）

后台「支付渠道管理 → 原生微信支付」填写：

| 字段 | 获取位置 | 必填 |
|------|---------|------|
| 应用 AppID | 微信公众平台 / 开放平台 | 是 |
| 商户号 (MchID) | 微信支付商户平台 | 是 |
| APIv3 密钥 | 商户平台 → API 安全 → APIv3 密钥 | 是 |
| 证书序列号 | 商户平台 → API 安全 → API 证书 | 是 |
| 商户私钥 | `apiclient_key.pem` 内容（或服务器文件路径） | 是 |
| 回调地址 | **系统自动生成**（`{APP_BASE_URL}/api/payments/webhook/wxpay`），只读 | — |

需在微信商户平台「产品中心 → Native 支付」开通，并配置回调域名。

### 4.2 原生支付宝

后台「支付渠道管理 → 原生支付宝」填写：

| 字段 | 获取位置 | 必填 |
|------|---------|------|
| 应用 AppID | 支付宝开放平台 | 是 |
| 应用私钥 | 应用私钥（RSA2，可含 PEM 头） | 是 |
| 支付宝公钥 | 支付宝开放平台提供的公钥 | 是 |
| 回调地址 | **系统自动生成**（`{APP_BASE_URL}/api/payments/webhook/alipay`），只读 | — |

需在支付宝开放平台开通「当面付」；应用设置中把 **异步通知地址** 填为自动生成的回调地址。

### 4.3 支付链路验证（上线必做）

支付回调链路：下单 → 用户扫码/跳转支付 → 平台异步回调 → **验签 → 幂等 → 金额校验 →
服务端主动查单二次确认 → 标记已支付 → 自动发货（发放卡密 + 邮件）**。

用 **1 分钱商品**做一次真实支付，验证：
1. 微信扫码支付成功、支付宝付款成功；
2. 支付后订单状态自动变为已支付，卡密立即发放；
3. 前端支付页轮询能拿到状态（回调丢失时由主动查单兜底，每 20 秒查询一次网关）。

---

## 5. 上线检查清单

- [ ] `APP_BASE_URL` 设置为 https 域名，回调地址可公网访问（微信/支付宝必须）
- [ ] PostgreSQL 已建库建用户，`data.sql` 已执行
- [ ] `JWT_SECRET` 已替换为随机密钥
- [ ] 管理员已登录并修改默认密码（或 `PASSWORD_PLAIN=false` 已按 2.5 流程切换）
- [ ] 原生微信/支付宝渠道已填入真实商户信息并启用
- [ ] 后端端口 8083、前端端口 3000 未被其他项目占用（`ss -lntp` 确认）
- [ ] Nginx HTTPS 已配置，`/api/health` 返回正常
- [ ] 1 分钱真实支付联调通过，发货正常
- [ ] 上传目录 `./uploads`（或 Docker volume）已持久化并备份

---

## 6. 常见问题

| 问题 | 排查 |
|------|------|
| 支付后订单未变已支付 | 检查后端日志 webhook 验签/查单记录；确认 `APP_BASE_URL` 域名与商户平台配置一致；确认回调地址公网可达 |
| 下单报"渠道缺少必填配置" | 后台渠道配置未填全，或渠道未启用 |
| 时间差 8 小时 | 确认进程时区 `TZ=Asia/Shanghai` |
| 端口冲突 | `ss -lntp` 查看占用，改 `server.port` / 前端 `--port` |
| `PASSWORD_PLAIN=false` 后登录失败 | 需先 `true` 启动登录并在后台改密，再切 `false`（见 2.5） |
| 邮件不发送 | 检查 `MAIL_ENABLED`、SMTP 授权码、`MAIL_SITE_URL` |

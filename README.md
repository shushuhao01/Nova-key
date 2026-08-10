<div align="center">

# Nova key

**自动化数字商品（卡密）发卡平台 · 基于开源 Orion Key 二次开发**

Automated Digital Goods Delivery Platform (Fork of Orion Key)

[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-22-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen?logo=springboot)
![Next.js](https://img.shields.io/badge/Next.js-16-black?logo=next.js)
![React](https://img.shields.io/badge/React-19-61dafb?logo=react)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18+-336791?logo=postgresql&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.7-3178c6?logo=typescript&logoColor=white)
![Tailwind CSS](https://img.shields.io/badge/Tailwind%20CSS-3.4-38bdf8?logo=tailwindcss&logoColor=white)
![pnpm](https://img.shields.io/badge/pnpm-9+-f69220?logo=pnpm&logoColor=white)

简体中文 | [English](README.en.md)

</div>

---

## 项目声明

> **Nova key** 是基于开源项目 [Orion Key](https://github.com/RivenLau/orion-key)（MIT License）的二次开发版本。
> 在原项目基础上实现了**原生微信支付（APIv3）与原生支付宝支付**、**客户管理**、**营销与优惠券**、
> **消息通知**（钉钉/企业微信/邮件）、**RBAC 权限系统**（员工与角色）、**分销推广**（一级/二级佣金）
> 与**站内消息中心**（铃铛）等能力，
> 并对品牌、部署文档等进行了定制。
> 原项目版权归原作者所有，本项目按 MIT License 开源。

| | 地址 |
|---|---|
| 🐙 **本项目仓库** | <a href="https://github.com/shushuhao01/Nova-key" target="_blank" rel="noopener noreferrer">https://github.com/shushuhao01/Nova-key</a> |
| ⭐ **原项目仓库** | <a href="https://github.com/RivenLau/orion-key" target="_blank" rel="noopener noreferrer">https://github.com/RivenLau/orion-key</a> |
| 🔑 **默认管理员账号** | `admin` / `admin123`（首次登录后请立即修改，绑定内置「超级管理员」角色） |

---

## 核心特性

|  |  |
|---|---|
| 🛒 **自动发卡** — 下单支付后自动发放卡密，零人工干预 | 🎨 **主题切换** — 支持亮色/暗色模式，多主题色自由切换 |
| 📦 **商品管理** — 分类、上下架、库存、批量导入卡密、展示视频 | 🔒 **安全认证** — JWT 无状态认证 + BCrypt 加密 |
| 💳 **多支付渠道** — 原生微信支付 / 原生支付宝 / 易支付 / USDT | 🛡️ **风控系统** — IP 限流、登录防爆破、订单防刷 |
| 📊 **管理后台** — 仪表盘数据概览、订单/站点全面管理 | 🔍 **订单追踪** — 订单号查询卡密，支持游客和会员 |
| 🛍️ **购物车** — 多商品合并下单，提升购买体验 | ⚙️ **站点配置** — 公告、弹窗、维护模式，后台一键开关 |
| 👥 **客户管理** — 注册客户 + 匿名客户聚合，消费统计与封禁 | 📣 **营销管理** — 邮件营销活动 + 优惠券（全商品/指定商品，下单核销） |
| 📈 **分销推广** — 一级/二级分销、阶梯佣金、微信零钱提现 | 🧾 **订单退款** — 后台全额/部分退款，微信支付原路退回 |
| 🔔 **消息中心** — 前台/后台消息铃铛，站内通知自动触达 | 🧑‍💼 **系统管理 (RBAC)** — 内部员工与角色权限，菜单/接口双控 |

---

## 支付渠道集成

| 渠道 | 接入方式 | 说明 |
|------|---------|------|
| 微信支付 | **原生接入（APIv3）** | Native 扫码下单 + 移动端 H5 拉起微信 App（后台开关、失败自动回退二维码），回调验签 + 主动查单兜底，**退款原路退回** |
| 支付宝 | **原生接入** | 直接对接支付宝开放平台，PC 当面付扫码 / 移动端 H5 跳转 |
| 支付宝 | 易支付集成 | 通过第三方易支付平台接入 |
| 微信支付 | 易支付集成 | 通过第三方易支付平台接入 |
| USDT (TRC-20) | BEpusdt 自托管 | 链上自动确认，无第三方托管 |

> **原生微信/支付宝回调地址由系统自动生成**：部署时配置环境变量 `APP_BASE_URL`
> 后，回调地址自动拼接为 `{APP_BASE_URL}/api/payments/webhook/wxpay` 与 `/alipay`，
> 无需在后台手动填写。

---

## 管理后台功能

后台入口 `/admin`，登录账号须拥有后台访问权限。模块总览：

| 模块 | 功能 |
|------|------|
| 📊 仪表盘 | 今日/本月销售额与订单数、支付转化率、流量、低库存预警 |
| 🗂️ 分类管理 | 商品分类的增删改、排序 |
| 📦 商品管理 | 商品 CRUD、规格/批发价、上下架、库存阈值、详情（Markdown）、展示视频 |
| 🔑 卡密管理 | 批量导入、库存汇总、卡密查询/导出 |
| 🧾 订单管理 | 订单列表/详情、状态流转（支付/发货/过期/自动完成）、手动补发卡密、全额/部分退款（微信原路退回） |
| 👥 客户管理 | 注册客户与匿名客户聚合、消费统计、客户详情（订单时间线）、封禁/解禁 |
| 📈 分销推广 | 分销员审核与自定义比例、商品佣金配置、佣金记录、提现审核（微信转账）、规则参数配置 |
| 📣 营销管理 | 邮件营销活动（受众筛选）、优惠券创建与核销统计 |
| 🔔 消息通知 | 钉钉/企业微信机器人、通知邮箱，模板化自动触发，测试发送 |
| 💳 支付渠道 | 原生微信/支付宝、易支付、USDT 渠道配置，参数校验与测试连接 |
| 🛡️ 风控管理 | IP 限流、登录防爆破、订单防刷阈值 |
| 🔎 TXID 审核 | USDT 交易审核记录，自动/人工审核（驳回可退款） |
| 📜 操作日志 | 后台关键操作审计（登录、下单、发货、支付配置等） |
| 🧑‍💼 系统管理 | 内部员工（创建/改密/禁用/删除/详情）与角色权限配置（RBAC） |

---

## RBAC 权限系统

> 用于管理**内部员工**（管理员、客服等）与后台访问权限；前台注册客户（role=`USER`）不参与权限体系。

### 模型

- **用户**（`users` 表）：`role` 枚举 `USER` / `STAFF` / `ADMIN`，其中 `ADMIN` 为内置超级管理员，
  `STAFF` 通过 `role_id` 关联角色获得权限。
- **角色**（`system_roles` 表）：`code` 唯一、`permissions`（JSON 权限码数组）、`is_system` 内置标记。
- **权限码**：后台菜单与接口鉴权的最小单位。

### 权限码清单

| 权限码 | 对应后台模块 |
|--------|-------------|
| `BACKEND_ACCESS` | 后台访问总开关（未勾选则无法进入后台） |
| `DASHBOARD` | 仪表盘 |
| `CATEGORY_MANAGE` / `PRODUCT_MANAGE` / `CARDKEY_MANAGE` | 分类 / 商品 / 卡密 |
| `ORDER_MANAGE` | 订单管理 |
| `CUSTOMER_MANAGE` | 客户管理 |
| `MARKETING_MANAGE` | 营销管理 |
| `PAYMENT_MANAGE` | 支付渠道 |
| `SITE_CONFIG_MANAGE` | 网站设置 |
| `RISK_MANAGE` / `TXID_REVIEW` / `LOG_VIEW` | 风控 / USDT 审核 / 操作日志 |
| `SYSTEM_MANAGE` | 系统管理（员工与角色） |

### 鉴权链路

- 后端：`/admin/**` 要求 `BACKEND_ACCESS`，`/admin/system/**` 额外要求 `SYSTEM_MANAGE`；
  JWT 过滤器对后台请求实时校验用户状态（禁用/角色变更立即生效）并注入动态权限码。
- 前端：侧边栏菜单按权限码过滤，无权菜单不展示。
- 内置「超级管理员」角色（`SUPER_ADMIN`）拥有全部权限，不可修改/删除；绑定该角色的用户即 `ADMIN`。
- 保护规则：不能修改/禁用/删除自己的账号；内置管理员不可被禁用或删除；已被员工绑定的角色不可删除。

---

## 客户管理

| 视图 | 说明 |
|------|------|
| 统计概览 | 注册客户数、匿名客户数、今日/本月新增、成交/未成交客户数 |
| 注册客户 | `role=USER` 的注册用户：搜索、消费统计（订单数/成交数/累计消费）、详情、封禁/解禁 |
| 匿名客户 | 按邮箱聚合的未注册买家：订单数、累计消费、首末次购买时间，可下钻订单明细 |

> 注册客户与匿名客户按订单统计，同一邮箱注册前后消费分开统计，口径统一为「非内部员工」。

---

## 营销与优惠券

### 营销活动（邮件）

- 受众：全部用户 / 指定用户 ID / 指定邮箱列表。
- 支持 HTML 模板（`{{username}}`、`{{coupon_code}}` 等变量），发送后统计送达数。

### 优惠券

| 配置项 | 说明 |
|--------|------|
| 类型 | 满减（AMOUNT） / 折扣（PERCENT） |
| 门槛 / 有效期 | 最低消费金额、生效与截止时间 |
| **适用范围** | `ALL` 全部商品通用 / `SPECIFIC` 仅指定商品可用 |
| 发放 | 系统生成领取码（`coupon_code`），限量领取，后台核销统计 |

- 下单页输入核销码即时校验并给出**具体原因提示**：未领取 / 已使用 / 未生效 / 已过期 /
  未达门槛 / 不适用当前商品等；校验通过后下单金额自动抵扣。
- 安全：并发领取防超发（行级锁）；优惠券抵扣后实付金额须大于 0，避免 0 元订单。

---

## 消息通知

| 渠道 | 说明 |
|------|------|
| 钉钉机器人 | Webhook + 加签（secret） |
| 企业微信机器人 | Webhook |
| 通知邮箱 | SMTP 发送 |

- 预设模板（新订单、支付成功、低库存等），后台可勾选启用、绑定渠道、开启自动触发。
- 支持单渠道测试发送与逐项测试结果展示。

---

## 商品视频

商品支持上传展示视频，前台商品详情页图文视频结合展示，提升转化。

- 后台商品编辑页上传视频文件，商品实体 `video_url` 保存地址
- 大视频上传受 Nginx `client_max_body_size 100m` 与 API 代理超时（`proxy_read_timeout 300s`）保护
- 前台商品详情页视频与图片轮播共存，移动端自适应播放

---

## 分销推广

分销推广系统，支持一级/二级分销、阶梯佣金与微信零钱提现，防退款套佣。

### 前台（分销中心）

入口：个人中心 →「分销中心」（`/my/distribution`）。

- **分销员**：在线申请、审核状态展示、专属推广员 ID
- **推广商品**：单商品 / 整店生成推广链接与二维码，分享即赚佣金
- **数据概览**：累计佣金、可提现余额、待结算、已提现金额、下级分销员数量
- **佣金记录**：按待结算 / 已结算 / 已取消筛选
- **提现记录**：提现申请、审核、微信转账状态跟踪；绑定本人微信收款
- **分销规则**：一键查看完整规则（佣金、二级抽成、结算、提现、阶梯等说明）

### 后台（分销推广管理）

- **概览**：分销员总数、待审核、累计佣金、待结算、本月新增、趋势图
- **分销员管理**：审核 / 拒绝 / 禁用 / 解禁，设置自定义佣金比例与下级抽成比例
- **商品佣金**：按商品设置专属佣金比例，或排除商品不参与分销
- **佣金记录**：按分销员 / 状态 / 时间筛选，明细可追溯
- **提现审核**：审核通过自动发起**微信商家转账**到零钱（兜底查询 + 超时自动撤销），支持线下结算；审核拒绝自动退回冻结余额
- **规则配置**：总开关、默认佣金比例、二级抽成比例、结算延迟天数、提现门槛/手续费、客户保护期、阶梯佣金档位

### 佣金机制

| 机制 | 说明 |
|------|------|
| 佣金基数 | 订单实际付款金额（扣除优惠券、积分抵扣后的实付金额） |
| 比例优先级 | 分销员自定义比例 > 商品专属比例 > 全局默认比例 |
| 二级分销 | 一级分销员按下级佣金的一定比例（`sub_rate`）抽成，层级最多两级 |
| 阶梯佣金 | 同一客户第 N 次购买按档位比例（占标准佣金百分比）返佣，超出最高档不再返佣 |
| 佣金结算 | 订单发货 24h 无操作自动完成（定时任务）；订单完成 + N 天（结算延迟）后佣金入可提现余额 |
| 退款取消 | 订单退款自动取消佣金：待结算直接取消、已结算从余额扣回、已提现标记待追回 |
| 客户绑定 | 客户首次通过推广链接进入即绑定，保护期内订单归属原推广员，期满可重新绑定 |

### 订单状态机

```
PENDING（待支付）→ PAID（已支付）→ DELIVERED（已发货）→ COMPLETED（已完成，发货 24h 自动完成）
                                    └→ REFUNDED（全额退款） / PARTIALLY_REFUNDED（部分退款）
```

管理后台可对已支付 / 已发货 / 已完成的**微信支付**订单发起全额或部分退款（需填写退款原因），退款原路退回，累计退款金额不超过订单实付金额；已退款订单可一键筛选。

---

## 消息中心（铃铛）

站内消息系统，前后台均提供铃铛入口，订单 / 分销 / 系统事件自动触达。

| 入口 | 说明 |
|------|------|
| 前台铃铛 | 顶部导航未读角标 → `/my/messages` 消息中心：按订单 / 分销 / 系统分类筛选，单条已读、全部已读 |
| 后台铃铛 | 右上角未读角标 → 下拉消息列表：查看详情、标记已读、一键清空 |

- 自动触达事件：订单支付、发货、**退款**、分销佣金结算、提现审核结果等
- 消息按分类（`ORDER` / `DISTRIBUTION` / `SYSTEM` / `USER`）独立管理，未读数实时展示

---

## 技术架构

| 层级 | 技术栈 |
|------|--------|
| **前端** | Next.js 16 · React 19 · TypeScript · Tailwind CSS 3 · shadcn/ui |
| **后端** | Spring Boot 3.4 · Java 22 · Spring Data JPA · Spring Security |
| **数据库** | PostgreSQL 18+ |
| **认证** | JWT (jjwt) · BCrypt |
| **构建** | pnpm (前端) · Maven (后端) |

### Monorepo 目录结构

> 基于 pnpm workspaces 的 Monorepo 架构，前后端统一管理。

```
nova-key/
├── apps/
│   ├── web/                          # Next.js 前端
│   │   ├── app/
│   │   │   ├── (store)/              # 前台路由组（首页、商品、购物车、订单、支付…）
│   │   │   └── admin/                # 管理后台路由组（仪表盘、商品/卡密/订单、客户/营销/系统管理…）
│   │   ├── features/                 # 业务功能模块
│   │   ├── services/                 # API 调用层（统一封装后端接口）
│   │   ├── hooks/                    # 自定义 React Hooks
│   │   ├── components/               # 通用 UI 组件（shadcn/ui）
│   │   ├── types/                    # TypeScript 类型定义
│   │   └── next.config.mjs           # Next.js 配置（含 API 代理 rewrites）
│   │
│   └── api/                          # Spring Boot 后端
│       └── src/main/
│           ├── java/com/orionkey/    # REST 控制器、实体、服务、安全配置
│           └── resources/
│               ├── application.yml   # 应用配置（数据库、JWT、邮件、上传等）
│               └── data.sql          # 初始化数据（管理员、站点配置）
│
├── docker-compose.yml                # Docker Compose 编排（生产 / 本地通用）
├── .env.example                      # 环境变量模板
└── pnpm-workspace.yaml               # Monorepo 工作区声明
```

---

## 先决条件

开始之前，请确保已安装以下工具：

| 工具 | 版本 | 说明 |
|------|------|------|
| Java | 22+ | 后端运行环境 |
| Maven | 3.9+ | 后端构建工具 |
| Node.js | 20+ | 前端运行环境 |
| pnpm | 9+ | 前端包管理（`npm i -g pnpm`） |
| PostgreSQL | 18+ | 数据库，需提前创建库和用户 |

---

## 配置

核心配置文件：`apps/api/src/main/resources/application.yml`

所有配置项均支持**环境变量覆盖**（格式 `${ENV_VAR:默认值}`），本地开发可直接修改 yml，生产环境建议通过环境变量注入。

### 应用公网地址（原生支付必填）

```yaml
app:
  base-url: ${APP_BASE_URL:http://localhost:8083}
```

生产环境**必须**通过 `APP_BASE_URL` 设置为实际公网可访问地址，用于自动生成微信/支付宝支付回调地址。

### 数据库

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/nova_key}
    username: ${DB_USERNAME:novakey}
    password: ${DB_PASSWORD:your_password}
```

首次启动自动建表（`ddl-auto: update`），启动后执行一次初始化 SQL（data.sql 文件）写入管理员账户、站点配置：

```bash
psql -U novakey -d nova_key -f apps/api/src/main/resources/data.sql
```

> SQL 内置 `WHERE NOT EXISTS`，多次执行不会产生重复数据。

### JWT 认证

```yaml
jwt:
  secret: ${JWT_SECRET:<用 openssl rand -base64 48 生成>}
  expiration: 86400000  # 24 小时
```

生产环境**必须**通过 `JWT_SECRET` 环境变量注入随机密钥（至少 256 bits）：

```bash
openssl rand -base64 48
```

### 密码加密模式

```yaml
security:
  password-plain: ${PASSWORD_PLAIN:true}  # true=明文密码(开发用), false=BCrypt(生产用)
```

- **本地开发**：`true`（默认），密码明文存储，方便调试
- **生产环境**：设为 `false`，启用 BCrypt 加密，**必须在切换前重置所有用户密码**

### 邮件发送

```yaml
spring:
  mail:
    host: ${MAIL_HOST:smtp.example.com}
    port: ${MAIL_PORT:465}
    username: ${MAIL_USERNAME:your@email.com}
    password: ${MAIL_PASSWORD:your_password}

mail:
  enabled: ${MAIL_ENABLED:true}       # 邮件功能总开关，设为 false 可关闭所有邮件发送
  site-url: ${MAIL_SITE_URL:https://your-domain.com}
```

### 文件上传

```yaml
upload:
  path: ${UPLOAD_PATH:./uploads}                # 文件存储路径
  url-prefix: ${UPLOAD_URL_PREFIX:/api/uploads}  # 访问 URL 前缀
```

---

## 部署

> 详细部署与运维步骤见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)（部署文档）与 [docs/MAINTENANCE.md](docs/MAINTENANCE.md)（维护文档）。

### 方式一：Docker 部署（推荐）

仓库根目录提供 `docker-compose.yml`，编排 **api / web / bepusdt** 三个容器；**不含 PostgreSQL 和 Nginx**，需自行准备。

```bash
# 1. 准备 .env（变量含义见上方「配置」章节）
cp .env.example .env

# 2. 构建并启动（或将镜像发布至 GHCR 后 docker compose pull）
docker compose build
docker compose up -d

# 3. 查看日志
docker compose logs -f
```

> 上传文件通过卷挂载 `./uploads` 持久化，容器重建不丢失。生产环境建议前置 Nginx 反向代理处理 HTTPS 和静态资源。

### 方式二：非 Docker 部署（直接运行）

适合本地开发或单机直跑场景。需先安装 Java 22 / Maven 3.9+ / Node.js 20+ / pnpm 9+ / PostgreSQL 18+。

```bash
# 后端（端口 8083）
cd apps/api
mvn spring-boot:run

# 前端（端口 3000，新开终端）
cd apps/web
pnpm install
pnpm dev
```

或在仓库根目录一键启动前端：

```bash
pnpm install
pnpm dev:web
```

> **API 代理**：`next.config.mjs` 已配置 `rewrites`，前端 `/api/*` 自动代理到 `http://localhost:8083`，无需手动处理跨域。

### 验证

- 健康检查：`GET http://localhost:8083/api/health`

---

## 致谢

- 本项目基于 [RivenLau/orion-key](https://github.com/RivenLau/orion-key) 二次开发，遵循原项目 [MIT License](LICENSE)。
- 感谢原项目作者的出色工作与开源贡献。

---

## License

[MIT](LICENSE) © Nova key contributors · 基于 [Orion Key](https://github.com/RivenLau/orion-key) © Riven (MIT)

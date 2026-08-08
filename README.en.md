<div align="center">

# Nova key

**Automated Digital Goods Delivery Platform · A Fork of the Open-Source Orion Key**

[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-22-orange?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen?logo=springboot)
![Next.js](https://img.shields.io/badge/Next.js-16-black?logo=next.js)
![React](https://img.shields.io/badge/React-19-61dafb?logo=react)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18+-336791?logo=postgresql&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5.7-3178c6?logo=typescript&logoColor=white)
![Tailwind CSS](https://img.shields.io/badge/Tailwind%20CSS-3.4-38bdf8?logo=tailwindcss&logoColor=white)
![pnpm](https://img.shields.io/badge/pnpm-9+-f69220?logo=pnpm&logoColor=white)

English | [简体中文](README.md)

</div>

---

## Project Statement

> **Nova key** is a secondary development (fork) of the open-source project
> [Orion Key](https://github.com/RivenLau/orion-key) (MIT License).
> It implements the full **Native WeChat Pay (APIv3)** and **Native Alipay**
> payment flows (order creation, callback signature verification, active order
> querying), plus **Customer Management**, **Marketing & Coupons**,
> **Notifications** (DingTalk / WeCom / Email) and an **RBAC permission system**
> (staff & roles) on top of the original project, and customizes branding and docs.
> Copyright of the original project belongs to its authors.

| | Link |
|---|---|
| 🐙 **This repository** | <a href="https://github.com/shushuhao01/Nova-key" target="_blank" rel="noopener noreferrer">https://github.com/shushuhao01/Nova-key</a> |
| ⭐ **Upstream repository** | <a href="https://github.com/RivenLau/orion-key" target="_blank" rel="noopener noreferrer">https://github.com/RivenLau/orion-key</a> |
| 🔑 **Default admin account** | `admin` / `admin123` (change it immediately after first login; bound to the built-in `SUPER_ADMIN` role) |

---

## Key Features

|  |  |
|---|---|
| 🛒 **Auto Delivery** — card keys are delivered automatically after payment | 🎨 **Theme Switch** — light/dark mode with customizable theme colors |
| 📦 **Product Management** — categories, on/off shelf, stock, bulk import | 🔒 **Secure Auth** — stateless JWT + BCrypt |
| 💳 **Multiple Payment Channels** — Native WeChat / Native Alipay / Epay / USDT | 🛡️ **Risk Control** — IP rate limit, login brute-force protection, order anti-abuse |
| 📊 **Admin Panel** — dashboard, order/site management | 🔍 **Order Tracking** — lookup card keys by order number, guest & member support |
| 🛍️ **Cart** — combine multiple products into one order | ⚙️ **Site Config** — announcement, popup, maintenance mode |
| 👥 **Customer Management** — registered + anonymous customers, spending stats & ban | 📣 **Marketing** — email campaigns + coupons (all/specific products, checkout redeem) |
| 🔔 **Notifications** — DingTalk / WeCom / Email templates, auto-triggered | 🧑‍💼 **System (RBAC)** — internal staff & role permissions, menu & API control |

---

## Payment Integration

| Channel | Method | Description |
|---------|--------|-------------|
| WeChat Pay | **Native (APIv3)** | Direct WeChat Pay integration, Native QR code, callback verify + active query fallback |
| Alipay | **Native** | Direct Alipay Open Platform integration, PC QR / mobile H5 |
| Alipay | Epay | Via third-party Epay platform |
| WeChat Pay | Epay | Via third-party Epay platform |
| USDT (TRC-20) | BEpusdt self-hosted | On-chain auto confirmation |

> **Native WeChat/Alipay callback URLs are auto-generated.** Set the `APP_BASE_URL`
> environment variable during deployment and callbacks become
> `{APP_BASE_URL}/api/payments/webhook/wxpay` and `/alipay` automatically.

---

## Admin Panel Modules

Backend entry `/admin`; the logged-in account must have backend access. Overview:

| Module | Features |
|--------|----------|
| 📊 Dashboard | Today/month sales & orders, conversion rate, traffic, low-stock alerts |
| 🗂️ Categories | CRUD and ordering of product categories |
| 📦 Products | CRUD, specs/wholesale, on/off shelf, stock threshold, Markdown details |
| 🔑 Card Keys | Bulk import, stock summary, search/export |
| 🧾 Orders | List/detail, status flow (paid/delivered/expired), manual card key resend |
| 👥 Customers | Registered + anonymous aggregation, spending stats, order timeline, ban/unban |
| 📣 Marketing | Email campaigns (audience filtering), coupons with redemption stats |
| 🔔 Notifications | DingTalk/WeCom bots, notification email, templates, auto-trigger, test send |
| 💳 Payment Channels | Native WeChat/Alipay, Epay, USDT config; parameter validation & test connection |
| 🛡️ Risk Control | IP rate limit, login brute-force protection, order anti-abuse thresholds |
| 🔎 TXID Review | USDT transaction review, auto/manual approval (refund on rejection) |
| 📜 Operation Logs | Audit of key backend operations (login, order, delivery, payment config) |
| 🧑‍💼 System | Internal staff (create/reset password/disable/delete/detail) & role permissions (RBAC) |

---

## RBAC Permission System

> Manages **internal staff** (admins, support, etc.) and backend access; frontend
> registered customers (`role=USER`) are not part of the permission system.

### Model

- **User** (`users`): `role` enum `USER` / `STAFF` / `ADMIN`. `ADMIN` is the built-in
  super admin; `STAFF` obtains permissions via `role_id` → role.
- **Role** (`system_roles`): unique `code`, `permissions` (JSON array of permission codes), `is_system` flag.
- **Permission code**: the smallest unit for backend menu and API authorization.

### Permission Codes

| Code | Backend Module |
|------|----------------|
| `BACKEND_ACCESS` | Backend access master switch (cannot enter admin without it) |
| `DASHBOARD` | Dashboard |
| `CATEGORY_MANAGE` / `PRODUCT_MANAGE` / `CARDKEY_MANAGE` | Categories / Products / Card Keys |
| `ORDER_MANAGE` | Orders |
| `CUSTOMER_MANAGE` | Customers |
| `MARKETING_MANAGE` | Marketing |
| `PAYMENT_MANAGE` | Payment Channels |
| `SITE_CONFIG_MANAGE` | Site Config |
| `RISK_MANAGE` / `TXID_REVIEW` / `LOG_VIEW` | Risk / TXID Review / Operation Logs |
| `SYSTEM_MANAGE` | System (staff & roles) |

### Authorization Chain

- Backend: `/admin/**` requires `BACKEND_ACCESS`; `/admin/system/**` additionally requires `SYSTEM_MANAGE`.
  The JWT filter validates user state (disable/role change takes effect immediately) and injects dynamic permissions.
- Frontend: sidebar menus are filtered by permission codes.
- The built-in `SUPER_ADMIN` role has all permissions and cannot be modified/deleted; users bound to it are `ADMIN`.
- Protection rules: you cannot modify/disable/delete your own account; built-in admins cannot be disabled/deleted;
  roles bound to staff cannot be deleted.

---

## Customer Management

| View | Description |
|------|-------------|
| Overview | Registered count, anonymous count, new today/month, deal/no-deal customers |
| Registered | `role=USER` users: search, spending stats (orders/paid/total spent), detail, ban/unban |
| Anonymous | Aggregated by email for guest buyers: order count, total spent, first/last purchase, order drill-down |

> Registered and anonymous customers are counted by orders; the same email before/after
> registration is counted separately, and the scope is uniformly "non-internal-staff".

---

## Marketing & Coupons

### Email Campaigns

- Audience: all users / specific user IDs / email list.
- HTML templates with variables (`{{username}}`, `{{coupon_code}}`, ...); send counts tracked.

### Coupons

| Item | Description |
|------|-------------|
| Type | Fixed amount (AMOUNT) / Percentage (PERCENT) |
| Threshold / Validity | Minimum spend, valid from/to |
| **Scope** | `ALL` products or `SPECIFIC` selected products |
| Issuance | System-generated redemption code, limited claims, backend redemption stats |

- On the checkout page the code is validated instantly with a **specific reason**:
  not claimed / already used / not active / expired / below threshold / not applicable to the product, etc.
- Safety: row-lock prevents over-issuing under concurrency; the actual amount after the coupon must be > 0.

---

## Notifications

| Channel | Description |
|---------|-------------|
| DingTalk bot | Webhook + secret signature |
| WeCom bot | Webhook |
| Notification email | SMTP |

- Preset templates (new order, payment success, low stock, ...); enable, bind channels,
  and toggle auto-trigger in the admin panel.
- Per-channel test send with itemized results.

---

## Tech Stack

| Layer | Stack |
|-------|-------|
| **Frontend** | Next.js 16 · React 19 · TypeScript · Tailwind CSS 3 · shadcn/ui |
| **Backend** | Spring Boot 3.4 · Java 22 · Spring Data JPA · Spring Security |
| **Database** | PostgreSQL 18+ |
| **Auth** | JWT (jjwt) · BCrypt |
| **Build** | pnpm (frontend) · Maven (backend) |

---

## Prerequisites

| Tool | Version | Description |
|------|---------|-------------|
| Java | 22+ | Backend runtime |
| Maven | 3.9+ | Backend build |
| Node.js | 20+ | Frontend runtime |
| pnpm | 9+ | Frontend package manager (`npm i -g pnpm`) |
| PostgreSQL | 18+ | Database (create the database and user first) |

---

## Configuration

Main config file: `apps/api/src/main/resources/application.yml`

All settings support **environment variable override** (`${ENV_VAR:default}`).

### Public Base URL (required for native payments)

```yaml
app:
  base-url: ${APP_BASE_URL:http://localhost:8083}
```

Set `APP_BASE_URL` to the actual public URL in production; it is used to auto-generate the WeChat/Alipay callback URLs.

### Database

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/nova_key}
    username: ${DB_USERNAME:novakey}
    password: ${DB_PASSWORD:your_password}
```

Tables are created automatically (`ddl-auto: update`). Run the initialization SQL once after the first start:

```bash
psql -U novakey -d nova_key -f apps/api/src/main/resources/data.sql
```

> All SQL statements use `WHERE NOT EXISTS`, safe to re-run.

### JWT

```yaml
jwt:
  secret: ${JWT_SECRET:<generated via openssl rand -base64 48>}
  expiration: 86400000  # 24 hours
```

### Password Mode

```yaml
security:
  password-plain: ${PASSWORD_PLAIN:true}  # true=plain (dev), false=BCrypt (production)
```

### Mail

```yaml
spring:
  mail:
    host: ${MAIL_HOST:smtp.example.com}
    port: ${MAIL_PORT:465}
    username: ${MAIL_USERNAME:your@email.com}
    password: ${MAIL_PASSWORD:your_password}

mail:
  enabled: ${MAIL_ENABLED:true}
  site-url: ${MAIL_SITE_URL:https://your-domain.com}
```

### Uploads

```yaml
upload:
  path: ${UPLOAD_PATH:./uploads}
  url-prefix: ${UPLOAD_URL_PREFIX:/api/uploads}
```

---

## Deployment

> See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) (deployment) and [docs/MAINTENANCE.md](docs/MAINTENANCE.md) (maintenance) for details.

### Option 1: Docker (recommended)

```bash
cp .env.example .env
docker compose build
docker compose up -d
docker compose logs -f
```

### Option 2: Run directly

```bash
# Backend (port 8083)
cd apps/api
mvn spring-boot:run

# Frontend (port 3000, new terminal)
cd apps/web
pnpm install
pnpm dev
```

### Health check

- `GET http://localhost:8083/api/health`

---

## Credits

- Built on [RivenLau/orion-key](https://github.com/RivenLau/orion-key) under its [MIT License](LICENSE).
- Thanks to the original authors for their excellent work and open-source contribution.

---

## License

[MIT](LICENSE) © Nova key contributors · Based on [Orion Key](https://github.com/RivenLau/orion-key) © Riven (MIT)

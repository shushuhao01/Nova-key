-- ============================================================
-- Nova key 初始化数据（基于开源 Orion Key 二开）
-- 执行方式：由 update.sh 的 Step 6.5 用 psql 自动执行（后端启动不依赖本文件）。
-- 表结构由后端 ddl-auto: update 启动时自动创建/补字段，无需手动建表。
-- 所有 INSERT 均带 WHERE NOT EXISTS，可安全重复执行（幂等）。
-- 如需手动执行: psql -h <host> -p <port> -U <user> -d <db> -f data.sql
-- ============================================================

-- ────────────────────────────────────────
-- 1. 管理员账户 (默认密码: admin123，请首次登录后立即修改)
--    默认使用明文密码（application.yml: security.password-plain: true）。
--    若生产设置为 BCrypt，请将 password_hash 替换为 BCrypt 哈希。
-- ────────────────────────────────────────
INSERT INTO users (id, username, email, password_hash, role, points, is_deleted, failed_login_attempts, lock_until, created_at, updated_at)
SELECT gen_random_uuid(), 'admin', 'admin@novakey.com',
       'admin123',
       'ADMIN', 0, 0, 0, NULL, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin');

-- ────────────────────────────────────────
-- 2. 站点配置 (config_group = 'site')
-- ────────────────────────────────────────

-- 站点名称，显示在页面标题和 Header
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'site_name', 'Nova key', 'site', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'site_name');

-- 站点标语，显示在首页 Hero 区域
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'site_slogan', '即买即发，随时可取', 'site', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'site_slogan');

-- 站点描述，显示在首页副标题 / SEO
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'site_description', '自动发货，7×24 小时全天候在线', 'site', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'site_description');

-- 页脚（留空则不显示）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'footer_text', '由开源 Orion Key 二开，Nova key 提供服务', 'site', NOW(), NOW()
    WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'footer_text');

INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'github_url', 'https://github.com/shushuhao01/Nova-key', 'site', NOW(), NOW()
    WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'github_url');

-- 积分功能总开关 (true/false)
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'points_enabled', 'false', 'site', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'points_enabled');

-- 积分倍率：每消费 1 元获得的积分数
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'points_rate', '1', 'site', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'points_rate');

-- 维护模式开关，开启后非管理员请求返回 503 (true/false)
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'maintenance_enabled', 'false', 'site', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'maintenance_enabled');

-- 全站公告开关 (true/false)
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'announcement_enabled', 'false', 'site', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'announcement_enabled');

-- 弹窗通知开关 (true/false)
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'popup_enabled', 'false', 'site', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'popup_enabled');

-- ────────────────────────────────────────
-- 2.1 邮箱发件配置 (config_group = 'email')
-- 管理后台「网站设置 → 邮箱发件」可修改；此处仅占位空值：
-- 留空时后端自动回退到 .env 环境变量（MAIL_HOST / MAIL_USERNAME / MAIL_PASSWORD 等），
-- 因此不会覆盖已有 SMTP 配置。mail_enabled 不在此写入，避免锁死环境变量的开关。
-- ────────────────────────────────────────
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'smtp_host', '', 'email', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'smtp_host');

INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'smtp_port', '', 'email', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'smtp_port');

INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'smtp_username', '', 'email', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'smtp_username');

INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'smtp_password', '', 'email', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'smtp_password');

INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'mail_from', '', 'email', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'mail_from');

INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'mail_from_name', '', 'email', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'mail_from_name');

INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'mail_site_url', '', 'email', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'mail_site_url');

-- ────────────────────────────────────────
-- 3. 风控配置 (config_group = 'risk')
-- ────────────────────────────────────────

-- 单 IP 每秒最大请求数（令牌桶容量）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'rate_limit_per_second', '25', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'rate_limit_per_second');

-- 单账号连续登录失败上限（超过后需等待冷却）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'login_attempt_limit', '10', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'login_attempt_limit');

-- 每用户单次最大购买数量
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'max_purchase_per_user', '50', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'max_purchase_per_user');

-- 单 IP 最大未支付订单数（防刷单，共享 IP 场景适当放宽）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'max_pending_orders_per_ip', '5', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'max_pending_orders_per_ip');

-- 单用户最大未支付订单数
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'max_pending_orders_per_user', '5', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'max_pending_orders_per_user');

-- 未支付订单自动过期时间（分钟）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'order_expire_minutes', '15', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'order_expire_minutes');

-- Turnstile 人机验证开关（默认关闭，需后台手动启用）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'turnstile_enabled', 'false', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'turnstile_enabled');

-- 设备指纹限流开关（默认关闭，需后台手动启用）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'device_rate_limit_enabled', 'false', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'device_rate_limit_enabled');

-- 设备指纹限流：下单频率上限（次/小时/设备）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'device_order_limit_per_hour', '15', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'device_order_limit_per_hour');

-- 设备指纹限流：TXID 提交上限（次/小时/设备）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'device_txid_limit_per_hour', '5', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'device_txid_limit_per_hour');

-- TXID 提交上限（次/订单）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'txid_submit_limit_per_order', '3', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'txid_submit_limit_per_order');

-- 设备指纹限流：查询频率上限（次/小时/设备）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'device_query_limit_per_hour', '50', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'device_query_limit_per_hour');

-- 设备指纹限流：登录频率上限（次/小时/设备）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'device_login_limit_per_hour', '10', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'device_login_limit_per_hour');

-- 设备指纹限流：注册频率上限（次/小时/设备）
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
SELECT gen_random_uuid(), 'device_register_limit_per_hour', '10', 'risk', NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM site_configs WHERE config_key = 'device_register_limit_per_hour');


-- ────────────────────────────────────────
-- 4. 货币类型
-- ────────────────────────────────────────
INSERT INTO currencies (id, code, name, symbol, is_enabled, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), 'CNY', '人民币', '¥', true, 1, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM currencies WHERE code = 'CNY');

INSERT INTO currencies (id, code, name, symbol, is_enabled, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), 'USD', '美元', '$', true, 2, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM currencies WHERE code = 'USD');

INSERT INTO currencies (id, code, name, symbol, is_enabled, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), 'USDT', 'USDT (TRC-20)', '₮', true, 3, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM currencies WHERE code = 'USDT');

INSERT INTO currencies (id, code, name, symbol, is_enabled, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), 'EUR', '欧元', '€', true, 4, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM currencies WHERE code = 'EUR');

INSERT INTO currencies (id, code, name, symbol, is_enabled, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), 'GBP', '英镑', '£', true, 5, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM currencies WHERE code = 'GBP');

-- ────────────────────────────────────────
-- 5. 商品分类 / 商品 / 卡密
--    演示数据已清理，生产环境请通过管理后台添加商品与卡密。
-- ────────────────────────────────────────
-- Spring 自动执行时每条语句独立提交，无需 commit；psql 手动执行默认 autocommit 亦无需 commit。
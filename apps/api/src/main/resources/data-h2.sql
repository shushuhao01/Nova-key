-- ============================================================
-- Nova key 本地演示初始化数据（H2 PostgreSQL 兼容模式，基于开源 Orion Key 二开）
-- 由 data.sql 转译：gen_random_uuid()->RANDOM_UUID(), generate_series->VALUES
-- 仅用于本地无 PostgreSQL 环境的演示启动
-- ============================================================

-- 1. 管理员账户 (明文密码 admin123，需 PASSWORD_PLAIN=true)
INSERT INTO users (id, username, email, password_hash, role, points, is_deleted, failed_login_attempts, lock_until, created_at, updated_at)
VALUES (RANDOM_UUID(), 'admin', 'admin@novakey.com', 'admin123', 'ADMIN', 0, 0, 0, NULL, NOW(), NOW());

-- 2. 站点配置
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'site_name', 'Nova key', 'site', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'site_slogan', '即买即发，随时可取', 'site', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'site_description', '自动发货，7×24 小时全天候在线', 'site', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'footer_text', '由开源 Orion Key 二开，Nova key 提供服务', 'site', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'github_url', 'https://github.com/shushuhao01/Nova-key', 'site', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'points_enabled', 'false', 'site', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'points_rate', '1', 'site', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'maintenance_enabled', 'false', 'site', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'announcement_enabled', 'false', 'site', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'popup_enabled', 'false', 'site', NOW(), NOW());

-- 3. 风控配置
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'rate_limit_per_second', '25', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'login_attempt_limit', '10', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'max_purchase_per_user', '50', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'max_pending_orders_per_ip', '5', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'max_pending_orders_per_user', '5', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'order_expire_minutes', '15', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'turnstile_enabled', 'false', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'device_rate_limit_enabled', 'false', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'device_order_limit_per_hour', '15', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'device_txid_limit_per_hour', '5', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'txid_submit_limit_per_order', '3', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'device_query_limit_per_hour', '50', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'device_login_limit_per_hour', '10', 'risk', NOW(), NOW());
INSERT INTO site_configs (id, config_key, config_value, config_group, created_at, updated_at)
VALUES (RANDOM_UUID(), 'device_register_limit_per_hour', '10', 'risk', NOW(), NOW());

-- 4. 货币类型
INSERT INTO currencies (id, code, name, symbol, is_enabled, sort_order, created_at, updated_at)
VALUES (RANDOM_UUID(), 'CNY', '人民币', '¥', true, 1, NOW(), NOW());
INSERT INTO currencies (id, code, name, symbol, is_enabled, sort_order, created_at, updated_at)
VALUES (RANDOM_UUID(), 'USD', '美元', '$', true, 2, NOW(), NOW());
INSERT INTO currencies (id, code, name, symbol, is_enabled, sort_order, created_at, updated_at)
VALUES (RANDOM_UUID(), 'USDT', 'USDT (TRC-20)', '₮', true, 3, NOW(), NOW());
INSERT INTO currencies (id, code, name, symbol, is_enabled, sort_order, created_at, updated_at)
VALUES (RANDOM_UUID(), 'EUR', '欧元', '€', true, 4, NOW(), NOW());
INSERT INTO currencies (id, code, name, symbol, is_enabled, sort_order, created_at, updated_at)
VALUES (RANDOM_UUID(), 'GBP', '英镑', '£', true, 5, NOW(), NOW());

-- 5. 商品分类 / 商品 / 卡密（演示数据已清理，通过管理后台添加）

-- 6. 支付渠道（占位配置已清理，请在管理后台添加真实商户配置后使用）

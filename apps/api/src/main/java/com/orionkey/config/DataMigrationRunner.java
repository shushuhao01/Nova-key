package com.orionkey.config;

import com.orionkey.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 应用启动后执行的一次性数据迁移。
 * 1. 将已有规格但 spec_enabled=false 的商品自动设为 true，确保向后兼容。
 * 2. 修复 users.role 原生 PG 枚举列（缺 'STAFF' 值导致保存客服角色报 System error）：
 *    若该列是 USER-DEFINED（PG 枚举），转换为 varchar(255)，并清除引用 role 的 CHECK 约束。
 * 幂等安全：后续启动无匹配行时 0 行更新 / 列类型已为 varchar 时跳过，无副作用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataMigrationRunner implements ApplicationRunner {

    private final ProductRepository productRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int updated = productRepository.migrateSpecEnabled();
        if (updated > 0) {
            log.info("Migration: set spec_enabled=true for {} products with existing specs", updated);
        }
        migrateUserRoleColumn();
    }

    /**
     * 生产环境早期版本由 Hibernate 6 为 users.role 建成了原生 PG 枚举类型（如
     * CREATE TYPE user_role AS ENUM ('USER','ADMIN')），缺少本版本新增的 'STAFF' 值。
     * ddl-auto:update 不会修改已有列类型，Hibernate 事务内也无法 ALTER TYPE ADD VALUE，
     * 导致保存/编辑带 STAFF 角色的员工时 PSQLException → 全局异常转 "System error"。
     * 解决方案：启动时将列转换为 varchar(255)（枚举标签 → 文本），并清理会拒绝 'STAFF'
     * 的 CHECK 约束。H2 等环境该列已是 varchar，自动跳过。
     */
    private void migrateUserRoleColumn() {
        try {
            List<String> dataTypes = jdbcTemplate.queryForList(
                    "SELECT data_type FROM information_schema.columns WHERE table_name = 'users' AND column_name = 'role'",
                    String.class);
            if (dataTypes.size() == 1 && "USER-DEFINED".equalsIgnoreCase(dataTypes.get(0))) {
                log.warn("[Migration] users.role is a native PG enum type, converting to varchar(255) to support STAFF role...");
                jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN role TYPE varchar(255) USING role::text");
                log.info("[Migration] users.role converted to varchar(255)");
            }
            // 清除引用 role 的 CHECK 约束（如 role IN ('USER','ADMIN')），防止其继续拒绝 STAFF
            try {
                List<String> constraints = jdbcTemplate.queryForList(
                        "SELECT conname FROM pg_constraint WHERE conrelid = 'users'::regclass AND contype = 'c' "
                                + "AND pg_get_constraintdef(oid) ~ '\\mrole\\M'",
                        String.class);
                for (String con : constraints) {
                    log.warn("[Migration] dropping CHECK constraint \"{}\" on users.role", con);
                    jdbcTemplate.execute("ALTER TABLE users DROP CONSTRAINT \"" + con.replace("\"", "\"\"") + "\"");
                }
            } catch (Exception e) {
                log.warn("[Migration] users.role CHECK constraint cleanup skipped (non-PostgreSQL or no matching): {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("[Migration] users.role column check skipped: {}", e.getMessage());
        }
    }
}

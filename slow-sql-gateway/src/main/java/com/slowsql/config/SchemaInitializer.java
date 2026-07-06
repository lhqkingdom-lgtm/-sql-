package com.slowsql.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 应用启动后自动初始化平台数据库表。
 * 建表失败仅记 WARN，不影响应用启动（适用于 DB 还未建好的首次启动场景）。
 */
@Component
public class SchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("检查并初始化平台数据库表...");

        // 执行 schema.sql
        try {
            ClassPathResource resource = new ClassPathResource("schema.sql");
            String sql = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            // 按分号拆分，逐条执行
            for (String stmt : sql.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                    try {
                        jdbcTemplate.execute(trimmed);
                    } catch (Exception e) {
                        log.debug("SQL执行跳过（可能已存在）: {}", e.getMessage());
                    }
                }
            }
            log.info("schema.sql 执行完成");
        } catch (Exception e) {
            log.warn("schema.sql 执行失败 (不影响启动): {}", e.getMessage());
        }

        // V5兼容旧表：补充旧表可能缺失的新列）
        addColumnIfMissing("diagnosis_record", "instance_id", "VARCHAR(64)");
        addColumnIfMissing("diagnosis_record", "project_code", "VARCHAR(64)");
        addColumnIfMissing("diagnosis_record", "tool_call_count", "INT DEFAULT 0");
        addColumnIfMissing("diagnosis_record", "error_message", "VARCHAR(1000)");
        addColumnIfMissing("diagnosis_record", "source", "VARCHAR(50) DEFAULT 'manual'");
        addColumnIfMissing("diagnosis_record", "fingerprint", "VARCHAR(64)");
        addColumnIfMissing("diagnosis_record", "updated_at", "DATETIME");
        addColumnIfMissing("captured_sql", "instance_id", "VARCHAR(64)");
        addColumnIfMissing("captured_sql", "project_code", "VARCHAR(64)");
        addColumnIfMissing("captured_sql", "severity", "VARCHAR(10)");
        addColumnIfMissing("captured_sql", "lock_time_sec", "DOUBLE");
        addColumnIfMissing("captured_sql", "rows_sent", "BIGINT");

        log.info("平台数据库表初始化完成");
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            List<String> cols = jdbcTemplate.queryForList(
                    "SHOW COLUMNS FROM " + table + " LIKE '" + column + "'", String.class);
            if (cols.isEmpty()) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                log.info("  + {}.{} 列已添加", table, column);
            }
        } catch (Exception e) {
            // 表不存在等情况，忽略
        }
    }
}

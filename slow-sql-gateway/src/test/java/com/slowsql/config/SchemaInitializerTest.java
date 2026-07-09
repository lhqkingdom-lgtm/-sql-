package com.slowsql.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.not;

class SchemaInitializerTest {

    private JdbcTemplate jdbcTemplate;
    private SchemaInitializer initializer;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        initializer = new SchemaInitializer(jdbcTemplate);
    }

    // ===== schema.sql 执行 =====

    @Test
    void init_shouldExecuteSchemaStatements() {
        // schema.sql 中的每条语句独立 try-catch，失败不中断
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        doNothing().when(jdbcTemplate).execute(anyString());

        // 不应抛异常
        initializer.init();

        verify(jdbcTemplate, atLeastOnce()).execute(anyString());
    }

    // ===== schema.sql 不存在→不中断 =====

    @Test
    void init_shouldSurviveMissingSchemaFile() {
        // 无 schema.sql → ClassPathResource 抛异常，记 WARN 但不中断
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        doNothing().when(jdbcTemplate).execute(anyString());

        // 不应抛异常
        initializer.init();
    }

    // ===== 列已存在→跳过 ALTER =====

    @Test
    void init_shouldSkipExistingColumn() {
        // SHOW COLUMNS 返回非空 → 列已存在，跳过 ALTER
        // 只有 instance_id 列已存在，其他列不存在
        when(jdbcTemplate.queryForList(contains("instance_id"), eq(String.class)))
                .thenReturn(List.of("instance_id"));  // instance_id 已存在

        // 其他列的 SHOW COLUMNS 都返回空（需要 ALTER）
        when(jdbcTemplate.queryForList(not(contains("instance_id")), eq(String.class)))
                .thenReturn(List.of());
        // execute() 是 void 方法，mock 默认不做任何事

        initializer.init();

        // instance_id 的 ALTER 不应被执行
        verify(jdbcTemplate, never()).execute(startsWith("ALTER TABLE diagnosis_record ADD COLUMN instance_id"));
        // 但其他列应该被 ALTER
        verify(jdbcTemplate, atLeastOnce()).execute(startsWith("ALTER TABLE"));
    }

    // ===== 列不存在→执行 ALTER =====

    @Test
    void init_shouldAddMissingColumn() {
        // SHOW COLUMNS 返回空 → 列不存在，执行 ALTER
        when(jdbcTemplate.queryForList(anyString(), eq(String.class))).thenReturn(List.of());
        doNothing().when(jdbcTemplate).execute(anyString());

        initializer.init();

        // ALTER TABLE 至少执行一次
        verify(jdbcTemplate, atLeastOnce()).execute(startsWith("ALTER TABLE"));
    }

    // ===== 表不存在→SHOW COLUMNS 失败但忽略 =====

    @Test
    void init_shouldSurviveMissingTable() {
        when(jdbcTemplate.queryForList(anyString(), eq(String.class)))
                .thenThrow(new RuntimeException("Table doesn't exist"));
        doNothing().when(jdbcTemplate).execute(anyString());

        // 不应抛异常
        initializer.init();
    }
}

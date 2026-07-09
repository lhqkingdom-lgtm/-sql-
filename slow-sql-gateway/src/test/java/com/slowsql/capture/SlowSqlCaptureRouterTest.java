package com.slowsql.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlowSqlCaptureRouterTest {

    // ===== buildDiagnosisContext =====

    @Test
    void buildDiagnosisContext_shouldIncludeMetrics() {
        SlowSqlEvent event = buildEvent("SELECT * FROM orders WHERE id=1", 5.5, 100000L, "slow_log_table", "test_sql");

        String ctx = SlowSqlCaptureRouter.buildDiagnosisContext(event);

        assertTrue(ctx.contains("【自动采集信息】"));
        assertTrue(ctx.contains("5.50 秒"));
        assertTrue(ctx.contains("100000 行"));
        assertTrue(ctx.contains("slow_log_table"));
        assertTrue(ctx.contains("test_sql"));
        assertTrue(ctx.contains("【待分析SQL】"));
        assertTrue(ctx.contains("SELECT * FROM orders WHERE id=1"));
    }

    @Test
    void buildDiagnosisContext_shouldHandleNullMetrics() {
        SlowSqlEvent event = new SlowSqlEvent();
        event.setSqlText("SELECT 1");
        event.setSource("manual");
        event.setDbName(null);
        event.setMetrics(null);

        String ctx = SlowSqlCaptureRouter.buildDiagnosisContext(event);

        assertTrue(ctx.contains("【自动采集信息】"));
        assertTrue(ctx.contains("manual"));
        assertTrue(ctx.contains("unknown"));  // null db → "unknown"
        assertFalse(ctx.contains("耗时:"));   // null metrics → 跳过指标
        assertFalse(ctx.contains("扫描:"));
    }

    @Test
    void buildDiagnosisContext_shouldHandleNullDbName() {
        SlowSqlEvent event = buildEvent("SELECT 1", 1.0, 100L, "http_capture", null);

        String ctx = SlowSqlCaptureRouter.buildDiagnosisContext(event);

        assertTrue(ctx.contains("unknown"));  // null → "unknown"
    }

    @Test
    void buildDiagnosisContext_shouldFormatQueryTime() {
        SlowSqlEvent event = buildEvent("SELECT 1", 3.14159265, 500L, "slow_log_table", "db");

        String ctx = SlowSqlCaptureRouter.buildDiagnosisContext(event);

        assertTrue(ctx.contains("3.14 秒"));  // 保留2位小数
    }

    private SlowSqlEvent buildEvent(String sql, double queryTime, long rowsExamined,
                                     String source, String dbName) {
        SlowSqlEvent event = new SlowSqlEvent();
        event.setSqlText(sql);
        event.setSource(source);
        event.setDbName(dbName);

        SlowSqlEvent.EventMetrics m = new SlowSqlEvent.EventMetrics();
        m.setQueryTimeSec(queryTime);
        m.setRowsExamined(rowsExamined);
        event.setMetrics(m);

        return event;
    }
}

package com.slowsql.capture;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SlowSqlEventTest {

    // ===== 指纹 =====

    @Test
    void fingerprint_shouldDifferByInstance() {
        SlowSqlEvent e1 = event("tc-dev-mysql", "SELECT * FROM orders");
        e1.computeFingerprint();

        SlowSqlEvent e2 = event("tc-prod-mysql", "SELECT * FROM orders");
        e2.computeFingerprint();

        assertNotEquals(e1.getFingerprint(), e2.getFingerprint());
    }

    @Test
    void fingerprint_shouldBeSameForNormalizedSql() {
        SlowSqlEvent e1 = event("tc-dev-mysql", "SELECT * FROM orders WHERE id=1");
        e1.computeFingerprint();

        SlowSqlEvent e2 = event("tc-dev-mysql", "select * from orders where id = 1 ");
        e2.computeFingerprint();

        assertEquals(e1.getFingerprint(), e2.getFingerprint());
    }

    @Test
    void fingerprint_shouldDifferBySql() {
        SlowSqlEvent e1 = event("tc-dev-mysql", "SELECT * FROM orders");
        e1.computeFingerprint();

        SlowSqlEvent e2 = event("tc-dev-mysql", "SELECT * FROM users");
        e2.computeFingerprint();

        assertNotEquals(e1.getFingerprint(), e2.getFingerprint());
    }

    @Test
    void normalize_shouldHandleCommaSpacing() {
        SlowSqlEvent e1 = event("tc-dev-mysql", "SELECT a, b, c FROM t");
        e1.computeFingerprint();

        SlowSqlEvent e2 = event("tc-dev-mysql", "SELECT a,b,c FROM t");
        e2.computeFingerprint();

        assertEquals(e1.getFingerprint(), e2.getFingerprint());
    }

    @Test
    void normalize_shouldHandleNull() {
        assertEquals("", SlowSqlEvent.normalizeSql(null));
    }

    // ===== EventNormalizer =====

    @Test
    void normalizer_shouldBuildFromSlowLogRow() {
        EventNormalizer normalizer = new EventNormalizer();
        Map<String, Object> row = Map.of(
                "sql_text", "SELECT * FROM orders WHERE id = 1",
                "query_time", 5.23,
                "lock_time", 0.01,
                "rows_examined", 500000L,
                "rows_sent", 10L,
                "start_time", java.sql.Timestamp.valueOf("2026-07-06 10:00:00"));

        SlowSqlEvent event = normalizer.fromSlowLogRow(row, "tc-dev-mysql", "tongcheng-club");

        assertEquals("slow_log_table", event.getSource());
        assertEquals("tc-dev-mysql", event.getInstanceId());
        assertEquals("tongcheng-club", event.getProjectCode());
        assertEquals(5.23, event.getMetrics().getQueryTimeSec());
        assertEquals(0.01, event.getMetrics().getLockTimeSec());
        assertEquals(500000L, event.getMetrics().getRowsExamined());
        assertNotNull(event.getFingerprint());
    }

    @Test
    void normalizer_shouldBuildFromHttpCapture() {
        EventNormalizer normalizer = new EventNormalizer();
        Map<String, Object> body = Map.of(
                "sqlText", "SELECT * FROM users",
                "queryTimeSec", 2.5,
                "applicationName", "order-service",
                "traceId", "trace-123");

        SlowSqlEvent event = normalizer.fromHttpCapture(body, "tc-dev-mysql", "tongcheng-club");

        assertEquals("http_capture", event.getSource());
        assertNotNull(event.getContext());
        assertEquals("order-service", event.getContext().getApplicationName());
        assertEquals("trace-123", event.getContext().getTraceId());
    }

    @Test
    void normalizer_shouldBuildFromManual() {
        EventNormalizer normalizer = new EventNormalizer();
        SlowSqlEvent event = normalizer.fromManual("SELECT * FROM orders", "tc-dev-mysql", "tongcheng-club");

        assertEquals("manual", event.getSource());
        assertNull(event.getMetrics());
        assertNotNull(event.getFingerprint());
    }

    @Test
    void capturedSql_fromEvent_shouldMapFields() {
        SlowSqlEvent event = event("tc-dev-mysql", "SELECT * FROM orders");
        event.setProjectCode("tongcheng-club");
        event.setSource("slow_log_table");
        event.computeFingerprint();

        SlowSqlEvent.EventMetrics m = new SlowSqlEvent.EventMetrics();
        m.setQueryTimeSec(3.5);
        m.setRowsExamined(10000);
        event.setMetrics(m);

        CapturedSql c = CapturedSql.fromEvent(event);
        assertEquals("tc-dev-mysql", c.getInstanceId());
        assertEquals("tongcheng-club", c.getProjectCode());
        assertEquals(3.5, c.getQueryTimeSec());
        assertEquals(10000L, c.getRowsExamined());
        assertEquals(event.getFingerprint(), c.getFingerprint());
    }

    // ===== 辅助 =====

    private SlowSqlEvent event(String instanceId, String sql) {
        SlowSqlEvent e = new SlowSqlEvent();
        e.setInstanceId(instanceId);
        e.setSqlText(sql);
        return e;
    }
}

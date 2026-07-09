package com.slowsql.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SlowSqlSeverityTest {

    // ===== from — P0 =====

    @Test
    void from_shouldReturnP0ForVerySlowQuery() {
        SlowSqlSeverity result = SlowSqlSeverity.from(event(15.0, 2_000_000L));

        assertEquals(SlowSqlSeverity.P0, result);
    }

    @Test
    void from_shouldReturnP0ForHighRowsExamined() {
        SlowSqlSeverity result = SlowSqlSeverity.from(event(1.0, 1_000_000L));

        assertEquals(SlowSqlSeverity.P0, result);
    }

    @Test
    void from_shouldReturnP0ForHighQueryTime() {
        SlowSqlSeverity result = SlowSqlSeverity.from(event(10.0, 100L));

        assertEquals(SlowSqlSeverity.P0, result);
    }

    // ===== from — P1 =====

    @Test
    void from_shouldReturnP1ForModerateQuery() {
        SlowSqlSeverity result = SlowSqlSeverity.from(event(3.5, 200_000L));

        assertEquals(SlowSqlSeverity.P1, result);
    }

    @Test
    void from_shouldReturnP1ForModerateRowsExamined() {
        SlowSqlSeverity result = SlowSqlSeverity.from(event(0.5, 100_000L));

        assertEquals(SlowSqlSeverity.P1, result);
    }

    @Test
    void from_shouldReturnP1ForModerateQueryTime() {
        SlowSqlSeverity result = SlowSqlSeverity.from(event(2.0, 500L));

        assertEquals(SlowSqlSeverity.P1, result);
    }

    // ===== from — P2 =====

    @Test
    void from_shouldReturnP2ForFastQuery() {
        SlowSqlSeverity result = SlowSqlSeverity.from(event(0.1, 100L));

        assertEquals(SlowSqlSeverity.P2, result);
    }

    // ===== from — null metrics =====

    @Test
    void from_shouldReturnP2ForNullMetrics() {
        SlowSqlEvent e = new SlowSqlEvent();
        e.setMetrics(null);

        assertEquals(SlowSqlSeverity.P2, SlowSqlSeverity.from(e));
    }

    // ===== from — 边界值 =====

    @Test
    void from_shouldReturnP0AtExactP0Threshold() {
        SlowSqlSeverity result = SlowSqlSeverity.from(event(10.0, 1L));

        assertEquals(SlowSqlSeverity.P0, result);
    }

    @Test
    void from_shouldReturnP1AtExactP1Threshold() {
        SlowSqlSeverity result = SlowSqlSeverity.from(event(2.0, 1L));

        assertEquals(SlowSqlSeverity.P1, result);
    }

    // ===== 枚举值 =====

    @Test
    void p0_shouldHaveCorrectLabels() {
        assertEquals("严重", SlowSqlSeverity.P0.label);
        assertEquals("🔴", SlowSqlSeverity.P0.emoji);
    }

    @Test
    void p1_shouldHaveCorrectLabels() {
        assertEquals("警告", SlowSqlSeverity.P1.label);
        assertEquals("🟡", SlowSqlSeverity.P1.emoji);
    }

    @Test
    void p2_shouldHaveCorrectLabels() {
        assertEquals("关注", SlowSqlSeverity.P2.label);
        assertEquals("🟢", SlowSqlSeverity.P2.emoji);
    }

    // ===== P0 优先于 P1 =====

    @Test
    void from_shouldCheckP0BeforeP1() {
        // 同时满足 P0 和 P1 → 返回 P0
        SlowSqlSeverity result = SlowSqlSeverity.from(event(10.0, 1_000_000L));

        assertEquals(SlowSqlSeverity.P0, result);
    }

    private SlowSqlEvent event(double queryTime, long rowsExamined) {
        SlowSqlEvent e = new SlowSqlEvent();
        SlowSqlEvent.EventMetrics m = new SlowSqlEvent.EventMetrics();
        m.setQueryTimeSec(queryTime);
        m.setRowsExamined(rowsExamined);
        e.setMetrics(m);
        return e;
    }
}

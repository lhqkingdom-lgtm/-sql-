package com.slowsql.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 事件归一化器——将各采集源的原始数据翻译为统一的 SlowSqlEvent。
 */
@Component
public class EventNormalizer {

    private static final Logger log = LoggerFactory.getLogger(EventNormalizer.class);

    /**
     * 从 mysql.slow_log 表行构建事件。
     */
    public SlowSqlEvent fromSlowLogRow(Map<String, Object> row, String instanceId, String projectCode) {
        SlowSqlEvent event = new SlowSqlEvent();
        event.setSource("slow_log_table");
        event.setSourceDetail("mysql.slow_log@" + instanceId);
        event.setInstanceId(instanceId);
        event.setProjectCode(projectCode);
        event.setSqlText((String) row.get("sql_text"));
        event.setCapturedAt(toLocalDateTime(row.get("start_time")));

        SlowSqlEvent.EventMetrics m = new SlowSqlEvent.EventMetrics();
        m.setQueryTimeSec(toDouble(row.get("query_time")));
        m.setLockTimeSec(toDouble(row.get("lock_time")));
        m.setRowsExamined(toLong(row.get("rows_examined")));
        m.setRowsSent(toLong(row.get("rows_sent")));
        event.setMetrics(m);

        event.computeFingerprint();
        return event;
    }

    /**
     * 从 HTTP 上报 JSON 构建事件。
     */
    public SlowSqlEvent fromHttpCapture(Map<String, Object> body, String instanceId, String projectCode) {
        SlowSqlEvent event = new SlowSqlEvent();
        event.setSource("http_capture");
        event.setSourceDetail("HTTP:" + body.getOrDefault("applicationName", "unknown"));
        event.setInstanceId(instanceId);
        event.setProjectCode(projectCode);
        event.setSqlText((String) body.get("sqlText"));
        event.setCapturedAt(LocalDateTime.now());

        SlowSqlEvent.EventMetrics m = new SlowSqlEvent.EventMetrics();
        m.setQueryTimeSec(toDouble(body.get("queryTimeSec")));
        m.setLockTimeSec(toDouble(body.get("lockTimeSec")));
        m.setRowsExamined(toLong(body.get("rowsExamined")));
        m.setRowsSent(toLong(body.get("rowsSent")));
        event.setMetrics(m);

        if (body.containsKey("traceId") || body.containsKey("applicationName")) {
            SlowSqlEvent.EventContext ctx = new SlowSqlEvent.EventContext();
            ctx.setTraceId((String) body.get("traceId"));
            ctx.setApplicationName((String) body.get("applicationName"));
            event.setContext(ctx);
        }

        event.computeFingerprint();
        return event;
    }

    /**
     * 从用户手动诊断构建事件（无 metrics）。
     */
    public SlowSqlEvent fromManual(String sql, String instanceId, String projectCode) {
        SlowSqlEvent event = new SlowSqlEvent();
        event.setSource("manual");
        event.setSourceDetail("user-input");
        event.setInstanceId(instanceId);
        event.setProjectCode(projectCode);
        event.setSqlText(sql);
        event.setCapturedAt(LocalDateTime.now());
        event.computeFingerprint();
        return event;
    }

    // ===== 辅助 =====

    private double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private LocalDateTime toLocalDateTime(Object v) {
        if (v instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof java.time.LocalDateTime ldt) return ldt;
        if (v instanceof String s) {
            try { return LocalDateTime.parse(s.replace(" ", "T")); } catch (Exception e) { return LocalDateTime.now(); }
        }
        return LocalDateTime.now();
    }
}

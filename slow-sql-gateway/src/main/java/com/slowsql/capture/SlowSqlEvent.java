package com.slowsql.capture;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * 统一事件格式——所有采集入口和手动诊断归一化为此格式。
 * <p>
 * 此后下游所有组件（去重、诊断队列、Agent）只认这一种数据结构。
 */
public class SlowSqlEvent {

    // ===== 来源信息 =====
    private String source;             // slow_log_table / slow_log_file / http_capture / manual / performance_schema
    private String sourceDetail;       // 来源详情，如 "mysql.slow_log@10.0.1.1:3306"
    private LocalDateTime capturedAt;

    // ===== 目标定位 =====
    private String instanceId;         // 目标 MySQL 实例 ID
    private String projectCode;        // 项目 code
    private String dbName;             // SQL 所在的库名（可为 null）

    // ===== SQL 数据 =====
    private String sqlText;
    private String fingerprint;        // MD5(instanceId + ":" + 标准化SQL)

    // ===== 采集指标（手动诊断时全为 null） =====
    private EventMetrics metrics;

    // ===== 上下文（应用上报特有） =====
    private EventContext context;

    public SlowSqlEvent() {}

    // ===== 指纹计算 =====

    /**
     * 计算并设置指纹 = MD5(instanceId + ":" + 标准化SQL)。
     * 标准化规则：去除多余空白、转小写、移除尾部空格。
     */
    public void computeFingerprint() {
        String normalized = normalizeSql(sqlText);
        String raw = instanceId + ":" + normalized;
        this.fingerprint = md5(raw);
    }

    static String normalizeSql(String sql) {
        if (sql == null) return "";
        return sql.replaceAll("\\s+", " ")
                  .trim()
                  .toLowerCase()
                  .replaceAll("\\s*,\\s*", ",")
                  .replaceAll("\\s*=\\s*", "=")
                  .replaceAll("\\s*\\(", "(")
                  .replaceAll("\\)\\s*", ")");
    }

    static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return String.format("%032x", new BigInteger(1, digest));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    // ===== 内嵌类 =====

    public static class EventMetrics {
        private double queryTimeSec;
        private double lockTimeSec;
        private long rowsExamined;
        private long rowsSent;

        public double getQueryTimeSec() { return queryTimeSec; }
        public void setQueryTimeSec(double queryTimeSec) { this.queryTimeSec = queryTimeSec; }

        public double getLockTimeSec() { return lockTimeSec; }
        public void setLockTimeSec(double lockTimeSec) { this.lockTimeSec = lockTimeSec; }

        public long getRowsExamined() { return rowsExamined; }
        public void setRowsExamined(long rowsExamined) { this.rowsExamined = rowsExamined; }

        public long getRowsSent() { return rowsSent; }
        public void setRowsSent(long rowsSent) { this.rowsSent = rowsSent; }
    }

    public static class EventContext {
        private String applicationName;
        private String traceId;

        public String getApplicationName() { return applicationName; }
        public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
    }

    // ===== getters / setters =====

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getSourceDetail() { return sourceDetail; }
    public void setSourceDetail(String sourceDetail) { this.sourceDetail = sourceDetail; }

    public LocalDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(LocalDateTime capturedAt) { this.capturedAt = capturedAt; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }

    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }

    public String getSqlText() { return sqlText; }
    public void setSqlText(String sqlText) { this.sqlText = sqlText; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public EventMetrics getMetrics() { return metrics; }
    public void setMetrics(EventMetrics metrics) { this.metrics = metrics; }

    public EventContext getContext() { return context; }
    public void setContext(EventContext context) { this.context = context; }
}

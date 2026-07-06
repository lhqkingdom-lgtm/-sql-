package com.slowsql.capture;

import java.time.LocalDateTime;

/**
 * 采集到的慢 SQL 记录实体。
 */
public class CapturedSql {

    private Long id;
    private String sqlText;
    private String databaseName;
    private String instanceId;
    private String projectCode;
    private double queryTimeSec;
    private double lockTimeSec;
    private long rowsExamined;
    private long rowsSent;
    private String fingerprint;
    private String source;
    private int occurrenceCount;
    private String diagnosisReport;
    private String severity;          // P0 / P1 / P2
    private LocalDateTime capturedAt;

    /** 从统一事件构建采集记录 */
    public static CapturedSql fromEvent(SlowSqlEvent event) {
        CapturedSql c = new CapturedSql();
        c.sqlText = event.getSqlText();
        c.databaseName = event.getDbName();
        c.instanceId = event.getInstanceId();
        c.projectCode = event.getProjectCode();
        c.fingerprint = event.getFingerprint();
        c.source = event.getSource();
        c.capturedAt = event.getCapturedAt() != null ? event.getCapturedAt() : LocalDateTime.now();
        if (event.getMetrics() != null) {
            c.queryTimeSec = event.getMetrics().getQueryTimeSec();
            c.lockTimeSec = event.getMetrics().getLockTimeSec();
            c.rowsExamined = event.getMetrics().getRowsExamined();
            c.rowsSent = event.getMetrics().getRowsSent();
        }
        return c;
    }

    // ===== getters / setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSqlText() { return sqlText; }
    public void setSqlText(String sqlText) { this.sqlText = sqlText; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }

    public double getQueryTimeSec() { return queryTimeSec; }
    public void setQueryTimeSec(double queryTimeSec) { this.queryTimeSec = queryTimeSec; }

    public double getLockTimeSec() { return lockTimeSec; }
    public void setLockTimeSec(double lockTimeSec) { this.lockTimeSec = lockTimeSec; }

    public long getRowsExamined() { return rowsExamined; }
    public void setRowsExamined(long rowsExamined) { this.rowsExamined = rowsExamined; }

    public long getRowsSent() { return rowsSent; }
    public void setRowsSent(long rowsSent) { this.rowsSent = rowsSent; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public int getOccurrenceCount() { return occurrenceCount; }
    public void setOccurrenceCount(int occurrenceCount) { this.occurrenceCount = occurrenceCount; }

    public String getDiagnosisReport() { return diagnosisReport; }
    public void setDiagnosisReport(String diagnosisReport) { this.diagnosisReport = diagnosisReport; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public LocalDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(LocalDateTime capturedAt) { this.capturedAt = capturedAt; }
}

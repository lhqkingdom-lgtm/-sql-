package com.slowsql.persistence;

import java.time.LocalDateTime;

/**
 * 诊断记录实体。
 */
public class DiagnosisRecord {

    private Long id;
    private String taskId;
    private String sessionId;
    private String instanceId;
    private String projectCode;
    private String originalSql;
    private String cleanSql;
    private String tableNames;         // JSON数组字符串
    private String report;
    private String status;             // PENDING / RUNNING / COMPLETED / FAILED / DEAD
    private String errorMessage;
    private long durationMs;
    private int toolCallCount;
    private String source;             // manual / slow_log_table / slow_log_file / http_capture / performance_schema
    private String fingerprint;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== 状态常量 =====
    public static final String STATUS_PENDING   = "PENDING";
    public static final String STATUS_RUNNING   = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED    = "FAILED";
    public static final String STATUS_DEAD      = "DEAD";

    public static DiagnosisRecord create(String taskId, String sessionId, String instanceId,
                                          String projectCode, String source) {
        DiagnosisRecord r = new DiagnosisRecord();
        r.taskId = taskId;
        r.sessionId = sessionId;
        r.instanceId = instanceId;
        r.projectCode = projectCode;
        r.source = source;
        r.status = STATUS_PENDING;
        r.createdAt = LocalDateTime.now();
        return r;
    }

    // ===== getters / setters =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getProjectCode() { return projectCode; }
    public void setProjectCode(String projectCode) { this.projectCode = projectCode; }

    public String getOriginalSql() { return originalSql; }
    public void setOriginalSql(String originalSql) { this.originalSql = originalSql; }

    public String getCleanSql() { return cleanSql; }
    public void setCleanSql(String cleanSql) { this.cleanSql = cleanSql; }

    public String getTableNames() { return tableNames; }
    public void setTableNames(String tableNames) { this.tableNames = tableNames; }

    public String getReport() { return report; }
    public void setReport(String report) { this.report = report; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public int getToolCallCount() { return toolCallCount; }
    public void setToolCallCount(int toolCallCount) { this.toolCallCount = toolCallCount; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

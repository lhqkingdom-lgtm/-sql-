package com.slowsql.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 诊断记录持久化层。内部全量 try-catch，失败仅记 WARN 不抛异常。
 */
@Repository
public class DiagnosisRecordRepository {

    private static final Logger log = LoggerFactory.getLogger(DiagnosisRecordRepository.class);

    private final JdbcTemplate jdbc;

    public DiagnosisRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<DiagnosisRecord> ROW_MAPPER = (ResultSet rs, int rowNum) -> {
        DiagnosisRecord r = new DiagnosisRecord();
        r.setId(rs.getLong("id"));
        r.setTaskId(rs.getString("task_id"));
        r.setSessionId(rs.getString("session_id"));
        r.setInstanceId(rs.getString("instance_id"));
        r.setProjectCode(rs.getString("project_code"));
        r.setOriginalSql(rs.getString("original_sql"));
        r.setCleanSql(rs.getString("clean_sql"));
        r.setTableNames(rs.getString("table_names"));
        r.setReport(rs.getString("report"));
        r.setStatus(rs.getString("status"));
        r.setErrorMessage(rs.getString("error_message"));
        r.setDurationMs(rs.getLong("duration_ms"));
        r.setToolCallCount(rs.getInt("tool_call_count"));
        r.setSource(rs.getString("source"));
        r.setFingerprint(rs.getString("fingerprint"));
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) r.setCreatedAt(created.toLocalDateTime());
        Timestamp updated = rs.getTimestamp("updated_at");
        if (updated != null) r.setUpdatedAt(updated.toLocalDateTime());
        return r;
    };

    public void save(DiagnosisRecord record) {
        try {
            if (record.getCreatedAt() == null) record.setCreatedAt(LocalDateTime.now());

            jdbc.update("""
                    INSERT INTO diagnosis_record
                    (task_id, session_id, instance_id, project_code, original_sql, clean_sql,
                     table_names, report, status, error_message, duration_ms, tool_call_count,
                     source, fingerprint, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      status = VALUES(status),
                      report = VALUES(report),
                      error_message = VALUES(error_message),
                      duration_ms = VALUES(duration_ms),
                      tool_call_count = VALUES(tool_call_count),
                      updated_at = VALUES(updated_at)
                    """,
                    record.getTaskId(), record.getSessionId(), record.getInstanceId(),
                    record.getProjectCode(), record.getOriginalSql(), record.getCleanSql(),
                    record.getTableNames(), record.getReport(), record.getStatus(),
                    record.getErrorMessage(), record.getDurationMs(), record.getToolCallCount(),
                    record.getSource(), record.getFingerprint(),
                    Timestamp.valueOf(record.getCreatedAt()),
                    record.getUpdatedAt() != null ? Timestamp.valueOf(record.getUpdatedAt()) : null);
        } catch (Exception e) {
            log.warn("诊断记录保存失败 (不影响主流程): {}", e.getMessage());
        }
    }

    public DiagnosisRecord findByTaskId(String taskId) {
        try {
            List<DiagnosisRecord> list = jdbc.query(
                    "SELECT * FROM diagnosis_record WHERE task_id = ?", ROW_MAPPER, taskId);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            log.warn("查询诊断记录失败: {}", e.getMessage());
            return null;
        }
    }

    public List<DiagnosisRecord> findBySessionId(String sessionId, int limit) {
        try {
            return jdbc.query(
                    "SELECT * FROM diagnosis_record WHERE session_id = ? ORDER BY created_at DESC LIMIT ?",
                    ROW_MAPPER, sessionId, limit);
        } catch (Exception e) {
            log.warn("查询诊断历史失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 查同指纹最近一次诊断（用于缓存重建）。
     */
    public DiagnosisRecord findLatestByFingerprint(String fingerprint, int withinMinutes) {
        try {
            List<DiagnosisRecord> list = jdbc.query(
                    "SELECT * FROM diagnosis_record WHERE fingerprint = ? AND status = 'COMPLETED'" +
                    " AND created_at > DATE_SUB(NOW(), INTERVAL ? MINUTE)" +
                    " ORDER BY created_at DESC LIMIT 1",
                    ROW_MAPPER, fingerprint, withinMinutes);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查出状态为 RUNNING 但 updated_at 超过指定秒数的任务（超时检测）。
     */
    public List<DiagnosisRecord> findStaleRunningTasks(int staleSeconds) {
        try {
            return jdbc.query(
                    "SELECT * FROM diagnosis_record WHERE status = 'RUNNING'" +
                    " AND updated_at < DATE_SUB(NOW(), INTERVAL ? SECOND)",
                    ROW_MAPPER, staleSeconds);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 找出 Redis 有但 DB 缺失的任务（补偿任务扫描用）。
     * 返回最近N分钟内的 COMPLETED 记录的 taskId 集合。
     */
    public List<String> findCompletedTaskIds(int withinMinutes) {
        try {
            return jdbc.queryForList(
                    "SELECT task_id FROM diagnosis_record WHERE status = 'COMPLETED'" +
                    " AND created_at > DATE_SUB(NOW(), INTERVAL ? MINUTE)",
                    String.class, withinMinutes);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 查询诊断历史（可筛选项目/实例） */
    public List<DiagnosisRecord> findHistory(String projectCode, String instanceId, int limit) {
        try {
            StringBuilder sql = new StringBuilder("SELECT * FROM diagnosis_record WHERE 1=1");
            java.util.List<Object> params = new java.util.ArrayList<>();
            if (projectCode != null && !projectCode.isEmpty()) { sql.append(" AND project_code = ?"); params.add(projectCode); }
            if (instanceId != null && !instanceId.isEmpty()) { sql.append(" AND instance_id = ?"); params.add(instanceId); }
            sql.append(" ORDER BY created_at DESC LIMIT ?");
            params.add(limit);
            return jdbc.query(sql.toString(), ROW_MAPPER, params.toArray());
        } catch (Exception e) { return List.of(); }
    }

    /** 统计诊断数 */
    public int countByProject(String projectCode) {
        try {
            if (projectCode == null || projectCode.isEmpty())
                return jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_record", Integer.class);
            return jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_record WHERE project_code = ?", Integer.class, projectCode);
        } catch (Exception e) { return 0; }
    }

    /** P0 严重度计数 */
    public int countP0(String projectCode) {
        try {
            if (projectCode == null || projectCode.isEmpty())
                return jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_record WHERE status IN ('COMPLETED','FAILED') AND report IS NOT NULL", Integer.class);
            return jdbc.queryForObject("SELECT COUNT(*) FROM diagnosis_record WHERE project_code = ? AND status IN ('COMPLETED','FAILED') AND report IS NOT NULL", Integer.class, projectCode);
        } catch (Exception e) { return 0; }
    }

    /**
     * 清理过期记录。
     */
    public int deleteOlderThan(int days) {
        try {
            return jdbc.update(
                    "DELETE FROM diagnosis_record WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)", days);
        } catch (Exception e) {
            log.warn("清理过期诊断记录失败: {}", e.getMessage());
            return 0;
        }
    }
}

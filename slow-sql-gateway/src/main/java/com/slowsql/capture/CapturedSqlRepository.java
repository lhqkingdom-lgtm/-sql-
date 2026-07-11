package com.slowsql.capture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 采集记录持久化层。内部全量 try-catch，失败仅记 WARN。
 */
@Repository
public class CapturedSqlRepository {

    private static final Logger log = LoggerFactory.getLogger(CapturedSqlRepository.class);

    private final JdbcTemplate jdbc;

    public CapturedSqlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<CapturedSql> ROW_MAPPER = (rs, rowNum) -> {
        CapturedSql c = new CapturedSql();
        c.setId(rs.getLong("id"));
        c.setSqlText(rs.getString("sql_text"));
        c.setDatabaseName(rs.getString("database_name"));
        c.setInstanceId(rs.getString("instance_id"));
        c.setProjectCode(rs.getString("project_code"));
        c.setQueryTimeSec(rs.getDouble("query_time_sec"));
        c.setLockTimeSec(rs.getDouble("lock_time_sec"));
        c.setRowsExamined(rs.getLong("rows_examined"));
        c.setRowsSent(rs.getLong("rows_sent"));
        c.setFingerprint(rs.getString("fingerprint"));
        c.setSource(rs.getString("source"));
        c.setOccurrenceCount(rs.getInt("occurrence_count"));
        c.setDiagnosisReport(rs.getString("diagnosis_report"));
        c.setSeverity(rs.getString("severity"));
        Timestamp ts = rs.getTimestamp("captured_at");
        if (ts != null) c.setCapturedAt(ts.toLocalDateTime());
        return c;
    };

    /**
     * upsert：按指纹唯一，新记录插入，已存在则更新出现次数和耗时。
     */
    public void upsert(CapturedSql sql) {
        try {
            jdbc.update("""
                    INSERT INTO captured_sql
                    (sql_text, database_name, instance_id, project_code, query_time_sec,
                     lock_time_sec, rows_examined, rows_sent, fingerprint, source,
                     occurrence_count, diagnosis_report, severity, captured_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE
                      occurrence_count = occurrence_count + 1,
                      query_time_sec = (query_time_sec + VALUES(query_time_sec)) / 2,
                      lock_time_sec  = (lock_time_sec + VALUES(lock_time_sec)) / 2,
                      rows_examined  = GREATEST(rows_examined, VALUES(rows_examined)),
                      diagnosis_report = COALESCE(VALUES(diagnosis_report), diagnosis_report),
                      severity = COALESCE(VALUES(severity), severity)
                    """,
                    sql.getSqlText(), sql.getDatabaseName(), sql.getInstanceId(),
                    sql.getProjectCode(), sql.getQueryTimeSec(), sql.getLockTimeSec(),
                    sql.getRowsExamined(), sql.getRowsSent(), sql.getFingerprint(),
                    sql.getSource(), sql.getDiagnosisReport(), sql.getSeverity());
        } catch (Exception e) {
            log.warn("采集记录 upsert 失败: {}", e.getMessage());
        }
    }

    public List<CapturedSql> findAll(int limit) {
        try {
            return jdbc.query(
                    "SELECT * FROM captured_sql ORDER BY captured_at DESC LIMIT ?",
                    ROW_MAPPER, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<CapturedSql> findByInstance(String instanceId, int limit) {
        try {
            return jdbc.query(
                    "SELECT * FROM captured_sql WHERE instance_id = ? ORDER BY captured_at DESC LIMIT ?",
                    ROW_MAPPER, instanceId, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    public CapturedSql findById(Long id) {
        try {
            List<CapturedSql> list = jdbc.query(
                    "SELECT * FROM captured_sql WHERE id = ?", ROW_MAPPER, id);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 回写诊断报告到采集记录 */
    public void updateReport(CapturedSql cs) {
        try {
            jdbc.update(
                    "UPDATE captured_sql SET diagnosis_report = ?, severity = ? WHERE id = ?",
                    cs.getDiagnosisReport(), cs.getSeverity(), cs.getId());
        } catch (Exception e) {
            log.warn("回写诊断报告失败: {}", e.getMessage());
        }
    }

    public CapturedSql findByFingerprint(String fingerprint) {
        try {
            List<CapturedSql> list = jdbc.query(
                    "SELECT * FROM captured_sql WHERE fingerprint = ?", ROW_MAPPER, fingerprint);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    public int deleteById(Long id) {
        try {
            return jdbc.update("DELETE FROM captured_sql WHERE id = ?", id);
        } catch (Exception e) {
            return 0;
        }
    }

    public int deleteAll() {
        try {
            return jdbc.update("DELETE FROM captured_sql");
        } catch (Exception e) {
            return 0;
        }
    }

    // ===== Dashboard 聚合查询 =====

    public int countToday() { return countToday(null); }
    public int countToday(String projectCode) {
        try {
            if (projectCode == null || projectCode.isEmpty())
                return jdbc.queryForObject("SELECT COUNT(*) FROM captured_sql WHERE captured_at >= CURDATE()", Integer.class);
            return jdbc.queryForObject("SELECT COUNT(*) FROM captured_sql WHERE captured_at >= CURDATE() AND project_code = ?", Integer.class, projectCode);
        } catch (Exception e) { return 0; }
    }

    public int countTotal() { return countTotal(null); }
    public int countTotal(String projectCode) {
        try {
            if (projectCode == null || projectCode.isEmpty())
                return jdbc.queryForObject("SELECT COUNT(*) FROM captured_sql", Integer.class);
            return jdbc.queryForObject("SELECT COUNT(*) FROM captured_sql WHERE project_code = ?", Integer.class, projectCode);
        } catch (Exception e) { return 0; }
    }

    public List<Map<String, Object>> countBySource() { return countBySource(null); }
    public List<Map<String, Object>> countBySource(String projectCode) {
        try {
            if (projectCode == null || projectCode.isEmpty())
                return jdbc.queryForList("SELECT source, COUNT(*) AS cnt FROM captured_sql GROUP BY source");
            return jdbc.queryForList("SELECT source, COUNT(*) AS cnt FROM captured_sql WHERE project_code = ? GROUP BY source", projectCode);
        } catch (Exception e) { return List.of(); }
    }

    public List<CapturedSql> findTopFrequent(int limit) { return findTopFrequent(null, limit); }
    public List<CapturedSql> findTopFrequent(String projectCode, int limit) {
        try {
            if (projectCode == null || projectCode.isEmpty())
                return jdbc.query("SELECT * FROM captured_sql ORDER BY occurrence_count DESC LIMIT ?", ROW_MAPPER, limit);
            return jdbc.query("SELECT * FROM captured_sql WHERE project_code = ? ORDER BY occurrence_count DESC LIMIT ?", ROW_MAPPER, projectCode, limit);
        } catch (Exception e) { return List.of(); }
    }

    // ===== 筛选查询 =====

    public List<CapturedSql> findByProjectCode(String projectCode, int limit) {
        try {
            return jdbc.query(
                "SELECT * FROM captured_sql WHERE project_code = ? ORDER BY captured_at DESC LIMIT ?",
                ROW_MAPPER, projectCode, limit);
        } catch (Exception e) { return List.of(); }
    }

    /** 指纹聚合——按 fingerprint 分组，显示出现次数、平均耗时、最大耗时 */
    public List<Map<String, Object>> aggregatedByFingerprint(String projectCode, int limit) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT fingerprint, MIN(sql_text) as sample_sql, COUNT(*) as cnt, " +
                "ROUND(AVG(query_time_sec),2) as avg_time, ROUND(MAX(query_time_sec),2) as max_time, " +
                "MAX(severity) as top_severity, MAX(diagnosis_report) as has_report, " +
                "MAX(instance_id) as instance_id, MAX(project_code) as project_code " +
                "FROM captured_sql WHERE 1=1");
            java.util.List<Object> params = new java.util.ArrayList<>();
            if (projectCode != null && !projectCode.isEmpty()) {
                sql.append(" AND project_code = ?"); params.add(projectCode);
            }
            sql.append(" GROUP BY fingerprint ORDER BY cnt DESC, avg_time DESC LIMIT ?");
            params.add(limit);
            return jdbc.queryForList(sql.toString(), params.toArray());
        } catch (Exception e) { return List.of(); }
    }

    public int countP0(String projectCode) {
        try {
            if (projectCode == null || projectCode.isEmpty())
                return jdbc.queryForObject("SELECT COUNT(*) FROM captured_sql WHERE severity = 'P0'", Integer.class);
            return jdbc.queryForObject("SELECT COUNT(*) FROM captured_sql WHERE project_code = ? AND severity = 'P0'", Integer.class, projectCode);
        } catch (Exception e) { return 0; }
    }

    public List<Map<String, Object>> dailyTrend(String projectCode, int days) {
        try {
            String sql = "SELECT DATE(captured_at) as day, COUNT(*) as cnt FROM captured_sql WHERE captured_at >= DATE_SUB(CURDATE(), INTERVAL ? DAY)";
            java.util.List<Object> params = new java.util.ArrayList<>();
            params.add(days);
            if (projectCode != null && !projectCode.isEmpty()) {
                sql += " AND project_code = ?";
                params.add(projectCode);
            }
            sql += " GROUP BY DATE(captured_at) ORDER BY day";
            return jdbc.queryForList(sql, params.toArray());
        } catch (Exception e) { return List.of(); }
    }

    public List<CapturedSql> findByFilters(String projectCode, String instanceId, String severity,
                                            String startTime, String endTime, int offset, int size) {
        try {
            StringBuilder sql = new StringBuilder("SELECT * FROM captured_sql WHERE 1=1");
            java.util.List<Object> params = new java.util.ArrayList<>();
            if (projectCode != null && !projectCode.isEmpty()) { sql.append(" AND project_code = ?"); params.add(projectCode); }
            if (instanceId != null && !instanceId.isEmpty()) { sql.append(" AND instance_id = ?"); params.add(instanceId); }
            if (severity != null && !severity.isEmpty()) { sql.append(" AND severity = ?"); params.add(severity); }
            if (startTime != null && !startTime.isEmpty()) { sql.append(" AND captured_at >= ?"); params.add(startTime); }
            if (endTime != null && !endTime.isEmpty()) { sql.append(" AND captured_at <= ?"); params.add(endTime); }
            sql.append(" ORDER BY captured_at DESC LIMIT ?, ?");
            params.add(offset); params.add(size);
            return jdbc.query(sql.toString(), ROW_MAPPER, params.toArray());
        } catch (Exception e) { return List.of(); }
    }

    public int countByFilters(String projectCode, String instanceId, String severity,
                              String startTime, String endTime) {
        try {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM captured_sql WHERE 1=1");
            java.util.List<Object> params = new java.util.ArrayList<>();
            if (projectCode != null && !projectCode.isEmpty()) { sql.append(" AND project_code = ?"); params.add(projectCode); }
            if (instanceId != null && !instanceId.isEmpty()) { sql.append(" AND instance_id = ?"); params.add(instanceId); }
            if (severity != null && !severity.isEmpty()) { sql.append(" AND severity = ?"); params.add(severity); }
            if (startTime != null && !startTime.isEmpty()) { sql.append(" AND captured_at >= ?"); params.add(startTime); }
            if (endTime != null && !endTime.isEmpty()) { sql.append(" AND captured_at <= ?"); params.add(endTime); }
            Integer cnt = jdbc.queryForObject(sql.toString(), Integer.class, params.toArray());
            return cnt != null ? cnt : 0;
        } catch (Exception e) { return 0; }
    }
}

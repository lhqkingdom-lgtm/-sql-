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

    public int countToday() {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM captured_sql WHERE captured_at >= CURDATE()", Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public int countTotal() {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM captured_sql", Integer.class);
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public List<Map<String, Object>> countBySource() {
        try {
            return jdbc.queryForList(
                    "SELECT source, COUNT(*) AS cnt FROM captured_sql GROUP BY source");
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<CapturedSql> findTopFrequent(int limit) {
        try {
            return jdbc.query(
                    "SELECT * FROM captured_sql ORDER BY occurrence_count DESC LIMIT ?",
                    ROW_MAPPER, limit);
        } catch (Exception e) {
            return List.of();
        }
    }
}

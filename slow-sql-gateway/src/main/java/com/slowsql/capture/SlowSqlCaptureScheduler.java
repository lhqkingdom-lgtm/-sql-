package com.slowsql.capture;

import com.slowsql.config.DataSourceManager;
import com.slowsql.config.SqlMonitorProperties;
import com.slowsql.gateway.DiagnosisTaskProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component
public class SlowSqlCaptureScheduler {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlCaptureScheduler.class);

    private final DataSourceManager dataSourceManager;
    private final SqlMonitorProperties properties;
    private final FingerprintDedupService dedupService;
    private final CapturedSqlRepository repository;
    private final DiagnosisTaskProducer taskProducer;
    private final ImNotificationService notifier;
    private final EventNormalizer normalizer;

    private final Map<String, LocalDateTime> lastCheckMap = new HashMap<>();

    public SlowSqlCaptureScheduler(DataSourceManager dataSourceManager,
                                    SqlMonitorProperties properties,
                                    FingerprintDedupService dedupService,
                                    CapturedSqlRepository repository,
                                    DiagnosisTaskProducer taskProducer,
                                    ImNotificationService notifier,
                                    EventNormalizer normalizer) {
        this.dataSourceManager = dataSourceManager;
        this.properties = properties;
        this.dedupService = dedupService;
        this.repository = repository;
        this.taskProducer = taskProducer;
        this.notifier = notifier;
        this.normalizer = normalizer;
    }

    @Scheduled(fixedDelay = 60_000)
    public void poll() {
        SqlMonitorProperties.CaptureConfig cfg = properties.getCapture();
        if (!cfg.isEnabled()) return;

        for (String instanceId : dataSourceManager.getReadyInstanceIds()) {
            try {
                String projectCode = dataSourceManager.findProjectCode(instanceId);
                List<Map<String, Object>> rows = fetchSlowLog(instanceId);
                if (rows == null || rows.isEmpty()) continue;

                int sent = 0;
                int maxPerRound = cfg.getMaxPerRound();
                for (Map<String, Object> row : rows) {
                    if (sent >= maxPerRound) break;

                    SlowSqlEvent event = normalizer.fromSlowLogRow(row, instanceId, projectCode);
                    if (event.getSqlText() == null) continue;
                    if (isNoise(event.getSqlText())) continue;

                    SlowSqlSeverity severity = SlowSqlSeverity.from(event);
                    if (!dedupService.tryRegister(event.getFingerprint())) {
                        // 命中缓存 → 更新出现次数 + 复用报告
                        CapturedSql cached = repository.findByFingerprint(event.getFingerprint());
                        if (cached != null) {
                            cached.setOccurrenceCount(cached.getOccurrenceCount() + 1);
                            repository.upsert(cached);
                        }
                        String cachedReport = dedupService.getCachedReport(event.getFingerprint());
                        if (cachedReport != null) {
                            notifier.notify(event.getFingerprint(), event.getSqlText(),
                                    "已有诊断:\n" + cachedReport,
                                    event.getMetrics() != null ? event.getMetrics().getQueryTimeSec() : 0,
                                    event.getMetrics() != null ? event.getMetrics().getRowsExamined() : 0,
                                    event.getDbName() != null ? event.getDbName() : "unknown");
                        }
                        continue;
                    }

                    // 新指纹 → 持久化 + 投递诊断
                    CapturedSql cs = CapturedSql.fromEvent(event);
                    cs.setSeverity(severity.name());
                    repository.upsert(cs);

                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("taskId", UUID.randomUUID().toString());
                    msg.put("instanceId", instanceId);
                    msg.put("projectCode", projectCode);
                    msg.put("enrichedPrompt", SlowSqlCaptureRouter.buildDiagnosisContext(event));
                    msg.put("fingerprint", event.getFingerprint());
                    msg.put("source", "slow_log_table");
                    msg.put("timestamp", LocalDateTime.now().toString());
                    taskProducer.sendNormal(msg);

                    if (severity == SlowSqlSeverity.P0) {
                        notifier.notify(event.getFingerprint(), event.getSqlText(),
                                severity.emoji + " [" + severity.label + "] 新发现慢查询",
                                event.getMetrics() != null ? event.getMetrics().getQueryTimeSec() : 0,
                                event.getMetrics() != null ? event.getMetrics().getRowsExamined() : 0,
                                event.getDbName() != null ? event.getDbName() : "unknown");
                    }
                    sent++;
                }
            } catch (Exception e) {
                log.warn("采集异常 [{}]: {}", instanceId, e.getMessage());
            }
        }
    }

    private List<Map<String, Object>> fetchSlowLog(String instanceId) {
        try {
            LocalDateTime last = lastCheckMap.getOrDefault(instanceId,
                    LocalDateTime.now().minusMinutes(10));
            String sql = "SELECT sql_text, query_time, lock_time, rows_examined, rows_sent, start_time " +
                         "FROM mysql.slow_log WHERE start_time > ? ORDER BY start_time DESC LIMIT 200";
            var jt = dataSourceManager.getMonitoringTemplate(instanceId);
            List<Map<String, Object>> rows = jt.queryForList(sql,
                    java.sql.Timestamp.valueOf(last));
            lastCheckMap.put(instanceId, LocalDateTime.now());
            return rows;
        } catch (Exception e) {
            log.debug("慢日志读取失败 [{}] (可能未开启slow_log): {}", instanceId, e.getMessage());
            return List.of();
        }
    }

    private boolean isNoise(String sql) {
        if (sql.length() < 10) return true;
        String upper = sql.toUpperCase().trim();
        if (upper.startsWith("SET ") || upper.startsWith("SHOW ") || upper.startsWith("QUIT") ||
            upper.startsWith("CONNECT") || upper.startsWith("ADMIN") || upper.startsWith("XA ") ||
            upper.startsWith("PING") || upper.startsWith("INIT DB") || upper.startsWith("BEGIN") ||
            upper.startsWith("COMMIT") || upper.startsWith("ROLLBACK")) return true;
        if (upper.contains("SELECT @@") || upper.contains("SELECT 1") ||
            upper.contains("SELECT DATABASE()") || upper.contains("SELECT VERSION()") ||
            upper.contains("SELECT CONNECTION_ID()")) return true;
        if (upper.contains("INFORMATION_SCHEMA") || upper.contains("MYSQL.") ||
            upper.contains("PERFORMANCE_SCHEMA")) return true;
        if (upper.contains("DIAGNOSIS_RECORD") || upper.contains("CAPTURED_SQL") ||
            upper.contains("RAG_DOCUMENT")) return true;
        return !(upper.startsWith("SELECT") || upper.startsWith("WITH") ||
                 upper.startsWith("EXPLAIN") || upper.startsWith("DESCRIBE"));
    }
}

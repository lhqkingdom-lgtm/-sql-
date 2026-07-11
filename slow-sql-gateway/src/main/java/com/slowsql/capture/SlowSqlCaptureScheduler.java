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
    private final HttpCaptureController httpController;
    private final CaptureStatusController captureStatus;
    private final List<SlowLogFileSource> fileSources = new ArrayList<>();

    private final Map<String, LocalDateTime> lastCheckMap = new java.util.concurrent.ConcurrentHashMap<>();

    public SlowSqlCaptureScheduler(DataSourceManager dataSourceManager,
                                    SqlMonitorProperties properties,
                                    FingerprintDedupService dedupService,
                                    CapturedSqlRepository repository,
                                    DiagnosisTaskProducer taskProducer,
                                    ImNotificationService notifier,
                                    EventNormalizer normalizer,
                                    HttpCaptureController httpController,
                                    CaptureStatusController captureStatus) {
        this.dataSourceManager = dataSourceManager;
        this.properties = properties;
        this.dedupService = dedupService;
        this.repository = repository;
        this.taskProducer = taskProducer;
        this.notifier = notifier;
        this.normalizer = normalizer;
        this.httpController = httpController;
        this.captureStatus = captureStatus;

        // Initialize slow_log_file sources from yml config
        for (var src : properties.getCapture().getSources()) {
            if ("slow_log_file".equals(src.getType())) {
                var fileSrc = new SlowLogFileSource(src);
                if (fileSrc.isConfigured()) {
                    fileSources.add(fileSrc);
                    log.info("慢日志文件采集源已启用: {}", src.getPath());
                }
            }
            if ("http_endpoint".equals(src.getType())) {
                httpController.markConfigured();
                log.info("HTTP端点采集源已启用");
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void poll() {
        SqlMonitorProperties.CaptureConfig cfg = properties.getCapture();
        if (!cfg.isEnabled()) return;

        // === 采集源 1: HTTP 端点 ===
        if (httpController.isConfigured() && anyInstanceAllows("http_endpoint")) {
            try {
                List<SlowSqlEvent> httpEvents = httpController.collect();
                processEvents(httpEvents);
            } catch (Exception e) { log.warn("HTTP采集异常: {}", e.getMessage()); }
        }

        // === 采集源 2: 慢日志文件 ===
        if (anyInstanceAllows("slow_log_file")) {
        for (SlowLogFileSource fileSrc : fileSources) {
            try {
                List<SlowSqlEvent> fileEvents = fileSrc.collect();
                processEvents(fileEvents);
            } catch (Exception e) { log.warn("慢日志文件采集异常: {}", e.getMessage()); }
        }
        } // anyInstanceAllows("slow_log_file")

        // === 采集源 3: 慢日志表 ===
        for (String instanceId : dataSourceManager.getReadyInstanceIds()) {
            if (!captureStatus.isEnabled(instanceId)) continue;
            if (!captureStatus.isSourceEnabled(instanceId, "slow_log_table")) continue;
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
                        // 命中缓存 → upsert（SQL ON DUPLICATE KEY 自动 +1）
                        CapturedSql cached = repository.findByFingerprint(event.getFingerprint());
                        if (cached != null) {
                            repository.upsert(cached); // SQL already does occurrence_count + 1
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
                if (sent > 0) captureStatus.recordCollect(instanceId, sent);
            } catch (Exception e) {
                log.warn("采集异常 [{}]: {}", instanceId, e.getMessage());
                captureStatus.recordError(instanceId, e.getMessage());
            }
        }
    }

    /** 处理从 HTTP/慢日志文件 采集到的事件 */
    private void processEvents(List<SlowSqlEvent> events) {
        if (events == null || events.isEmpty()) return;
        int count = 0;
        for (SlowSqlEvent event : events) {
            if (event.getSqlText() == null || isNoise(event.getSqlText())) continue;
            String fp = event.getFingerprint() != null ? event.getFingerprint()
                    : CapturedSql.fingerprint(event.getSqlText());
            event.setFingerprint(fp);

            if (!dedupService.tryRegister(fp)) { continue; }

            CapturedSql cs = CapturedSql.fromEvent(event);
            cs.setSeverity(SlowSqlSeverity.from(event).name());
            repository.upsert(cs);

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("taskId", UUID.randomUUID().toString());
            msg.put("instanceId", event.getInstanceId() != null ? event.getInstanceId() : "");
            msg.put("projectCode", event.getProjectCode() != null ? event.getProjectCode() : "");
            msg.put("enrichedPrompt", SlowSqlCaptureRouter.buildDiagnosisContext(event));
            msg.put("fingerprint", fp);
            msg.put("source", event.getSource());
            msg.put("timestamp", LocalDateTime.now().toString());
            taskProducer.sendNormal(msg);
            count++;
        }
        if (count > 0) {
            log.info("采集源处理了 {} 条新SQL (已投递诊断)", count);
            // 记录到第一个有 instanceId 的事件对应的实例
            String iid = events.stream().filter(e -> e.getInstanceId() != null && !e.getInstanceId().isEmpty())
                    .findFirst().map(SlowSqlEvent::getInstanceId).orElse("unknown");
            captureStatus.recordCollect(iid, count);
        }
    }

    /* 已废弃，用 processEvents 替代 */
    @Deprecated
    /** 处理 HTTP/慢日志文件 采集到的 CapturedSql 批量 */
    private void processCapturedBatch(List<CapturedSql> batch) {
        if (batch == null || batch.isEmpty()) return;
        int count = 0;
        for (CapturedSql cs : batch) {
            if (cs.getSqlText() == null || isNoise(cs.getSqlText())) continue;
            String finger = cs.getFingerprint() != null ? cs.getFingerprint()
                    : CapturedSql.fingerprint(cs.getSqlText());
            cs.setFingerprint(finger);

            // 补充 projectCode
            if (cs.getInstanceId() != null && cs.getProjectCode() == null) {
                cs.setProjectCode(dataSourceManager.findProjectCode(cs.getInstanceId()));
            }

            if (!dedupService.tryRegister(finger)) {
                CapturedSql cached = repository.findByFingerprint(finger);
                if (cached != null) {
                    cached.setOccurrenceCount(cached.getOccurrenceCount() + 1);
                    repository.upsert(cached);
                }
                continue;
            }

            cs.setSeverity(evaluateSeverity(cs));
            repository.upsert(cs);

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("taskId", UUID.randomUUID().toString());
            msg.put("instanceId", cs.getInstanceId() != null ? cs.getInstanceId() : "");
            msg.put("projectCode", cs.getProjectCode() != null ? cs.getProjectCode() : "");
            msg.put("enrichedPrompt", "【自动采集】\n来源: " + cs.getSource()
                    + "\n耗时: " + cs.getQueryTimeSec() + "s\n扫描: " + cs.getRowsExamined()
                    + "行\n\n【待分析SQL】\n" + cs.getSqlText());
            msg.put("fingerprint", finger);
            msg.put("source", cs.getSource());
            msg.put("timestamp", LocalDateTime.now().toString());
            taskProducer.sendNormal(msg);
            count++;
        }
        if (count > 0) log.info("批量处理了 {} 条采集SQL (已投递诊断)", count);
    }

    private String evaluateSeverity(CapturedSql cs) {
        if (cs.getQueryTimeSec() >= 10.0 || cs.getRowsExamined() >= 1_000_000) return "P0";
        if (cs.getQueryTimeSec() >= 2.0 || cs.getRowsExamined() >= 100_000) return "P1";
        return "P2";
    }

    private boolean anyInstanceAllows(String sourceType) {
        return dataSourceManager.getReadyInstanceIds().stream()
                .anyMatch(id -> captureStatus.isSourceEnabled(id, sourceType));
    }

    private List<Map<String, Object>> fetchSlowLog(String instanceId) {
        try {
            // 首次轮询：仅记录时间锚点，不采集历史数据
            if (!lastCheckMap.containsKey(instanceId)) {
                lastCheckMap.put(instanceId, LocalDateTime.now());
                return List.of();
            }
            LocalDateTime last = lastCheckMap.get(instanceId);
            String sql = "SELECT sql_text, query_time, lock_time, rows_examined, rows_sent, start_time, db " +
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
        // 过滤调试查询
        if (upper.contains("SELECT SLEEP(") || upper.contains("SELECT COUNT(*) FROM SLOW_LOG") ||
            upper.contains("SHOW VARIABLES") || upper.contains("SHOW GLOBAL STATUS")) return true;
        if (upper.contains("INFORMATION_SCHEMA") || upper.contains("MYSQL.") ||
            upper.contains("PERFORMANCE_SCHEMA")) return true;
        if (upper.contains("DIAGNOSIS_RECORD") || upper.contains("CAPTURED_SQL") ||
            upper.contains("RAG_DOCUMENT")) return true;
        return !(upper.startsWith("SELECT") || upper.startsWith("WITH") ||
                 upper.startsWith("EXPLAIN") || upper.startsWith("DESCRIBE"));
    }
}

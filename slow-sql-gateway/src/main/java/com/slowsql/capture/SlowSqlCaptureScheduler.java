package com.slowsql.capture;

import com.slowsql.config.DataSourceManager;
import com.slowsql.config.SqlMonitorProperties;
import com.slowsql.gateway.DiagnosisTaskProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final DiagnosisCacheService diagnosisCache;
    private final List<SlowLogFileSource> fileSources = new ArrayList<>();

    /** PS 采集增量锚点：每个实例记录最后采集到的 TIMER_START（picoseconds） */
    private final Map<String, Long> lastTimerStartMap = new java.util.concurrent.ConcurrentHashMap<>();

    /** 文件源采集锚点：每个实例记录开启轮询的时间，只采集此后的慢日志 */
    private final Map<String, LocalDateTime> fileAnchorMap = new java.util.concurrent.ConcurrentHashMap<>();

    public SlowSqlCaptureScheduler(DataSourceManager dataSourceManager,
                                    SqlMonitorProperties properties,
                                    FingerprintDedupService dedupService,
                                    CapturedSqlRepository repository,
                                    DiagnosisTaskProducer taskProducer,
                                    ImNotificationService notifier,
                                    EventNormalizer normalizer,
                                    HttpCaptureController httpController,
                                    CaptureStatusController captureStatus,
                                    DiagnosisCacheService diagnosisCache) {
        this.dataSourceManager = dataSourceManager;
        this.properties = properties;
        this.dedupService = dedupService;
        this.repository = repository;
        this.taskProducer = taskProducer;
        this.notifier = notifier;
        this.normalizer = normalizer;
        this.httpController = httpController;
        this.captureStatus = captureStatus;
        this.diagnosisCache = diagnosisCache;

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

    /**
     * PS 轮询——由 CaptureStatusController 按实例动态调度。
     */
    public void pollPsInstance(String instanceId) {
        SqlMonitorProperties.CaptureConfig cfg = properties.getCapture();
        if (!cfg.isEnabled()) return;
        if (!captureStatus.isEnabled(instanceId)) return;
        if (!captureStatus.isSourceEnabled(instanceId, "slow_log_table")) return;

        try {
            String projectCode = dataSourceManager.findProjectCode(instanceId);
            List<Map<String, Object>> rows = fetchSlowLog(instanceId);
            if (rows == null || rows.isEmpty()) return;

            int sent = 0;
            int maxPerRound = cfg.getMaxPerRound();
            for (Map<String, Object> row : rows) {
                if (sent >= maxPerRound) break;

                SlowSqlEvent event = normalizer.fromSlowLogRow(row, instanceId, projectCode);
                if (event.getSqlText() == null) continue;
                if (isNoise(event.getSqlText())) continue;

                SlowSqlSeverity severity = SlowSqlSeverity.from(event);
                if (!dedupService.tryRegister(event.getFingerprint())) {
                    CapturedSql cached = repository.findByFingerprint(event.getFingerprint());
                    // 检查诊断缓存（7天），有则回写 report
                    String dedupCache = (severity != SlowSqlSeverity.P0)
                            ? diagnosisCache.checkCache(instanceId, event.getSqlText(), event.getFingerprint())
                            : null;
                    if (dedupCache != null && cached != null) {
                        cached.setDiagnosisReport(dedupCache);
                        log.info("诊断缓存命中(dedup): fingerprint={}", event.getFingerprint().substring(0, Math.min(8, event.getFingerprint().length())));
                    }
                    if (cached != null) {
                        repository.upsert(cached);
                    }
                    if (dedupCache != null) {
                        notifier.notify(event.getFingerprint(), event.getSqlText(),
                                "已有诊断(缓存):\n" + dedupCache,
                                event.getMetrics() != null ? event.getMetrics().getQueryTimeSec() : 0,
                                event.getMetrics() != null ? event.getMetrics().getRowsExamined() : 0,
                                event.getDbName() != null ? event.getDbName() : "unknown");
                    }
                    continue;
                }

                CapturedSql cs = CapturedSql.fromEvent(event);
                cs.setSeverity(severity.name());

                // 缓存检查：同指纹+同EXPLAIN → 复用报告
                String cachedReport = (severity != SlowSqlSeverity.P0)
                        ? diagnosisCache.checkCache(instanceId, event.getSqlText(), event.getFingerprint())
                        : null;
                if (cachedReport != null) {
                    cs.setDiagnosisReport(cachedReport);
                    log.info("诊断缓存命中: fingerprint={}", event.getFingerprint().substring(0, Math.min(8, event.getFingerprint().length())));
                }
                repository.upsert(cs);

                if (cachedReport == null) {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("taskId", UUID.randomUUID().toString());
                    msg.put("instanceId", instanceId);
                    msg.put("projectCode", projectCode);
                    msg.put("enrichedPrompt", SlowSqlCaptureRouter.buildDiagnosisContext(event));
                    msg.put("fingerprint", event.getFingerprint());
                    msg.put("source", "slow_log_table");
                    msg.put("timestamp", LocalDateTime.now().toString());
                    taskProducer.sendNormal(msg);
                }

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
            log.warn("PS采集异常 [{}]: {}", instanceId, e.getMessage());
            captureStatus.recordError(instanceId, e.getMessage());
        }
    }

    /**
     * 慢日志文件轮询——由 CaptureStatusController 按实例动态调度。
     */
    public void pollFileInstance(String instanceId) {
        SqlMonitorProperties.CaptureConfig cfg = properties.getCapture();
        if (!cfg.isEnabled()) return;
        if (!captureStatus.isEnabled(instanceId)) return;
        if (!captureStatus.isSourceEnabled(instanceId, "slow_log_file")) return;

        // 首次轮询：记录锚点时间，不采集历史
        if (!fileAnchorMap.containsKey(instanceId)) {
            fileAnchorMap.put(instanceId, LocalDateTime.now());
            log.info("文件源锚点已记录 [{}]: 只采集此后的慢日志", instanceId);
            return;
        }
        LocalDateTime anchor = fileAnchorMap.get(instanceId);

        for (SlowLogFileSource fileSrc : fileSources) {
            if (!instanceId.equals(fileSrc.getInstanceId())) continue;
            try {
                List<SlowSqlEvent> fileEvents = fileSrc.collect();
                // 过滤：只保留采集时间在锚点之后的事件
                List<SlowSqlEvent> filtered = fileEvents.stream()
                        .filter(e -> e.getCapturedAt() != null && e.getCapturedAt().isAfter(anchor))
                        .toList();
                if (!filtered.isEmpty()) log.info("文件源采集到 {} 条（过滤掉 {} 条历史）",
                        filtered.size(), fileEvents.size() - filtered.size());
                processEvents(filtered);
            } catch (Exception e) {
                log.warn("文件采集异常 [{}]: {}", instanceId, e.getMessage());
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

            // 自动补充 projectCode（文件源/表源可能缺失）
            if ((event.getProjectCode() == null || event.getProjectCode().isEmpty())
                    && event.getInstanceId() != null && !event.getInstanceId().isEmpty()) {
                String pc = dataSourceManager.findProjectCode(event.getInstanceId());
                if (pc != null) event.setProjectCode(pc);
            }

            if (!dedupService.tryRegister(fp)) { continue; }

            SlowSqlSeverity severity = SlowSqlSeverity.from(event);
            CapturedSql cs = CapturedSql.fromEvent(event);
            cs.setSeverity(severity.name());

            // 缓存检查：同指纹+同EXPLAIN → 复用报告
            String cachedReport = (severity != SlowSqlSeverity.P0
                    && event.getInstanceId() != null)
                    ? diagnosisCache.checkCache(event.getInstanceId(), event.getSqlText(), fp)
                    : null;
            if (cachedReport != null) {
                cs.setDiagnosisReport(cachedReport);
                log.info("诊断缓存命中: fingerprint={}", fp.substring(0, Math.min(8, fp.length())));
            }
            repository.upsert(cs);

            if (cachedReport == null) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("taskId", UUID.randomUUID().toString());
                msg.put("instanceId", event.getInstanceId() != null ? event.getInstanceId() : "");
                msg.put("projectCode", event.getProjectCode() != null ? event.getProjectCode() : "");
                msg.put("enrichedPrompt", SlowSqlCaptureRouter.buildDiagnosisContext(event));
                msg.put("fingerprint", fp);
                msg.put("source", event.getSource());
                msg.put("timestamp", LocalDateTime.now().toString());
                taskProducer.sendNormal(msg);
            }
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
            var jt = dataSourceManager.getMonitoringTemplate(instanceId);
            double minSec = getMinQueryTimeFor(instanceId);

            Long lastTimerStart = lastTimerStartMap.get(instanceId);

            if (lastTimerStart == null) {
                // 首次轮询：记录当前最大 TIMER_START 作为锚点，不采集历史
                Long maxTimer = jt.queryForObject(
                        "SELECT COALESCE(MAX(TIMER_START), 0) FROM performance_schema.events_statements_history_long",
                        Long.class);
                lastTimerStartMap.put(instanceId, maxTimer != null ? maxTimer : 0L);
                log.info("PS 锚点已记录 [{}]: TIMER_START >= {}", instanceId, lastTimerStartMap.get(instanceId));
                return List.of();
            }

            // 增量查询：只查 TIMER_START > 上次锚点的新事件
            String sql = """
                    SELECT SQL_TEXT AS sql_text,
                           TIMER_WAIT / 1000000000000.0 AS query_time,
                           0 AS lock_time,
                           ROWS_EXAMINED AS rows_examined,
                           ROWS_SENT AS rows_sent,
                           NOW() AS start_time,
                           CURRENT_SCHEMA AS db
                    FROM performance_schema.events_statements_history_long
                    WHERE CURRENT_SCHEMA NOT IN
                           ('information_schema','mysql','performance_schema','sys','slow_sql_platform')
                      AND SQL_TEXT IS NOT NULL
                      AND DIGEST_TEXT IS NOT NULL
                      AND SQL_TEXT NOT LIKE 'SET %'
                      AND SQL_TEXT NOT LIKE 'SHOW %'
                      AND SQL_TEXT NOT LIKE 'SELECT @@%'
                      AND SQL_TEXT NOT LIKE '%ApplicationName%'
                      AND TIMER_WAIT / 1000000000000.0 > ?
                      AND TIMER_START > ?
                    ORDER BY TIMER_START ASC
                    LIMIT 200""";

            List<Map<String, Object>> rows = jt.queryForList(sql, minSec, lastTimerStart);

            // 更新锚点为本次采集到的最大 TIMER_START
            if (!rows.isEmpty()) {
                Long newMax = jt.queryForObject(
                        "SELECT MAX(TIMER_START) FROM performance_schema.events_statements_history_long WHERE TIMER_START > ?",
                        Long.class, lastTimerStart);
                if (newMax != null) {
                    lastTimerStartMap.put(instanceId, newMax);
                }
                log.info("PS 采集到 {} 条 [{}]", rows.size(), instanceId);
            }
            return rows;
        } catch (Exception e) {
            log.warn("PS 读取失败 [{}]: {}", instanceId, e.getMessage());
            return List.of();
        }
    }

    private double getMinQueryTimeFor(String instanceId) {
        Map<String, Object> cfg = captureStatus.getPollingConfigForInstance(instanceId);
        Object v = cfg.get("minQueryTimeSec");
        return v instanceof Number n ? n.doubleValue() : 0.5;
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

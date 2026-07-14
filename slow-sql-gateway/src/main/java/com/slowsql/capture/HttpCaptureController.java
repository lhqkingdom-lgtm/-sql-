package com.slowsql.capture;

import com.slowsql.config.DataSourceManager;
import com.slowsql.gateway.DiagnosisTaskProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * HTTP 慢 SQL 上报端点。业务应用 MyBatis 拦截器拦截到慢 SQL 后 POST 过来。
 * POST /api/capture/sql  body: {"sql": "...", "durationMs": 3500, "rowsExamined": 50000, "database": "test", "instanceId":"tc-dev-mysql"}
 */
@RestController
@RequestMapping("/api/capture")
public class HttpCaptureController implements CaptureSource {

    private static final Logger log = LoggerFactory.getLogger(HttpCaptureController.class);
    private static final int MAX_PENDING = 1000;
    private static final int MAX_SQL_LENGTH = 100_000;

    private final ConcurrentLinkedQueue<CapturedSql> pending = new ConcurrentLinkedQueue<>();
    private volatile boolean configured = false;
    private final DataSourceManager dataSourceManager;
    private final CapturedSqlRepository repository;
    private final DiagnosisTaskProducer taskProducer;
    private final FingerprintDedupService dedupService;
    private final CaptureStatusController captureStatus;

    public HttpCaptureController(DataSourceManager dataSourceManager,
                                  CapturedSqlRepository repository,
                                  DiagnosisTaskProducer taskProducer,
                                  FingerprintDedupService dedupService,
                                  @Lazy CaptureStatusController captureStatus) {
        this.dataSourceManager = dataSourceManager;
        this.repository = repository;
        this.taskProducer = taskProducer;
        this.dedupService = dedupService;
        this.captureStatus = captureStatus;
    }

    @Override public String name() { return "http_endpoint"; }
    @Override public boolean isConfigured() { return configured; }
    public void markConfigured() { this.configured = true; }

    @Override
    public List<SlowSqlEvent> collect() {
        List<CapturedSql> batch = new ArrayList<>();
        CapturedSql cs;
        while ((cs = pending.poll()) != null) batch.add(cs);
        if (!batch.isEmpty()) log.info("HTTP 端点采集到 {} 条慢 SQL", batch.size());
        return batch.stream().map(this::toEvent).toList();
    }

    private SlowSqlEvent toEvent(CapturedSql cs) {
        SlowSqlEvent e = new SlowSqlEvent();
        e.setSqlText(cs.getSqlText());
        e.setSource(cs.getSource());
        e.setDbName(cs.getDatabaseName());
        e.setInstanceId(cs.getInstanceId());
        e.setFingerprint(cs.getFingerprint());
        if (cs.getCapturedAt() != null) e.setCapturedAt(cs.getCapturedAt());
        SlowSqlEvent.EventMetrics m = new SlowSqlEvent.EventMetrics();
        m.setQueryTimeSec(cs.getQueryTimeSec());
        m.setRowsExamined(cs.getRowsExamined());
        e.setMetrics(m);
        return e;
    }

    @PostMapping(value = "/sql", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> receive(@RequestBody Map<String, Object> body) {
        Object sqlObj = body.get("sql");
        if (!(sqlObj instanceof String sql) || sql.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "sql is required"));
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error", "sql exceeds max " + MAX_SQL_LENGTH));
        }

        Object insId = body.get("instanceId");
        // 检查实例是否开启了 HTTP 端点采集
        if (insId instanceof String iid && !iid.isBlank()) {
            if (!captureStatus.isEnabled(iid) || !captureStatus.isSourceEnabled(iid, "http_endpoint")) {
                return ResponseEntity.ok(Map.of("received", false, "reason",
                        "实例 " + iid + " 未开启 HTTP 端点采集，请在轮询管理中开启"));
            }
        }

        if (pending.size() >= MAX_PENDING) {
            log.warn("HTTP 采集队列已满 ({})", MAX_PENDING);
            return ResponseEntity.ok(Map.of("received", false, "reason", "queue full"));
        }

        CapturedSql captured = new CapturedSql();
        captured.setSqlText(sql);
        captured.setSource(name());

        Object dbObj = body.getOrDefault("database", "unknown");
        captured.setDatabaseName(dbObj instanceof String s ? s : String.valueOf(dbObj));

        if (insId instanceof String s) {
            captured.setInstanceId(s);
            // 自动补充 projectCode
            String pc = dataSourceManager.findProjectCode(s);
            if (pc != null) captured.setProjectCode(pc);
        }

        Object durationMs = body.get("durationMs");
        if (durationMs instanceof Number n) captured.setQueryTimeSec(n.doubleValue() / 1000.0);

        Object rowsExamined = body.get("rowsExamined");
        if (rowsExamined instanceof Number n) captured.setRowsExamined(n.longValue());

        captured.setFingerprint(CapturedSql.fingerprint(sql));
        captured.setCapturedAt(LocalDateTime.now());

        // 秒级入库 + 发 RMQ，不等 Scheduler
        try {
            SlowSqlEvent event = toEvent(captured);
            captured.setSeverity(SlowSqlSeverity.from(event).name());

            // 去重：已注册过的指纹只 upsert，不重复投递诊断
            boolean isNew = dedupService.tryRegister(captured.getFingerprint());
            repository.upsert(captured);

            if (isNew) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("taskId", UUID.randomUUID().toString());
                msg.put("instanceId", captured.getInstanceId());
                msg.put("projectCode", captured.getProjectCode());
                msg.put("enrichedPrompt", SlowSqlCaptureRouter.buildDiagnosisContext(event));
                msg.put("originalSql", captured.getSqlText());
                msg.put("fingerprint", captured.getFingerprint());
                msg.put("source", captured.getSource());
                msg.put("timestamp", LocalDateTime.now().toString());
                taskProducer.sendNormal(msg);
            }
        } catch (Exception e) {
            log.warn("HTTP采集即时处理失败，降级到内存队列: {}", e.getMessage());
            pending.offer(captured);
        }

        return ResponseEntity.ok(Map.of("received", true));
    }
}

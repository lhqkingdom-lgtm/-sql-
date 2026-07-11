package com.slowsql.api;

import com.slowsql.capture.EventNormalizer;
import com.slowsql.capture.SlowSqlEvent;
import com.slowsql.config.AuditLogger;
import com.slowsql.config.DataSourceManager;
import com.slowsql.config.SqlMonitorProperties;
import com.slowsql.gateway.AgentClient;
import com.slowsql.gateway.DiagnosisTaskProducer;
import com.slowsql.persistence.DiagnosisRecord;
import com.slowsql.persistence.DiagnosisRecordRepository;
import com.slowsql.rag.RagRetriever;
import com.slowsql.security.MybatisLogParser;
import com.slowsql.security.SqlAstValidator;
import com.slowsql.security.SqlDataMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 诊断入口——只做 CPU 轻量操作，秒返 taskId。
 * DDL/EXPLAIN 不再预加载，由 Python Agent 按需 Function Call 获取。
 */
@RestController
@RequestMapping("/api/sql")
public class SqlAnalyzeController {

    private static final Logger log = LoggerFactory.getLogger(SqlAnalyzeController.class);
    private static final String MYBATIS_LOG = "MYBATIS_LOG";

    private final DiagnosisTaskProducer taskProducer;
    private final AgentClient agentClient;
    private final DataSourceManager dataSourceManager;
    private final DiagnosisRecordRepository recordRepository;
    private final RagRetriever ragRetriever;
    private final EventNormalizer eventNormalizer;
    private final StringRedisTemplate redis;
    private final SqlMonitorProperties properties;
    private final AuditLogger auditLogger;

    public SqlAnalyzeController(DiagnosisTaskProducer taskProducer,
                                 AgentClient agentClient,
                                 DataSourceManager dataSourceManager,
                                 DiagnosisRecordRepository recordRepository,
                                 RagRetriever ragRetriever,
                                 EventNormalizer eventNormalizer,
                                 StringRedisTemplate redis,
                                 SqlMonitorProperties properties,
                                 AuditLogger auditLogger) {
        this.taskProducer = taskProducer;
        this.agentClient = agentClient;
        this.dataSourceManager = dataSourceManager;
        this.recordRepository = recordRepository;
        this.ragRetriever = ragRetriever;
        this.eventNormalizer = eventNormalizer;
        this.redis = redis;
        this.properties = properties;
        this.auditLogger = auditLogger;
    }

    @GetMapping("/projects")
    public ResponseEntity<?> projects() {
        return ResponseEntity.ok(Map.of("projects",
                properties.getProjects().stream().map(p -> Map.of(
                    "code", p.getCode(),
                    "name", p.getName(),
                    "instanceIds", p.getInstanceIds()
                )).toList()));
    }

    @PostMapping(value = "/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> analyze(@RequestBody(required = false) SqlAnalyzeRequest request) {
        // 1. 参数校验
        if (request == null) return badRequest("请求体不能为空", "REQUEST_BLANK");
        if (!StringUtils.hasText(request.instanceId())) return badRequest("instanceId 不能为空", "INSTANCE_BLANK");
        if (!StringUtils.hasText(request.sql())) return badRequest("sql 不能为空", "SQL_BLANK");

        String sessionId = StringUtils.hasText(request.sessionId())
                ? request.sessionId() : UUID.randomUUID().toString();
        String instanceId = request.instanceId();

        // 2. 连接预检
        if (!dataSourceManager.validateConnection(instanceId)) {
            return ResponseEntity.status(503).body(
                    new SqlAnalyzeResponse(null, null, null, "目标数据库不可达", "DATABASE_UNAVAILABLE"));
        }

        // 3. MyBatis 日志解析
        String cleanSql = MYBATIS_LOG.equalsIgnoreCase(request.type())
                ? MybatisLogParser.parse(request.sql()) : request.sql();

        // 4. AST 安全校验
        SqlAstValidator.ValidationResult vr = SqlAstValidator.validateAndExtract(cleanSql);
        if (!vr.isSafe()) {
            return ResponseEntity.ok(new SqlAnalyzeResponse(null, null,
                    "🛑 系统安全拦截\n\n" + vr.errorMessage(), null, "SECURITY_BLOCKED"));
        }

        // 5. 数据脱敏
        String maskedSql = SqlDataMasker.mask(cleanSql, vr.tableNames());

        // 6. RAG 知识检索
        String ragContext = ragRetriever.retrieve(vr.tableNames(), cleanSql);

        // 7. 构造 enrichedPrompt（不含 DDL/EXPLAIN）
        String enrichedPrompt = buildPrompt(ragContext, maskedSql);

        // 8. 统一事件格式
        SlowSqlEvent event = eventNormalizer.fromManual(cleanSql, instanceId, request.projectCode());

        // 9. 直发 Agent HTTP（不走 RMQ），同步等结果
        AgentClient.DiagnoseResult result = agentClient.diagnose(
                enrichedPrompt, instanceId, request.projectCode());

        // 10. 落库
        String taskId = result.getTaskId() != null ? result.getTaskId() : UUID.randomUUID().toString();
        saveDiagnosisRecord(taskId, sessionId, instanceId, request.projectCode(),
                request.sql(), cleanSql, vr.tableNames(), event.getFingerprint(), result);

        // 11. 审计日志
        auditLogger.log(taskId, sessionId, instanceId, request.sql().length(),
                !maskedSql.equals(cleanSql), vr.tableNames().toString(),
                result.getDurationMs(), result.isCompleted() ? "COMPLETED" : "FAILED");

        // 12. 返回诊断结果
        if (result.isCompleted()) {
            return ResponseEntity.ok(new SqlAnalyzeResponse(
                    taskId, "COMPLETED", result.getReport(), null, null));
        }
        return ResponseEntity.ok(new SqlAnalyzeResponse(
                taskId, "FAILED", null, result.getError(), null));
    }

    private String buildPrompt(String ragContext, String sql) {
        StringBuilder sb = new StringBuilder();
        if (!ragContext.isEmpty()) sb.append(ragContext).append("\n\n");
        sb.append("【待分析SQL】\n").append(sql).append("\n\n");
        sb.append("请按需调用工具获取DDL和执行计划进行诊断。");
        return sb.toString();
    }

    private void initTaskRedis(String taskId, String instanceId) {
        try {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("status", "pending");
            meta.put("instanceId", instanceId);
            meta.put("createdAt", LocalDateTime.now().toString());
            redis.opsForHash().putAll("diagnosis:task:" + taskId, meta);
            redis.expire("diagnosis:task:" + taskId, Duration.ofMinutes(30));
        } catch (Exception e) {
            log.warn("Redis任务元数据写入失败: {}", e.getMessage());
        }
    }

    private void saveDiagnosisRecord(String taskId, String sessionId, String instanceId,
                                      String projectCode, String originalSql, String cleanSql,
                                      List<String> tableNames, String fingerprint,
                                      AgentClient.DiagnoseResult result) {
        try {
            DiagnosisRecord r = DiagnosisRecord.create(taskId, sessionId, instanceId, projectCode, "manual");
            r.setOriginalSql(originalSql);
            r.setCleanSql(cleanSql);
            r.setTableNames(tableNames != null ? tableNames.toString() : "[]");
            r.setFingerprint(fingerprint);
            r.setStatus(result.isCompleted() ? DiagnosisRecord.STATUS_COMPLETED : DiagnosisRecord.STATUS_FAILED);
            r.setReport(result.getReport());
            r.setErrorMessage(result.getError());
            r.setDurationMs(result.getDurationMs());
            r.setToolCallCount(result.getToolCallCount());
            r.setCreatedAt(LocalDateTime.now());
            r.setUpdatedAt(LocalDateTime.now());
            recordRepository.save(r);
        } catch (Exception e) {
            log.warn("诊断记录保存失败: {}", e.getMessage());
        }
    }

    /** 实例健康 */
    @GetMapping("/instances/health")
    public ResponseEntity<?> instancesHealth() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SqlMonitorProperties.InstanceConfig inst : properties.getInstances()) {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("instanceId", inst.getId());
            status.put("host", inst.getHost() + ":" + inst.getPort());
            status.put("projectCode", dataSourceManager.findProjectCode(inst.getId()));
            try { status.put("reachable", dataSourceManager.validateConnection(inst.getId())); }
            catch (Exception e) { status.put("reachable", false); }
            result.add(status);
        }
        return ResponseEntity.ok(result);
    }

    /** 诊断历史 */
    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestParam(required = false) String projectCode,
                                      @RequestParam(required = false) String instanceId,
                                      @RequestParam(defaultValue = "20") int limit) {
        List<com.slowsql.persistence.DiagnosisRecord> records = recordRepository.findHistory(projectCode, instanceId, null, null, null, null, 0, limit);
        return ResponseEntity.ok(records.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", r.getTaskId()); m.put("projectCode", r.getProjectCode());
            m.put("instanceId", r.getInstanceId());
            m.put("sqlPreview", r.getOriginalSql() != null && r.getOriginalSql().length() > 100
                    ? r.getOriginalSql().substring(0, 100) + "..." : r.getOriginalSql());
            m.put("status", r.getStatus()); m.put("source", r.getSource());
            m.put("durationMs", r.getDurationMs()); m.put("toolCallCount", r.getToolCallCount());
            m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
            return m;
        }).toList());
    }

    /** 诊断详情 */
    @GetMapping("/history/{taskId}")
    public ResponseEntity<?> historyDetail(@PathVariable String taskId) {
        com.slowsql.persistence.DiagnosisRecord r = recordRepository.findByTaskId(taskId);
        if (r == null) return ResponseEntity.notFound().build();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", r.getTaskId()); m.put("projectCode", r.getProjectCode());
        m.put("instanceId", r.getInstanceId()); m.put("originalSql", r.getOriginalSql());
        m.put("report", r.getReport()); m.put("status", r.getStatus());
        m.put("durationMs", r.getDurationMs()); m.put("toolCallCount", r.getToolCallCount());
        m.put("source", r.getSource()); m.put("fingerprint", r.getFingerprint());
        m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return ResponseEntity.ok(m);
    }

    /** 重新诊断——直发 Agent HTTP */
    @PostMapping("/retry/{taskId}")
    public ResponseEntity<?> retry(@PathVariable String taskId) {
        com.slowsql.persistence.DiagnosisRecord r = recordRepository.findByTaskId(taskId);
        if (r == null) return ResponseEntity.notFound().build();
        if (r.getOriginalSql() == null || r.getOriginalSql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "原始SQL为空"));
        }

        AgentClient.DiagnoseResult result = agentClient.diagnose(
                "【重试诊断】\n【原始SQL】\n" + r.getOriginalSql(),
                r.getInstanceId(), r.getProjectCode());

        String newTaskId = result.getTaskId() != null ? result.getTaskId() : UUID.randomUUID().toString();
        return ResponseEntity.ok(Map.of(
                "taskId", newTaskId,
                "status", result.getStatus(),
                "report", result.getReport() != null ? result.getReport() : "",
                "error", result.getError() != null ? result.getError() : ""));
    }

    /** 诊断进度——前端轮询展示工具调用步骤 */
    @GetMapping("/progress/{taskId}")
    public ResponseEntity<?> progress(@PathVariable String taskId) {
        try {
            String json = redis.opsForValue().get("diagnosis:progress:" + taskId);
            if (json != null) return ResponseEntity.ok(json);
            // fallback: check task status hash
            Map<Object, Object> status = redis.opsForHash().entries("diagnosis:task:" + taskId);
            return ResponseEntity.ok(Map.of("status", status.getOrDefault("status", "not_found")));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    /** 降级队列状态 */
    @GetMapping("/fallback")
    public ResponseEntity<?> fallbackStatus() {
        try {
            Long size = redis.opsForList().size("diagnosis:fallback:queue");
            return ResponseEntity.ok(Map.of("queueSize", size != null ? size : 0));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("queueSize", -1, "error", "Redis不可达"));
        }
    }

    private ResponseEntity<?> badRequest(String msg, String code) {
        return ResponseEntity.badRequest().body(
                new SqlAnalyzeResponse(null, null, null, msg, code));
    }
}

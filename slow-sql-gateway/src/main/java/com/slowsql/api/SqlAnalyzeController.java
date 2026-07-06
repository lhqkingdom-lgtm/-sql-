package com.slowsql.api;

import com.slowsql.capture.EventNormalizer;
import com.slowsql.capture.SlowSqlEvent;
import com.slowsql.config.DataSourceManager;
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
    private final DataSourceManager dataSourceManager;
    private final DiagnosisRecordRepository recordRepository;
    private final RagRetriever ragRetriever;
    private final EventNormalizer eventNormalizer;
    private final StringRedisTemplate redis;

    public SqlAnalyzeController(DiagnosisTaskProducer taskProducer,
                                 DataSourceManager dataSourceManager,
                                 DiagnosisRecordRepository recordRepository,
                                 RagRetriever ragRetriever,
                                 EventNormalizer eventNormalizer,
                                 StringRedisTemplate redis) {
        this.taskProducer = taskProducer;
        this.dataSourceManager = dataSourceManager;
        this.recordRepository = recordRepository;
        this.ragRetriever = ragRetriever;
        this.eventNormalizer = eventNormalizer;
        this.redis = redis;
    }

    @GetMapping("/projects")
    public ResponseEntity<?> projects() {
        // 返回项目列表供前端级联选择
        return ResponseEntity.ok(Map.of("projects", List.of())); // TODO: from properties
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

        // 8. 生成 taskId + 写任务元数据
        String taskId = UUID.randomUUID().toString();
        initTaskRedis(taskId, instanceId);

        // 9. 统一事件格式
        SlowSqlEvent event = eventNormalizer.fromManual(cleanSql, instanceId, request.projectCode());

        // 10. 投递 RabbitMQ
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("taskId", taskId);
        msg.put("sessionId", sessionId);
        msg.put("instanceId", instanceId);
        msg.put("projectCode", request.projectCode());
        msg.put("enrichedPrompt", enrichedPrompt);
        msg.put("fingerprint", event.getFingerprint());
        msg.put("source", "manual");
        msg.put("timestamp", LocalDateTime.now().toString());
        taskProducer.sendHigh(msg);

        // 11. 异步落库初始记录
        saveInitialRecord(taskId, sessionId, instanceId, request.projectCode(),
                request.sql(), cleanSql, vr.tableNames(), event.getFingerprint());

        // 12. 秒返
        return ResponseEntity.accepted().body(SqlAnalyzeResponse.pending(taskId));
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

    private void saveInitialRecord(String taskId, String sessionId, String instanceId,
                                    String projectCode, String originalSql, String cleanSql,
                                    List<String> tableNames, String fingerprint) {
        try {
            DiagnosisRecord r = DiagnosisRecord.create(taskId, sessionId, instanceId, projectCode, "manual");
            r.setOriginalSql(originalSql);
            r.setCleanSql(cleanSql);
            r.setTableNames(tableNames != null ? tableNames.toString() : "[]");
            r.setFingerprint(fingerprint);
            r.setStatus(DiagnosisRecord.STATUS_PENDING);
            r.setCreatedAt(LocalDateTime.now());
            recordRepository.save(r);
        } catch (Exception e) {
            log.warn("初始化诊断记录失败: {}", e.getMessage());
        }
    }

    private ResponseEntity<?> badRequest(String msg, String code) {
        return ResponseEntity.badRequest().body(
                new SqlAnalyzeResponse(null, null, null, msg, code));
    }
}

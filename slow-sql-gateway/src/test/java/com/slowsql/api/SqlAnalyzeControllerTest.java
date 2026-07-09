package com.slowsql.api;

import com.slowsql.capture.EventNormalizer;
import com.slowsql.capture.SlowSqlEvent;
import com.slowsql.config.AuditLogger;
import com.slowsql.config.DataSourceManager;
import com.slowsql.config.SqlMonitorProperties;
import com.slowsql.gateway.DiagnosisTaskProducer;
import com.slowsql.persistence.DiagnosisRecord;
import com.slowsql.persistence.DiagnosisRecordRepository;
import com.slowsql.rag.RagRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SqlAnalyzeControllerTest {

    private MockMvc mvc;
    private DiagnosisTaskProducer taskProducer;
    private DataSourceManager dataSourceManager;
    private DiagnosisRecordRepository recordRepository;
    private RagRetriever ragRetriever;
    private EventNormalizer eventNormalizer;
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private SqlMonitorProperties properties;
    private AuditLogger auditLogger;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        taskProducer = mock(DiagnosisTaskProducer.class);
        dataSourceManager = mock(DataSourceManager.class);
        recordRepository = mock(DiagnosisRecordRepository.class);
        ragRetriever = mock(RagRetriever.class);
        eventNormalizer = new EventNormalizer();
        redis = mock(StringRedisTemplate.class);
        properties = new SqlMonitorProperties();

        // 配置 projects 用于 /projects 端点
        SqlMonitorProperties.ProjectConfig project = new SqlMonitorProperties.ProjectConfig();
        project.setCode("tongcheng-club");
        project.setName("同城俱乐部");
        project.setInstanceIds(List.of("tc-dev-mysql"));
        properties.setProjects(List.of(project));

        // Redis Hash mock
        hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);

        auditLogger = mock(AuditLogger.class);

        SqlAnalyzeController controller = new SqlAnalyzeController(
                taskProducer, dataSourceManager, recordRepository, ragRetriever,
                eventNormalizer, redis, properties, auditLogger);

        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ===== GET /projects =====

    @Test
    void projects_shouldReturnProjectList() throws Exception {
        mvc.perform(get("/api/sql/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].code").value("tongcheng-club"))
                .andExpect(jsonPath("$.projects[0].name").value("同城俱乐部"))
                .andExpect(jsonPath("$.projects[0].instanceIds[0]").value("tc-dev-mysql"));
    }

    // ===== POST /analyze — 参数校验 =====

    @Test
    void analyze_shouldRejectNullBody() throws Exception {
        mvc.perform(post("/api/sql/analyze").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_BLANK"));
    }

    @Test
    void analyze_shouldRejectEmptyInstanceId() throws Exception {
        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"\",\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INSTANCE_BLANK"));
    }

    @Test
    void analyze_shouldRejectEmptySql() throws Exception {
        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"tc-dev-mysql\",\"sql\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SQL_BLANK"));
    }

    // ===== POST /analyze — 连接预检失败 =====

    @Test
    void analyze_shouldReturn503WhenDatabaseUnreachable() throws Exception {
        when(dataSourceManager.validateConnection("tc-dev-mysql")).thenReturn(false);

        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"tc-dev-mysql\",\"sql\":\"SELECT * FROM orders\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DATABASE_UNAVAILABLE"));
    }

    // ===== POST /analyze — 安全拦截 =====

    @Test
    void analyze_shouldBlockDangerousSql() throws Exception {
        when(dataSourceManager.validateConnection("tc-dev-mysql")).thenReturn(true);

        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"tc-dev-mysql\",\"sql\":\"DROP TABLE orders\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("SECURITY_BLOCKED"));
    }

    @Test
    void analyze_shouldBlockMultiStatement() throws Exception {
        when(dataSourceManager.validateConnection("tc-dev-mysql")).thenReturn(true);

        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"tc-dev-mysql\",\"sql\":\"SELECT 1; DROP TABLE orders\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("SECURITY_BLOCKED"));
    }

    // ===== POST /analyze — 正常路径 =====

    @Test
    void analyze_shouldAcceptValidSqlAndReturnPending() throws Exception {
        when(dataSourceManager.validateConnection("tc-dev-mysql")).thenReturn(true);
        when(ragRetriever.retrieve(anyList(), anyString())).thenReturn("");
        doNothing().when(taskProducer).sendHigh(anyMap());

        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"tc-dev-mysql\",\"sql\":\"SELECT * FROM orders WHERE id=1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void analyze_shouldIncludeRagContext() throws Exception {
        when(dataSourceManager.validateConnection("tc-dev-mysql")).thenReturn(true);
        when(ragRetriever.retrieve(anyList(), anyString()))
                .thenReturn("【企业知识库匹配规则】\n**规则1** [军规]: 避免SELECT *");
        doNothing().when(taskProducer).sendHigh(anyMap());

        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"tc-dev-mysql\",\"sql\":\"SELECT * FROM orders\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isNotEmpty());
    }

    @Test
    void analyze_shouldParseMybatisLogWhenTypeIsMybatisLog() throws Exception {
        when(dataSourceManager.validateConnection("tc-dev-mysql")).thenReturn(true);
        when(ragRetriever.retrieve(anyList(), anyString())).thenReturn("");
        doNothing().when(taskProducer).sendHigh(anyMap());

        String mybatisLog = "Preparing: SELECT * FROM orders WHERE id = ? AND status = ?\n"
                          + "Parameters: 1(Long), done(String)";

        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"tc-dev-mysql\",\"sql\":\"" + escape(mybatisLog)
                               + "\",\"type\":\"MYBATIS_LOG\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void analyze_shouldUseGivenSessionId() throws Exception {
        when(dataSourceManager.validateConnection("tc-dev-mysql")).thenReturn(true);
        when(ragRetriever.retrieve(anyList(), anyString())).thenReturn("");
        doNothing().when(taskProducer).sendHigh(anyMap());

        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"my-session\",\"instanceId\":\"tc-dev-mysql\",\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void analyze_shouldAutoGenerateSessionIdWhenMissing() throws Exception {
        when(dataSourceManager.validateConnection("tc-dev-mysql")).thenReturn(true);
        when(ragRetriever.retrieve(anyList(), anyString())).thenReturn("");
        doNothing().when(taskProducer).sendHigh(anyMap());

        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"tc-dev-mysql\",\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").isNotEmpty());
    }

    // ===== POST /analyze — Redis 写入失败不影响主流程 =====

    @Test
    void analyze_shouldSucceedEvenWhenRedisFails() throws Exception {
        when(dataSourceManager.validateConnection("tc-dev-mysql")).thenReturn(true);
        when(ragRetriever.retrieve(anyList(), anyString())).thenReturn("");
        doNothing().when(taskProducer).sendHigh(anyMap());
        doThrow(new RuntimeException("Redis down"))
                .when(hashOps).putAll(anyString(), anyMap());

        mvc.perform(post("/api/sql/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":\"tc-dev-mysql\",\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isAccepted());  // 仍秒返
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
